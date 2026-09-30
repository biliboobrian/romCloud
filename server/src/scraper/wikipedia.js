import { normalize } from './libretro.js';

// Description d'un jeu depuis Wikipedia (gratuit, sans compte) : complète la source Libretro,
// qui ne fournit aucun texte, quand ScreenScraper n'est pas configuré ou n'a pas de résumé.

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
 * convient pour « Sonic the Hedgehog 2 ») ; null si aucun ne correspond.
 */
export function matchArticle(results, title) {
  const wanted = normalize(title);
  if (!wanted) return null;
  return results.find((r) => normalize(r.title) === wanted)?.title ?? null;
}

/**
 * Résumé Wikipedia du jeu, dans la première des [languages] qui a un article correspondant ;
 * null si aucun. [system] (« Super Nintendo ») affine la recherche.
 */
export async function scrapeWikipedia({ title, system, languages }) {
  for (const lang of languages) {
    const conf = LANGS[lang];
    if (!conf) continue;
    const api = `https://${lang}.wikipedia.org`;
    const search = await getJson(
      `${api}/w/api.php?action=query&list=search&format=json&srlimit=8&srprop=&srsearch=${encodeURIComponent(`${title} ${conf.query} ${system || ''}`.trim())}`,
    );
    const page = matchArticle(search?.query?.search || [], title);
    if (!page) continue;
    const summary = await getJson(`${api}/api/rest_v1/page/summary/${encodeURIComponent(page.replace(/ /g, '_'))}`);
    const text = summary?.extract?.trim();
    if (!text || summary.type === 'disambiguation') continue;
    // Homonymes (film, personnage…) : le résumé doit parler d'un jeu.
    if (!conf.game.test(`${summary.description || ''} ${text}`)) continue;
    return text;
  }
  return null;
}
