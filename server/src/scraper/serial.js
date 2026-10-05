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
  return label || null;
}
