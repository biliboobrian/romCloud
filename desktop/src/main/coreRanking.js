// Ordre des cœurs libretro de chaque console : du plus abouti, qui fait tourner le plus de jeux,
// au moins abouti. Même table que CoreRanking.kt (application Android). Les cœurs absents de la
// table gardent l'ordre des modèles du serveur, après ceux de la table.

const GAME_BOY = ['sameboy', 'gambatte', 'mgba', 'mesen-s', 'gearboy', 'tgbdual', 'vbam', 'DoubleCherryGB', 'skyemu'];
const SEGA_8BIT = ['genesis_plus_gx', 'gearsystem', 'smsplus', 'picodrive', 'genesis_plus_gx_wide'];
const NES = ['mesen', 'nestopia', 'fceumm', 'bnes', 'quicknes'];

const RANKING = {
  // Nintendo
  nes: NES,
  fds: NES,
  snes: [
    'bsnes', 'snes9x', 'mesen-s', 'bsnes_hd_beta', 'higan_sfc', 'higan_sfc_balanced',
    'bsnes_mercury_accuracy', 'bsnes_mercury_balanced', 'bsnes2014_accuracy', 'bsnes2014_balanced',
    'mednafen_supafaust', 'snes9x2010', 'bsnes_mercury_performance', 'bsnes2014_performance',
    'mednafen_snes', 'bsnes_cplusplus98', 'snes9x2005_plus', 'snes9x2005', 'snes9x2002',
  ],
  gb: GAME_BOY,
  gbc: GAME_BOY,
  gba: ['mgba', 'vbam', 'mednafen_gba', 'vba_next', 'gpsp', 'skyemu', 'noods', 'meteor'],
  n64: ['mupen64plus_next_gles3', 'mupen64plus_next', 'mupen64plus_next_gles2', 'parallel_n64'],
  nds: ['melondsds', 'desmume', 'melonds', 'desmume2015', 'noods', 'skyemu'],
  ndsi: ['melondsds', 'melonds', 'desmume', 'desmume2015'],
  '3ds': ['azahar', 'citra', 'panda3ds'],
  gc: ['dolphin'],
  wii: ['dolphin'],
  virtualboy: ['mednafen_vb'],
  // Sega
  master: SEGA_8BIT,
  gamegear: SEGA_8BIT,
  sg1000: ['genesis_plus_gx', 'gearsystem', 'smsplus', 'picodrive'],
  genesis: ['genesis_plus_gx', 'picodrive', 'genesis_plus_gx_wide'],
  segacd: ['genesis_plus_gx', 'picodrive', 'genesis_plus_gx_wide'],
  sega32x: ['picodrive'],
  saturn: ['mednafen_saturn', 'ymir', 'yabasanshiro', 'yabause', 'kronos'],
  dreamcast: ['flycast'],
  // Sony
  psx: ['mednafen_psx_hw', 'swanstation', 'mednafen_psx', 'pcsx_rearmed', 'duckstation', 'goosestation'],
  // PlayStation 2 : LRPS2 (pcsx2) seul à afficher une image avec LibretroDroid (rendu logiciel).
  ps2: ['pcsx2', 'pcee2', 'armsx2', 'play'],
  psp: ['ppsspp'],
  // NEC, SNK, Atari, Amstrad…
  tg16: ['mednafen_pce', 'mednafen_pce_fast', 'mednafen_supergrafx'],
  tgcd: ['mednafen_pce', 'mednafen_pce_fast', 'mednafen_supergrafx'],
  supergrafx: ['mednafen_supergrafx', 'mednafen_pce'],
  ngp: ['mednafen_ngp', 'race'],
  ngpc: ['mednafen_ngp', 'race'],
  ws: ['mednafen_wswan'],
  wsc: ['mednafen_wswan'],
  atari2600: ['stella', 'stella2014'],
  atari7800: ['prosystem'],
  lynx: ['handy', 'mednafen_lynx'],
  cpc: ['cap32', 'crocods'],
  gx4000: ['cap32'],
  // Arcade : le nombre de jeux dépend surtout de la version du romset (MAME 0.78 -> mame2003_plus).
  neogeo: ['fbneo', 'geolith', 'fbalpha2012'],
  fbneo: ['fbneo', 'fbalpha2012'],
  mame: ['mame', 'mamearcade', 'mame2010', 'mame2003_plus', 'mame2003', 'mame2003_midway', 'mame2000'],
};

/** Cœurs absents du buildbot libretro pour Windows (vérifié en octobre 2026) : proposés en dernier. */
const UNAVAILABLE = ['bnes', 'duckstation', 'goosestation', 'mamearcade', 'armsx2'];

/** Cœurs [cores] du système [systemId] triés selon la table ; tri stable pour les autres. */
function rankCores(systemId, cores) {
  const order = RANKING[String(systemId || '').toLowerCase()] || [];
  const rank = (core) => {
    if (UNAVAILABLE.includes(core)) return order.length + 1;
    const i = order.indexOf(core);
    return i < 0 ? order.length : i;
  };
  return cores.map((core, index) => ({ core, index })).sort((a, b) => rank(a.core) - rank(b.core) || a.index - b.index).map((x) => x.core);
}

module.exports = { RANKING, rankCores };
