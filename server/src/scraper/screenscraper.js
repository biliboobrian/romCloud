// Scraper ScreenScraper.fr (API v2). Nécessite des identifiants développeur
// (SCREENSCRAPER_DEV_ID / SCREENSCRAPER_DEV_PASSWORD) ; le compte utilisateur
// (SCREENSCRAPER_USER / SCREENSCRAPER_PASSWORD) est facultatif mais augmente les quotas.
import { config } from '../config.js';
import { serialFromFileName } from './serial.js';
import { I18nError } from '../i18n.js';
import { compactDetails } from './details.js';
import { normalize } from './libretro.js';
import { SCREENSCRAPER_SYSTEM_IDS } from '../screenscraper-systems.js';

const API = 'https://api.screenscraper.fr/api2/jeuInfos.php';
const SEARCH_API = 'https://api.screenscraper.fr/api2/jeuRecherche.php';

export class QuotaError extends I18nError {
  name = 'QuotaError';
}

function pickText(list, key, preferred) {
  if (!Array.isArray(list) || !list.length) return null;
  for (const p of preferred) {
    const hit = list.find((e) => e[key] === p);
    if (hit?.text) return hit.text;
  }
  return list[0].text ?? null;
}

function pickMedia(medias, types, regions) {
  for (const type of types) {
    const ofType = medias.filter((m) => m.type === type && m.url);
    if (!ofType.length) continue;
    for (const r of regions) {
      const hit = ofType.find((m) => m.region === r);
      if (hit) return hit.url;
    }
    return ofType[0].url;
  }
  return null;
}

/** Valeur texte : chaîne, ou objet { text } selon les champs de l'API. */
const textOf = (v) => (v == null ? null : typeof v === 'object' ? (v.text ?? null) : String(v)) || null;

/** Libellés traduits d'une liste (genres, modes, familles…) dans la langue préférée. */
const labelsOf = (list, languages) =>
  [...new Set((Array.isArray(list) ? list : []).map((g) => pickText(g.noms, 'langue', languages)).filter(Boolean))];

/** Valeurs par région / langue, dédoublonnées : [{ region, text }]. */
const byRegion = (list) => {
  const out = [];
  for (const e of Array.isArray(list) ? list : []) {
    if (e?.text && !out.some((o) => o.region === e.region && o.text === e.text)) out.push({ region: e.region || null, text: e.text });
  }
  return out;
};

/** Liste d'une ROM (`regions_fr`, `langues_en`…) dans la première langue disponible. */
function romList(obj, prefix, languages) {
  if (!obj || typeof obj !== 'object') return [];
  for (const lang of [...languages, 'en', 'shortname']) {
    const list = obj[`${prefix}_${lang}`];
    if (Array.isArray(list) && list.length) return list;
  }
  return [];
}

/** Informations détaillées (voir details.js). */
function parseDetails(jeu, title) {
  const { languages } = config.screenscraper;
  const rom = jeu.rom && typeof jeu.rom === 'object' ? jeu.rom : {};
  const flags = { beta: 'Beta', demo: 'Demo', proto: 'Proto', hack: 'Hack', trad: 'Translation', unl: 'Unl' };
  return compactDetails({
    otherTitles: byRegion(jeu.noms).filter((n) => n.text !== title),
    releaseDates: byRegion(jeu.dates),
    series: labelsOf(jeu.familles, languages).join(', '),
    modes: labelsOf(jeu.modes, languages),
    themes: labelsOf(jeu.themes, languages),
    ageRatings: (Array.isArray(jeu.classifications) ? jeu.classifications : [])
      .filter((c) => c?.text)
      .map((c) => ({ type: c.type || null, text: c.text })),
    regions: romList(rom.regions, 'regions', languages),
    languages: romList(rom.langues, 'langues', languages),
    serial: textOf(rom.romserial),
    romFlags: Object.entries(flags).filter(([k]) => String(rom[k]) === '1').map(([, v]) => v),
    resolution: textOf(jeu.resolution),
    rotation: textOf(jeu.rotation) && textOf(jeu.rotation) !== '0' ? `${textOf(jeu.rotation)}°` : null,
    controls: textOf(jeu.controles),
    links: [{ label: 'ScreenScraper', url: `https://www.screenscraper.fr/gameinfos.php?gameid=${encodeURIComponent(jeu.id)}` }],
  });
}

function parseGame(jeu) {
  const { languages, regions } = config.screenscraper;
  const medias = Array.isArray(jeu.medias) ? jeu.medias : [];
  const genres = labelsOf(jeu.genres, languages).slice(0, 3);
  const date = pickText(jeu.dates, 'region', regions);
  const note = jeu.note?.text ? Number(jeu.note.text) : null;
  const title = pickText(jeu.noms, 'region', regions);
  return {
    title,
    description: pickText(jeu.synopsis, 'langue', languages),
    releaseDate: date,
    developer: jeu.developpeur?.text ?? null,
    publisher: jeu.editeur?.text ?? null,
    genre: genres.join(', ') || null,
    players: jeu.joueurs?.text ?? null,
    // ScreenScraper note sur 20 : on ramène sur 5.
    rating: Number.isFinite(note) && note > 0 ? Math.round((note / 4) * 10) / 10 : null,
    media: {
      boxart: pickMedia(medias, ['box-2D', 'box-3D', 'wheel'], regions),
      screenshot: pickMedia(medias, ['ss', 'sstitle'], regions),
    },
    details: parseDetails(jeu, title),
  };
}

/**
 * @param {{ system: object, fileName: string, size: number, crc32?: string, md5?: string }} rom
 * @returns {Promise<null | object>} null si le jeu est introuvable.
 */
/**
 * Recherche par titre (jeuRecherche) : homebrew ou fichier renommé, inconnus par leur nom de fichier
 * et leur empreinte. Seul un jeu dont un des noms correspond exactement au titre est retenu.
 */
export async function searchScreenScraper({ system, title }) {
  const s = config.screenscraper;
  if (!s.devId || !s.devPassword || !title) return null;
  const wanted = normalize(title);
  if (!wanted) return null;
  const params = new URLSearchParams({ devid: s.devId, devpassword: s.devPassword, softname: s.softName, output: 'json', recherche: title });
  if (s.user) params.set('ssid', s.user);
  if (s.password) params.set('sspassword', s.password);
  const systemId = system.screenscraperId || SCREENSCRAPER_SYSTEM_IDS[system.shortname] || SCREENSCRAPER_SYSTEM_IDS[system.id];
  if (systemId) params.set('systemeid', String(systemId));
  const res = await fetch(`${SEARCH_API}?${params}`, { headers: { 'User-Agent': s.softName } });
  const body = await res.text();
  if (res.status === 404) return null;
  if (res.status === 429 || res.status === 430 || res.status === 431) {
    throw new QuotaError('scrape.ssQuota', { status: res.status, detail: body.trim().slice(0, 200) });
  }
  if (!res.ok) throw new I18nError('scrape.ssError', { status: res.status, detail: body.trim().slice(0, 200) });
  let json;
  try {
    json = JSON.parse(body);
  } catch {
    throw new I18nError('scrape.ssUnreadable', { detail: body.trim().slice(0, 200) });
  }
  const games = (Array.isArray(json?.response?.jeux) ? json.response.jeux : []).filter((j) => j?.id);
  const match = games.find((j) => (Array.isArray(j.noms) ? j.noms : []).some((n) => n?.text && normalize(n.text) === wanted));
  return match ? parseGame(match) : null;
}

export async function scrapeScreenScraper({ system, fileName, size, crc32, md5 }) {
  const serial = serialFromFileName(fileName);
  const s = config.screenscraper;
  if (!s.devId || !s.devPassword) throw new I18nError('scrape.ssNotConfigured');
  const params = new URLSearchParams({
    devid: s.devId,
    devpassword: s.devPassword,
    softname: s.softName,
    output: 'json',
    romtype: 'rom',
    romnom: fileName,
    romtaille: String(size),
  });
  if (s.user) params.set('ssid', s.user);
  if (s.password) params.set('sspassword', s.password);
  // Système sans identifiant (créé à la main) : celui de la plateforme de même nom court s'il est connu.
  const systemId = system.screenscraperId || SCREENSCRAPER_SYSTEM_IDS[system.shortname] || SCREENSCRAPER_SYSTEM_IDS[system.id];
  if (systemId) params.set('systemeid', String(systemId));
  if (crc32) params.set('crc', crc32.toUpperCase());
  if (md5) params.set('md5', md5);
  // Numéro de série du nom de fichier (identifiant de contenu PS Vita, PSP…) : identifie le jeu
  // quand le nom n'est pas un titre.
  if (serial) params.set('serialnum', serial);

  const res = await fetch(`${API}?${params}`, { headers: { 'User-Agent': s.softName } });
  const body = await res.text();
  if (res.status === 404) return null;
  if (res.status === 429 || res.status === 430 || res.status === 431) {
    throw new QuotaError('scrape.ssQuota', { status: res.status, detail: body.trim().slice(0, 200) });
  }
  if (!res.ok) throw new I18nError('scrape.ssError', { status: res.status, detail: body.trim().slice(0, 200) });
  let json;
  try {
    json = JSON.parse(body);
  } catch {
    throw new I18nError('scrape.ssUnreadable', { detail: body.trim().slice(0, 200) });
  }
  const jeu = json?.response?.jeu;
  if (!jeu || !jeu.id) return null;
  return parseGame(jeu);
}
