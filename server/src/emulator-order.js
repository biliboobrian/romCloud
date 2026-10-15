// Ordre des émulateurs d'un système dans chaque application (Windows, Android, Android TV) : le
// premier disponible est choisi par défaut, l'utilisateur peut toujours en choisir un autre.
//
// Identifiants, ceux des applications :
//  • Android / Android TV : uniqueId des émulateurs (« libretrodroid.<cœur> » pour l'émulateur
//    intégré, sinon celui du modèle Daijishou, suffixé « #2 »… s'il est en double) ;
//  • Windows : « builtin:<cœur> » (moteur intégré ou émulateur géré : RPCS3, Cemu, shadPS4),
//    « retroarch:<cœur> », « emu:<id> » (émulateur installé par l'utilisateur).
//
// Pour afficher l'ordre par défaut dans l'administration, ce module reprend les tables des
// applications : CoreRanking.kt / LibretroPlayers.kt (Android), cores.rs, managed.rs et
// catalog.rs (Windows). À mettre à jour avec elles.
import { coreOf, playersFor } from './extra-cores.js';
import { HttpError } from './http-error.js';
import { PLATFORMS } from './systems.js';

export { coreOf };

const NES = ['mesen', 'nestopia', 'fceumm', 'bnes', 'quicknes'];
const GAME_BOY = ['sameboy', 'gambatte', 'mgba', 'mesen-s', 'gearboy', 'tgbdual', 'vbam', 'DoubleCherryGB', 'skyemu'];
const SEGA_8BIT = ['genesis_plus_gx', 'gearsystem', 'smsplus', 'picodrive', 'genesis_plus_gx_wide'];
const GENESIS = ['genesis_plus_gx', 'picodrive', 'genesis_plus_gx_wide'];
const PCE = ['mednafen_pce', 'mednafen_pce_fast', 'mednafen_supergrafx'];

/** Cœurs libretro de chaque console, du plus abouti au moins abouti. */
const RANKING = {
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
const ANDROID_UNAVAILABLE = ['bnes', 'duckstation', 'goosestation', 'azahar', 'mame', 'mupen64plus_next'];
const WINDOWS_UNAVAILABLE = ['bnes', 'duckstation', 'goosestation', 'mamearcade', 'armsx2'];

/** Émulateurs gérés par RomCloud sous Windows (managed.rs). */
const WINDOWS_APPS = [
  { core: 'rpcs3', name: 'RPCS3', systems: ['ps3'] },
  { core: 'shadps4', name: 'shadPS4', systems: ['ps4'] },
  { core: 'cemu', name: 'Cemu', systems: ['wiiu'] },
];

/** Émulateurs Windows installés par l'utilisateur (catalog.rs). */
const WINDOWS_EMULATORS = [
  { id: 'rpcs3', name: 'RPCS3', systems: ['ps3'] },
  { id: 'ppsspp', name: 'PPSSPP', systems: ['pspminis'] },
  { id: 'vita3k', name: 'Vita3K', systems: ['vita'] },
  { id: 'dolphin', name: 'Dolphin', systems: ['wiiware', 'triforce'] },
  { id: 'cemu', name: 'Cemu', systems: ['wiiu'] },
  { id: 'eden', name: 'Eden', systems: ['switch'] },
  { id: 'ryujinx', name: 'Ryujinx', systems: ['switch'] },
  { id: 'snes9x', name: 'Snes9x', systems: ['satellaview'] },
  { id: 'ares', name: 'ares', systems: ['coleco', 'msx'] },
  { id: 'flycast', name: 'Flycast', systems: ['naomi', 'atomiswave'] },
  { id: 'mednafen', name: 'Mednafen', systems: ['pcfx'] },
  { id: 'supermodel', name: 'Supermodel', systems: ['model3'] },
  { id: 'mame', name: 'MAME', systems: ['cps1', 'cps2', 'cps3', 'stv', 'naomi'] },
  { id: 'xemu', name: 'xemu', systems: ['xbox'] },
  { id: 'xenia', name: 'Xenia Canary', systems: ['xbox360'] },
  { id: 'scummvm', name: 'ScummVM', systems: ['scummvm'] },
];

const systemKeys = (system) => [system.shortname, system.id].filter(Boolean).map((k) => String(k).toLowerCase());
const rankKey = (system) => String(system.shortname || system.id || '').toLowerCase();

/** Cœurs triés selon la table du système ; les absents de la table après, puis les indisponibles. */
function rankCores(system, cores, unavailable) {
  const order = RANKING[rankKey(system)] || [];
  const rank = (core) => (unavailable.includes(core) ? order.length + 1 : order.includes(core) ? order.indexOf(core) : order.length);
  return cores.map((core, i) => ({ core, i })).sort((a, b) => rank(a.core) - rank(b.core) || a.i - b.i).map((x) => x.core);
}

/** uniqueId des modèles, suffixés « #2 »… en double (GameSystem.withUniquePlayerIds). */
function uniquePlayers(players) {
  const seen = new Map();
  return players.map((p) => {
    const n = (seen.get(p.uniqueId) || 0) + 1;
    seen.set(p.uniqueId, n);
    return n === 1 ? p : { ...p, uniqueId: `${p.uniqueId}#${n}` };
  });
}

/** Émulateurs de l'application Android (LibretroPlayers.addTo), dans leur ordre par défaut. */
function androidEmulators(system, platform) {
  const players = uniquePlayers(playersFor(system, platform));
  const cores = [...new Set(players.map(coreOf).filter((c) => c && /^[A-Za-z0-9_-]+$/.test(c)))];
  const ranked = rankCores(system, cores, ANDROID_UNAVAILABLE);
  const builtIn = new Set(ranked.filter((c) => !ANDROID_UNAVAILABLE.includes(c)));
  return [
    ...ranked.map((core) => ({ id: `libretrodroid.${core}`, name: `LibretroDroid - ${core}`, kind: 'builtin' })),
    ...players
      .filter((p) => !builtIn.has(coreOf(p)))
      .map((p) => ({ id: p.uniqueId, name: p.name, kind: 'external' })),
  ];
}

/** Émulateurs de l'application Windows (launcher.rs, options), dans leur ordre par défaut. */
function windowsEmulators(system) {
  const keys = systemKeys(system);
  const forSystem = (e) => e.systems.some((s) => keys.includes(s));
  const cores = [];
  for (const player of playersFor(system, 'windows')) {
    let core = coreOf(player);
    if (!core) continue;
    // Cœurs GLES (Android) : équivalent sans suffixe sous Windows ; MAME (arcade) s'y appelle « mame ».
    core = core === 'mamearcade' ? 'mame' : core.replace(/_gles[23]$/, '');
    if (!cores.includes(core)) cores.push(core);
  }
  const ranked = rankCores(system, cores, WINDOWS_UNAVAILABLE);
  const list = [];
  const app = WINDOWS_APPS.find(forSystem);
  if (app) list.push({ id: `builtin:${app.core}`, name: app.name, kind: 'builtin' });
  for (const core of ranked) list.push({ id: `builtin:${core}`, name: `RomCloud - ${core}`, kind: 'builtin' });
  for (const core of ranked.filter((c) => WINDOWS_UNAVAILABLE.includes(c))) {
    list.push({ id: `retroarch:${core}`, name: `RetroArch - ${core}`, kind: 'external' });
  }
  for (const emu of WINDOWS_EMULATORS.filter(forSystem)) list.push({ id: `emu:${emu.id}`, name: emu.name, kind: 'external' });
  return list;
}

/**
 * Émulateurs proposés par chaque application pour ce système (cœurs ajoutés par RomCloud compris),
 * dans l'ordre par défaut de l'application.
 */
export function emulatorCandidates(system) {
  return { android: androidEmulators(system, 'android'), androidtv: androidEmulators(system, 'androidtv'), windows: windowsEmulators(system) };
}

/**
 * Ordre enregistré : { plateforme: [identifiants] }, plateformes connues seulement, sans doublon ;
 * liste vide ou absente : ordre par défaut de l'application.
 */
export function normalizeEmulatorOrder(input) {
  if (input == null || input === '') return {};
  if (typeof input !== 'object' || Array.isArray(input)) throw new HttpError(400, 'errors.invalidEmulatorOrder');
  const out = {};
  for (const [platform, ids] of Object.entries(input)) {
    if (!PLATFORMS.includes(platform)) throw new HttpError(400, 'errors.unknownPlatform', { platform });
    if (ids == null) continue;
    if (!Array.isArray(ids) || ids.length > 200 || ids.some((id) => typeof id !== 'string' || !id || id.length > 200)) {
      throw new HttpError(400, 'errors.invalidEmulatorOrder');
    }
    const unique = [...new Set(ids)];
    if (unique.length) out[platform] = unique;
  }
  return out;
}
