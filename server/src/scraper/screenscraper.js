// Scraper ScreenScraper.fr (API v2). Nécessite des identifiants développeur
// (SCREENSCRAPER_DEV_ID / SCREENSCRAPER_DEV_PASSWORD) ; le compte utilisateur
// (SCREENSCRAPER_USER / SCREENSCRAPER_PASSWORD) est facultatif mais augmente les quotas.
import { config } from '../config.js';
import { I18nError } from '../i18n.js';

const API = 'https://api.screenscraper.fr/api2/jeuInfos.php';

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

function parseGame(jeu) {
  const { languages, regions } = config.screenscraper;
  const medias = Array.isArray(jeu.medias) ? jeu.medias : [];
  const genres = (jeu.genres || [])
    .map((g) => pickText(g.noms, 'langue', languages))
    .filter(Boolean)
    .slice(0, 3);
  const date = pickText(jeu.dates, 'region', regions);
  const note = jeu.note?.text ? Number(jeu.note.text) : null;
  return {
    title: pickText(jeu.noms, 'region', regions),
    description: pickText(jeu.synopsis, 'langue', languages),
    releaseDate: date,
    developer: jeu.developpeur?.text ?? null,
    publisher: jeu.editeur?.text ?? null,
    genre: genres.join(', ') || null,
    players: jeu.joueurs?.text ?? null,
    // ScreenScraper note sur 20 : on ramène sur 5.
    rating: Number.isFinite(note) ? Math.round((note / 4) * 10) / 10 : null,
    media: {
      boxart: pickMedia(medias, ['box-2D', 'box-3D', 'wheel'], regions),
      screenshot: pickMedia(medias, ['ss', 'sstitle'], regions),
    },
  };
}

/**
 * @param {{ system: object, fileName: string, size: number, crc32?: string, md5?: string }} rom
 * @returns {Promise<null | object>} null si le jeu est introuvable.
 */
export async function scrapeScreenScraper({ system, fileName, size, crc32, md5 }) {
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
  if (system.screenscraperId) params.set('systemeid', String(system.screenscraperId));
  if (crc32) params.set('crc', crc32.toUpperCase());
  if (md5) params.set('md5', md5);

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
