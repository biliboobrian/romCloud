// Informations détaillées d'un jeu (objet `details` stocké en JSON) : autres titres, dates par
// région, régions et langues, série, modes de jeu, classifications d'âge, numéro de série,
// particularités de la ROM, liens… Chaque source en fournit une partie ; le nom de fichier
// (convention No-Intro / Redump) donne déjà régions, langues et révision sans aucun scraping.

const REGIONS = new Set([
  'World', 'Europe', 'USA', 'Japan', 'France', 'Germany', 'Spain', 'Italy', 'UK', 'Australia', 'Asia',
  'Korea', 'Brazil', 'Canada', 'Netherlands', 'Sweden', 'Scandinavia', 'China', 'Taiwan', 'Hong Kong',
  'Russia', 'Portugal', 'Greece', 'Poland', 'Denmark', 'Norway', 'Finland', 'Belgium', 'Austria',
  'Switzerland', 'Latin America', 'India', 'Mexico', 'New Zealand', 'Unknown',
]);

/** Marqueurs No-Intro / Redump repris tels quels (« Rev 1 », « Beta 2 », « Disc 1 »…). */
const FLAG = /^(Rev\s*[\w.]+|v\d[\w.]*|Beta(\s*\d+)?|Proto(\s*\d+)?|Demo|Sample|Kiosk|Unl|Pirate|Hack|Alt(\s*\d+)?|Disc\s*\d+|Disk\s*\d+|Side\s*[AB]|Virtual Console|Collector's Edition|Limited Edition)$/i;

/** Régions, langues et particularités tirées du nom de fichier. */
export function fileNameDetails(fileName) {
  const regions = [];
  const languages = [];
  const romFlags = [];
  const base = String(fileName || '').replace(/\.[a-z0-9]+$/i, '');
  for (const [, tag] of base.matchAll(/\(([^)]+)\)/g)) {
    const parts = tag.split(/\s*,\s*/);
    if (parts.every((p) => REGIONS.has(p))) {
      for (const p of parts) if (!regions.includes(p)) regions.push(p);
    } else if (parts.every((p) => /^[A-Z][a-z](-[A-Z][a-z]+)?$/.test(p))) {
      for (const p of parts) if (!languages.includes(p)) languages.push(p);
    } else if (FLAG.test(tag)) {
      romFlags.push(tag);
    }
  }
  // Traductions de fans (convention GoodTools) : « [T+Fre] ».
  for (const [, lang] of base.matchAll(/\[T[+-]([A-Za-z]+)[^\]]*\]/g)) romFlags.push(`Translation ${lang}`);
  const details = {};
  if (regions.length) details.regions = regions;
  if (languages.length) details.languages = languages;
  if (romFlags.length) details.romFlags = romFlags;
  return details;
}

const isEmpty = (v) => v == null || v === '' || (Array.isArray(v) && !v.length);

/** Supprime les valeurs vides (objet compact à stocker). */
export function compactDetails(details) {
  const out = {};
  for (const [k, v] of Object.entries(details || {})) if (!isEmpty(v)) out[k] = v;
  return out;
}

/**
 * Fusionne des informations : chaque source complète les précédentes sans écraser ce qu'elles
 * ont déjà trouvé, sauf les liens, cumulés.
 */
export function mergeDetails(...sources) {
  const out = {};
  for (const source of sources) {
    for (const [k, v] of Object.entries(source || {})) {
      if (isEmpty(v)) continue;
      if (k === 'links') {
        const links = out.links || [];
        for (const link of v) if (!links.some((l) => l.url === link.url)) links.push(link);
        out.links = links;
      } else if (isEmpty(out[k])) {
        out[k] = v;
      }
    }
  }
  return out;
}

/** Objet stocké en base (JSON) ; {} s'il est absent ou illisible. */
export function parseStoredDetails(text) {
  if (!text) return {};
  try {
    const value = JSON.parse(text);
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  } catch {
    return {};
  }
}
