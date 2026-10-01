import { normalize } from './libretro.js';

// Wikipedia (gratuit, sans compte) : résumé d'un jeu quand ScreenScraper n'en fournit pas, et
// identification d'un jeu introuvable ailleurs (fichier mal nommé) : titre officiel de l'article,
// image, et informations de Wikidata (développeur, éditeur, date, genre, modes de jeu).

// Politique de Wikimedia : les clients doivent s'identifier.
const HEADERS = { 'User-Agent': 'RomCloud (https://github.com/biliboobrian/romCloud)' };

/** Mot-clé de recherche et indice « c'est bien un jeu » dans le résumé, par langue. */
const LANGS = {
  fr: { query: 'jeu vidéo', game: /\bjeux?\b/i },
  en: { query: 'video game', game: /\bgames?\b/i },
  de: { query: 'Videospiel', game: /spiel/i },
  es: { query: 'videojuego', game: /\bjuegos?\b|videojuego/i },
  it: { query: 'videogioco', game: /videogioco|\bgioco\b/i },
};

async function getJson(url) {
  const res = await fetch(url, { headers: HEADERS });
  if (!res.ok) return null;
  return res.json();
}

/**
 * Premier article dont le titre correspond au jeu (« Sonic the Hedgehog 2 (jeu vidéo, 16 bits) »
 * convient pour « Sonic the Hedgehog 2 ») ; null si aucun ne correspond. [loose] : à défaut, un
 * article dont le titre commence par celui recherché (« fatal fury » -> « Fatal Fury: King of
 * Fighters »), le plus court.
 */
export function matchArticle(results, title, { loose = false } = {}) {
  const wanted = normalize(title);
  if (!wanted) return null;
  const exact = results.find((r) => normalize(r.title) === wanted)?.title;
  if (exact || !loose) return exact ?? null;
  const prefixed = results
    .filter((r) => normalize(r.title).startsWith(wanted) && !isSequelOf(r.title, wanted))
    .map((r) => r.title);
  return prefixed.sort((a, b) => a.length - b.length)[0] ?? null;
}

/** « Fatal Fury 2 » ou « Street Fighter II » pour « fatal fury » / « street fighter » : une suite. */
export function isSequelOf(title, wanted) {
  const words = title.split(/[\s:–-]+/).filter(Boolean);
  let joined = '';
  for (let i = 0; i < words.length; i++) {
    joined += words[i].toLowerCase().replace(/[^a-z0-9]+/g, '');
    if (joined === wanted) return /^(\d+|[ivx]+)$/i.test(words[i + 1] || '');
    if (!wanted.startsWith(joined)) return false;
  }
  return false;
}

// Mots trop généraux pour reconnaître une plateforme dans un résumé (« Sony PlayStation 2 » -> « playstation »).
const GENERIC = new Set(['nintendo', 'sony', 'sega', 'atari', 'snk', 'amstrad', 'nec', 'bandai', 'microsoft', 'commodore', 'the', 'and', 'system', 'console', 'entertainment', 'computer']);

/** Le texte cite-t-il la plateforme du système (un mot distinctif de son nom) ? */
export function mentionsPlatform(text, system) {
  const words = String(system || '').toLowerCase().split(/[^a-z0-9]+/).filter((w) => w.length >= 2 && !GENERIC.has(w));
  if (!words.length) return true;
  const haystack = String(text || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ');
  return words.some((w) => new RegExp(`\\b${w}\\b`).test(haystack) || haystack.replace(/ /g, '').includes(w));
}

/** Titre de l'article sans la précision entre parenthèses : « Pang (jeu vidéo) » -> « Pang ». */
export const gameTitleOf = (page) => page.replace(/\s*\([^)]*\)\s*$/, '').trim();

/**
 * Résumé Wikipedia du jeu et adresse de l'article ({ text, url }), dans la première des
 * [languages] qui a un article correspondant ; null si aucun. [system] (« Super Nintendo »)
 * affine la recherche.
 */
export async function scrapeWikipedia({ title, system, languages, loose = false }) {
  for (const lang of languages) {
    const conf = LANGS[lang];
    if (!conf) continue;
    const api = `https://${lang}.wikipedia.org`;
    const searchFor = async (query) =>
      (await getJson(`${api}/w/api.php?action=query&list=search&format=json&srlimit=8&srprop=&srsearch=${encodeURIComponent(query.trim())}`))
        ?.query?.search || [];
    let page = matchArticle(await searchFor(`${title} ${conf.query} ${system || ''}`), title, { loose });
    // Nom du système absent des articles (« Amstrad GX4000 ») : nouvel essai sans lui.
    if (!page && system) page = matchArticle(await searchFor(`${title} ${conf.query}`), title, { loose });
    if (!page) continue;
    const summary = await getJson(`${api}/api/rest_v1/page/summary/${encodeURIComponent(page.replace(/ /g, '_'))}`);
    const text = summary?.extract?.trim();
    if (!text || summary.type === 'disambiguation') continue;
    // Homonymes (film, personnage…) : le résumé doit parler d'un jeu.
    if (!conf.game.test(`${summary.description || ''} ${text}`)) continue;
    // Correspondance approchée : l'article doit en plus citer la plateforme du système.
    if (normalize(page) !== normalize(title) && !mentionsPlatform(`${summary.description || ''} ${text}`, system)) continue;
    const url = summary.content_urls?.desktop?.page || `${api}/wiki/${encodeURIComponent(page.replace(/ /g, '_'))}`;
    return {
      text,
      url,
      title: gameTitleOf(summary.titles?.normalized || page),
      image: summary.originalimage?.source || summary.thumbnail?.source || null,
      wikidataId: summary.wikibase_item || null,
      language: lang,
    };
  }
  return null;
}

// ---------------------------------------------------------------------------
// Wikidata : informations structurées de l'article (élément « Q… »)
// ---------------------------------------------------------------------------

const PROPS = { developer: 'P178', publisher: 'P123', genre: 'P136', modes: 'P404', date: 'P577' };

const entityIds = (claims, prop) =>
  (claims?.[prop] || []).map((c) => c.mainsnak?.datavalue?.value?.id).filter(Boolean);

/** Date la plus ancienne (« +1991-11-21T00:00:00Z », précision jour / mois / année). */
export function earliestDate(claims) {
  const dates = (claims?.[PROPS.date] || [])
    .map((c) => c.mainsnak?.datavalue?.value)
    .filter((v) => v?.time)
    .map((v) => {
      const m = /^\+?(\d{4})-(\d{2})-(\d{2})/.exec(v.time);
      if (!m) return null;
      return v.precision >= 11 ? `${m[1]}-${m[2]}-${m[3]}` : v.precision === 10 ? `${m[1]}-${m[2]}` : m[1];
    })
    .filter(Boolean)
    .sort();
  return dates[0] || null;
}

/**
 * Développeur, éditeur, genres, modes de jeu et date de sortie d'un élément Wikidata, libellés
 * dans la première des [languages] disponible ; null en cas d'échec.
 */
export async function wikidataFacts(id, languages) {
  if (!/^Q\d+$/.test(id || '')) return null;
  const entity = (await getJson(`https://www.wikidata.org/wiki/Special:EntityData/${id}.json`))?.entities?.[id];
  const claims = entity?.claims;
  if (!claims) return null;
  const ids = {};
  for (const key of ['developer', 'publisher', 'genre', 'modes']) ids[key] = entityIds(claims, PROPS[key]).slice(0, 5);
  const all = [...new Set(Object.values(ids).flat())];
  const labels = {};
  if (all.length) {
    const langs = [...languages, 'en'].join('|');
    const res = await getJson(
      `https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=labels&languages=${langs}&ids=${all.join('|')}`,
    );
    for (const [qid, e] of Object.entries(res?.entities || {})) {
      const lang = [...languages, 'en'].find((l) => e.labels?.[l]);
      if (lang) labels[qid] = e.labels[lang].value;
    }
  }
  const names = (key) => ids[key].map((q) => labels[q]).filter(Boolean);
  return {
    developer: names('developer').join(', ') || null,
    publisher: names('publisher').join(', ') || null,
    genre: names('genre').slice(0, 3).join(', ') || null,
    modes: names('modes'),
    releaseDate: earliestDate(claims),
  };
}
