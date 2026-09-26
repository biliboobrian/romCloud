// Scraper basé sur les miniatures Libretro (https://thumbnails.libretro.com) :
// gratuit et sans compte, fournit jaquette, capture et écran-titre (pas de texte).

const BASE = 'https://thumbnails.libretro.com';
const listingCache = new Map(); // "<system>/<type>" -> { at, names: string[] }

/** Règle de nommage Libretro : ces caractères sont remplacés par "_". */
export function libretroName(name) {
  return name.replace(/[&*/:`<>?\\|"]/g, '_');
}

function normalize(name) {
  return name
    .replace(/\.[a-z0-9]+$/i, '')
    .replace(/\s*[([][^)\]]*[)\]]/g, '')
    .toLowerCase()
    .replace(/^the\s+|,\s*the\b/g, '')
    .replace(/[^a-z0-9]+/g, '');
}

const REGION_PRIORITY = ['france', 'europe', 'world', 'usa', 'japan'];

function regionScore(name) {
  const lower = name.toLowerCase();
  const i = REGION_PRIORITY.findIndex((r) => lower.includes(`(${r}`) || lower.includes(`, ${r}`));
  return i === -1 ? REGION_PRIORITY.length : i;
}

async function listing(system, type) {
  const key = `${system}/${type}`;
  const cached = listingCache.get(key);
  if (cached && Date.now() - cached.at < 6 * 3600_000) return cached.names;
  const res = await fetch(`${BASE}/${encodeURIComponent(system)}/${type}/`);
  if (!res.ok) return [];
  const html = await res.text();
  const names = [...html.matchAll(/href="([^"]+\.png)"/g)].map((m) => decodeURIComponent(m[1]).replace(/\.png$/, ''));
  listingCache.set(key, { at: Date.now(), names });
  return names;
}

async function exists(url) {
  const res = await fetch(url, { method: 'HEAD' });
  return res.ok;
}

async function findImage(system, type, fileName) {
  const exact = libretroName(fileName.replace(/\.[^.]+$/, ''));
  const exactUrl = `${BASE}/${encodeURIComponent(system)}/${type}/${encodeURIComponent(exact)}.png`;
  if (await exists(exactUrl)) return { url: exactUrl, matched: exact };

  // Pas de correspondance exacte : recherche approximative sur le titre sans les tags.
  const wanted = normalize(fileName);
  if (!wanted) return null;
  const candidates = (await listing(system, type)).filter((n) => normalize(n) === wanted);
  if (!candidates.length) return null;
  candidates.sort((a, b) => regionScore(a) - regionScore(b) || a.length - b.length);
  const best = candidates[0];
  return { url: `${BASE}/${encodeURIComponent(system)}/${type}/${encodeURIComponent(best)}.png`, matched: best };
}

/**
 * @returns {Promise<null | { title?: string, media: { boxart?: string, screenshot?: string } }>}
 */
export async function scrapeLibretro({ system, fileName }) {
  if (!system.libretroName) throw new Error('Nom de système Libretro non configuré');
  const box = await findImage(system.libretroName, 'Named_Boxarts', fileName);
  const snap = await findImage(system.libretroName, 'Named_Snaps', fileName);
  // Écran-titre : couverture de repli quand la base n'a pas de jaquette (ex. Amstrad - GX4000).
  const title = box ? null : await findImage(system.libretroName, 'Named_Titles', fileName);
  if (!box && !snap && !title) return null;
  return {
    media: {
      boxart: box?.url ?? title?.url ?? snap?.url,
      screenshot: snap?.url ?? title?.url,
    },
  };
}
