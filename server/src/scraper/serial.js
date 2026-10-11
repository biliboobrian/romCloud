// Numéro de série d'un jeu PlayStation lu dans son nom de fichier, pour l'identifier dans les bases
// (DAT libretro, ScreenScraper) quand le nom n'est pas un titre :
//  - identifiant de contenu PS Vita / PSP / PS3 : « EP0001-PCSB00040_00-ASPHALTINJECTION » -> PCSB-00040 ;
//  - numéro seul : « PCSB00040 », « ULES-00151 », « [BLES01807] », PS2 « SLUS_200.62 ».

// Préfixes des numéros Sony : Vita (PCS…, VCS…, VLAS), PSP (UCES, ULUS…), PSN (NPEB…), PS3 (BLES…),
// PS1 / PS2 (SLUS, SCES, SLPM…).
const PREFIX = '(?:PCS[A-HJ]|VCS[A-Z]|VLAS|U[CL][AEJKU]S|NP[A-Z]{2}|B[CL][AEJKU]S|S[CL][AEKPU][MSD])';
const CONTENT_ID = new RegExp(`^[A-Z]{2}\\d{4}-(${PREFIX})(\\d{5})[_ ]\\d{2}-`, 'i');
const SERIAL = new RegExp(`(?:^|[^A-Z0-9])(${PREFIX})[-_ ]?(\\d{3})[._]?(\\d{2})(?![0-9])`, 'i');

/** Numéro de série au format des DAT (« PCSB-00040 ») ; null si le nom n'en contient pas. */
export function serialFromFileName(fileName) {
  const name = String(fileName || '').replace(/\.[a-z0-9]+$/i, '');
  const content = name.match(CONTENT_ID);
  if (content) return `${content[1].toUpperCase()}-${content[2]}`;
  const serial = name.match(SERIAL);
  return serial ? `${serial[1].toUpperCase()}-${serial[2]}${serial[3]}` : null;
}

/** Clé de comparaison des numéros de série : « PCSB-00040 », « PCSB00040 », « pcsb_000.40 » -> « PCSB00040 ». */
export const serialKey = (serial) => String(serial || '').toUpperCase().replace(/[^A-Z0-9]/g, '');

/** Libellé d'un identifiant de contenu (« ASPHALTINJECTION »), titre provisoire avant le scraping. */
export function contentIdLabel(fileName) {
  const name = String(fileName || '').replace(/\.[a-z0-9]+$/i, '');
  if (!CONTENT_ID.test(name)) return null;
  const label = name.replace(/^[^-]+-[^-]+-/, '').replace(/_/g, ' ').trim();
  // Libellé complété par des zéros jusqu'à 16 caractères (« RAYMANORIGINS000 », « ACCVITA000000001 ») :
  // remplissage retiré.
  return (label.length === 16 ? label.replace(/(?<=[A-Z])0{2,}[1-9]?$/i, '') : label) || null;
}

/**
 * Sigle d'un libellé d'identifiant de contenu (« ACCVITA » : « ACC », Assassin's Creed Chronicles) :
 * lettres seules, suffixe de la console (VITA, PSV, PSP) retiré ; null s'il reste moins de 3 lettres.
 */
export function acronymOf(label) {
  const letters = String(label || '').toUpperCase();
  if (!/^[A-Z]+$/.test(letters)) return null;
  const acronym = letters.replace(/(?:PSVITA|VITA|PSV|PSP)$/, '');
  return acronym.length >= 3 && acronym !== letters ? acronym : null;
}

/** Initiales des mots d'un titre (« Assassin's Creed Chronicles » : « ACC »). */
export const initials = (title) =>
  String(title || '').replace(/['’]/g, '').split(/[^A-Za-z0-9]+/).filter(Boolean).map((w) => w[0].toUpperCase()).join('');

// Région de la boutique d'un identifiant de contenu (« EP0001-… » : Europe).
const CONTENT_REGIONS = { EP: 'Europe', UP: 'USA', JP: 'Japan', HP: 'Asia', KP: 'Korea' };

/** Région de l'identifiant de contenu (« EP0001-PCSB00061_00-… » -> « Europe ») ; null sinon. */
export function contentIdRegion(fileName) {
  const name = String(fileName || '').replace(/\.[a-z0-9]+$/i, '');
  return CONTENT_ID.test(name) ? CONTENT_REGIONS[name.slice(0, 2).toUpperCase()] ?? null : null;
}

/**
 * Le libellé d'un identifiant de contenu, limité à 16 caractères et souvent abrégé
 * (« LUMINESELECTROSY »), désigne-t-il le titre [compactTitle] (minuscules et chiffres seuls,
 * « luminesselectronicsymphony ») ? Mêmes premières lettres, puis les suivantes dans l'ordre.
 */
export function abbreviates(label, compactTitle) {
  const short = String(label || '').toLowerCase().replace(/[^a-z0-9]/g, '');
  if (short.length < 4 || !compactTitle) return false;
  const head = Math.min(6, short.length);
  if (!compactTitle.startsWith(short.slice(0, head))) return false;
  let i = head;
  for (const c of compactTitle.slice(head)) if (c === short[i]) i++;
  return i === short.length;
}

/**
 * Libellé d'identifiant de contenu nettoyé pour un rapprochement approché (minuscules et chiffres) :
 * nom de la console retiré (« SPONGEHEROVITA15 » : « spongehero », « FIFAVITA20130001 » : « fifa13 »),
 * remplissage final, année réduite à ses deux chiffres, zéros en tête des nombres (« REBIRTH02 »).
 */
export function looseLabel(label) {
  let l = String(label || '').toUpperCase();
  const withoutConsole = l.replace(/(?:PSVITA|VITA)\d{0,2}$/, '').replace(/(?:PSVITA|VITA)/g, '');
  if (withoutConsole.length >= 4) l = withoutConsole;
  return l
    .replace(/(?<=[A-Z0-9])0{2,}[1-9]?$/, '')
    .replace(/20([0-2]\d)(?!\d)/g, '$1')
    .replace(/(?<![0-9])0+(?=[1-9])/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]/g, '');
}

/** Longueur de la plus longue sous-suite commune de [a] et [b]. */
function commonSubsequence(a, b) {
  let prev = new Array(b.length + 1).fill(0);
  for (const ca of a) {
    const row = [0];
    for (let j = 0; j < b.length; j++) row.push(ca === b[j] ? prev[j] + 1 : Math.max(prev[j + 1], row[j]));
    prev = row;
  }
  return prev[b.length];
}

/**
 * Proximité entre un libellé d'identifiant de contenu et un titre compact (voir [abbreviates]) quand
 * le libellé n'en est pas une simple abréviation (« LEGOFORCEAWAKENS » : « LEGO Star Wars - The Force
 * Awakens », « NEPTUNEREBIRTH02 » : « Hyperdimension Neptunia Re;Birth2 ») : lettres du libellé
 * retrouvées dans l'ordre, ses 4 premières à la suite dans le titre ; 2 lettres de trop admises à
 * partir de 10. 0 si le titre ne convient pas.
 */
export function labelScore(label, compactTitle) {
  const l = looseLabel(label);
  if (l.length < 5 || !compactTitle || !compactTitle.includes(l.slice(0, 4))) return 0;
  const score = commonSubsequence(l, compactTitle);
  return score >= l.length - (l.length >= 10 ? 2 : 0) ? score : 0;
}
