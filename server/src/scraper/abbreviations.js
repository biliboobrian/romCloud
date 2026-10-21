// Abréviations courantes de séries en tête d'un nom de fichier (« GTA Chinatown Wars ») : le titre
// complet est essayé en plus auprès des sources qui cherchent par titre (ScreenScraper, LaunchBox,
// Wikipedia). Seulement des sigles sans ambiguïté, en majuscules dans le nom.
const ABBREVIATIONS = {
  GTA: 'Grand Theft Auto',
  MGS: 'Metal Gear Solid',
  FF: 'Final Fantasy',
  KH: 'Kingdom Hearts',
  LOZ: 'The Legend of Zelda',
  TLOZ: 'The Legend of Zelda',
  SMB: 'Super Mario Bros.',
  SSB: 'Super Smash Bros.',
  NFS: 'Need for Speed',
  COD: 'Call of Duty',
  TMNT: 'Teenage Mutant Ninja Turtles',
  DBZ: 'Dragon Ball Z',
  PES: 'Pro Evolution Soccer',
  KOF: 'The King of Fighters',
  DQ: 'Dragon Quest',
  TES: 'The Elder Scrolls',
};

/** « GTA chinatown wars » -> « Grand Theft Auto chinatown wars » ; null sans abréviation connue. */
export function expandAbbreviation(title) {
  const m = /^([A-Z]{2,4})(?=[\s:-]|$)(.*)$/.exec(String(title || '').trim());
  const full = m && ABBREVIATIONS[m[1]];
  return full ? `${full}${m[2]}` : null;
}
