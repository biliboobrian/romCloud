// Cœurs libretro de chaque console, du plus abouti au moins abouti : mêmes tables que les applications
// (CoreRanking.kt sous Android, cores.rs sous Windows), à mettre à jour avec elles. Une console de la
// table est émulée par le moteur intégré (LibretroDroid sous Android, romcloud-player sous Windows) :
// les émulateurs externes de ses modèles Daijishou n'y sont pas proposés.

const NES = ['mesen', 'nestopia', 'fceumm', 'bnes', 'quicknes'];
const GAME_BOY = ['sameboy', 'gambatte', 'mgba', 'mesen-s', 'gearboy', 'tgbdual', 'vbam', 'DoubleCherryGB', 'skyemu'];
const SEGA_8BIT = ['genesis_plus_gx', 'gearsystem', 'smsplus', 'picodrive', 'genesis_plus_gx_wide'];
const GENESIS = ['genesis_plus_gx', 'picodrive', 'genesis_plus_gx_wide'];
const PCE = ['mednafen_pce', 'mednafen_pce_fast', 'mednafen_supergrafx'];

/** Cœurs libretro de chaque console, du plus abouti au moins abouti. */
export const RANKING = {
  nes: NES,
  fds: NES,
  snes: [
    'bsnes', 'snes9x', 'mesen-s', 'bsnes_hd_beta', 'higan_sfc', 'higan_sfc_balanced', 'bsnes_mercury_accuracy',
    'bsnes_mercury_balanced', 'bsnes2014_accuracy', 'bsnes2014_balanced', 'mednafen_supafaust', 'snes9x2010',
    'bsnes_mercury_performance', 'bsnes2014_performance', 'mednafen_snes', 'bsnes_cplusplus98', 'snes9x2005_plus',
    'snes9x2005', 'snes9x2002',
  ],
  gb: GAME_BOY,
  gbc: GAME_BOY,
  gba: ['mgba', 'vbam', 'mednafen_gba', 'vba_next', 'gpsp', 'skyemu', 'noods', 'meteor'],
  n64: ['mupen64plus_next_gles3', 'mupen64plus_next', 'mupen64plus_next_gles2', 'parallel_n64'],
  nds: ['melondsds', 'desmume', 'melonds', 'desmume2015', 'noods', 'skyemu'],
  ndsi: ['melondsds', 'melonds', 'desmume', 'desmume2015'],
  '3ds': ['citra', 'panda3ds', 'azahar'],
  gc: ['dolphin'],
  wii: ['dolphin'],
  virtualboy: ['mednafen_vb'],
  master: SEGA_8BIT,
  gamegear: SEGA_8BIT,
  sg1000: ['genesis_plus_gx', 'gearsystem', 'smsplus', 'picodrive'],
  genesis: GENESIS,
  segacd: GENESIS,
  sega32x: ['picodrive'],
  saturn: ['mednafen_saturn', 'ymir', 'yabasanshiro', 'yabause', 'kronos'],
  dreamcast: ['flycast'],
  psx: ['mednafen_psx_hw', 'swanstation', 'mednafen_psx', 'pcsx_rearmed', 'duckstation', 'goosestation'],
  ps2: ['pcsx2', 'pcee2', 'play', 'armsx2'],
  psp: ['ppsspp'],
  pspminis: ['ppsspp'],
  tg16: PCE,
  tgcd: PCE,
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
  neogeo: ['fbneo', 'geolith', 'fbalpha2012'],
  fbneo: ['fbneo', 'fbalpha2012'],
  mame: ['mame', 'mamearcade', 'mame2010', 'mame2003_plus', 'mame2003', 'mame2003_midway', 'mame2000'],
};

/** Cœurs absents du buildbot libretro de chaque plateforme : proposés en dernier. */
export const ANDROID_UNAVAILABLE = ['bnes', 'duckstation', 'goosestation', 'azahar', 'mame', 'mupen64plus_next'];
export const WINDOWS_UNAVAILABLE = ['bnes', 'duckstation', 'goosestation', 'mamearcade', 'armsx2'];

const ANDROID_PLATFORMS = ['android', 'androidtv'];

/**
 * Cœurs pas encore essayés dans le moteur intégré de Windows (xemu) : proposés après l'émulateur
 * externe du système (cores.rs, AFTER_EXTERNAL), choisis par défaut seulement s'il n'est pas installé.
 */
export const AFTER_EXTERNAL = ['xemu'];

/** Clé de la console dans la table (nom court, sinon identifiant). */
export const rankKey = (system) => String(system?.shortname || system?.id || '').toLowerCase();

/**
 * Console émulée par le moteur intégré d'Android : dans la table, avec au moins un des cœurs [cores]
 * (ceux de ses modèles) téléchargeable sous Android.
 */
export function coveredOnAndroid(system, cores) {
  return Boolean(RANKING[rankKey(system)]) && cores.some((c) => c && !ANDROID_UNAVAILABLE.includes(c));
}

/**
 * Émulateurs envoyés aux applications Android et Android TV : pour une console émulée par le moteur
 * intégré, les modèles d'émulateurs externes ([coreOf] null) sont retirés ; autres plateformes, inchangés.
 */
export function withoutCoveredExternals(system, players, platform, coreOf) {
  if (!ANDROID_PLATFORMS.includes(platform)) return players;
  return coveredOnAndroid(system, players.map(coreOf)) ? players.filter((p) => coreOf(p)) : players;
}
