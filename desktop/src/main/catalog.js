// Catalogue des émulateurs Windows les plus connus : page de téléchargement, exécutable,
// emplacements habituels et ligne de commande pour lancer un jeu directement.
//
// Arguments (« args ») : un élément par argument, avec les variables {file} (chemin du jeu),
// {dir} (dossier du jeu), {name} (nom du fichier) et {basename} (nom sans extension).
// args = null : l'émulateur ne sait pas ouvrir un jeu passé en ligne de commande ; RomCloud
// l'ouvre seul et l'utilisateur choisit le jeu dans son menu.
// « systems » : identifiants courts des plateformes Daijishou (champ shortname des systèmes).
const fs = require('node:fs');
const path = require('node:path');

const EMULATORS = [
  {
    id: 'duckstation', name: 'DuckStation', systems: ['psx'],
    url: 'https://github.com/stenzek/duckstation/releases',
    exe: ['duckstation-qt-x64-ReleaseLTCG.exe', 'duckstation-qt.exe'],
    dirs: ['DuckStation'],
    args: ['-batch', '-fullscreen', '{file}'],
  },
  {
    id: 'pcsx2', name: 'PCSX2', systems: ['ps2'],
    url: 'https://pcsx2.net/downloads',
    exe: ['pcsx2-qt.exe', 'pcsx2.exe'],
    dirs: ['PCSX2'],
    args: ['-batch', '-fullscreen', '--', '{file}'],
  },
  {
    id: 'rpcs3', name: 'RPCS3', systems: ['ps3'],
    url: 'https://rpcs3.net/download',
    exe: ['rpcs3.exe'],
    dirs: ['RPCS3'],
    args: ['--no-gui', '{file}'],
  },
  {
    id: 'ppsspp', name: 'PPSSPP', systems: ['psp', 'pspminis'],
    url: 'https://www.ppsspp.org/download/',
    exe: ['PPSSPPWindows64.exe', 'PPSSPPWindows.exe'],
    dirs: ['PPSSPP'],
    args: ['--fullscreen', '{file}'],
  },
  {
    id: 'vita3k', name: 'Vita3K', systems: ['vita'],
    url: 'https://vita3k.org/',
    exe: ['Vita3K.exe'],
    dirs: ['Vita3K'],
    // Les jeux doivent d'abord être installés dans Vita3K (fichiers .vpk / .pkg) : pas de lancement direct.
    args: null,
  },
  {
    id: 'dolphin', name: 'Dolphin', systems: ['gc', 'wii', 'wiiware', 'triforce'],
    url: 'https://dolphin-emu.org/download/',
    exe: ['Dolphin.exe'],
    dirs: ['Dolphin', 'Dolphin-x64', 'Dolphin Emulator'],
    args: ['-b', '-e', '{file}'],
  },
  {
    id: 'cemu', name: 'Cemu', systems: ['wiiu'],
    url: 'https://cemu.info/',
    exe: ['Cemu.exe'],
    dirs: ['Cemu'],
    args: ['-f', '-g', '{file}'],
  },
  {
    id: 'project64', name: 'Project64', systems: ['n64'],
    url: 'https://www.pj64-emu.com/',
    exe: ['Project64.exe'],
    dirs: ['Project64 3.0', 'Project64'],
    args: ['{file}'],
  },
  {
    id: 'melonds', name: 'melonDS', systems: ['nds', 'ndsi'],
    url: 'https://melonds.kuribo64.net/downloads.php',
    exe: ['melonDS.exe'],
    dirs: ['melonDS'],
    args: ['{file}'],
  },
  {
    id: 'azahar', name: 'Azahar', systems: ['3ds'],
    url: 'https://azahar-emu.org/',
    exe: ['azahar.exe'],
    dirs: ['Azahar'],
    args: ['{file}'],
  },
  {
    id: 'mgba', name: 'mGBA', systems: ['gba', 'gb', 'gbc'],
    url: 'https://mgba.io/downloads.html',
    exe: ['mGBA.exe'],
    dirs: ['mGBA'],
    args: ['-f', '{file}'],
  },
  {
    id: 'snes9x', name: 'Snes9x', systems: ['snes', 'satellaview'],
    url: 'https://www.snes9x.com/',
    exe: ['snes9x-x64.exe', 'snes9x.exe'],
    dirs: ['Snes9x', 'snes9x'],
    args: ['{file}'],
  },
  {
    id: 'mesen', name: 'Mesen', systems: ['nes', 'fds', 'snes', 'gb', 'gbc', 'gba', 'tg16', 'tgcd', 'supergrafx', 'master', 'gamegear', 'ws', 'wsc'],
    url: 'https://www.mesen.ca/',
    exe: ['Mesen.exe'],
    dirs: ['Mesen', 'Mesen2'],
    args: ['{file}'],
  },
  {
    id: 'ares', name: 'ares', systems: ['n64', 'nes', 'snes', 'gb', 'gbc', 'gba', 'genesis', 'segacd', 'sega32x', 'master', 'gamegear', 'sg1000', 'tg16', 'tgcd', 'supergrafx', 'ngp', 'ngpc', 'ws', 'wsc', 'coleco', 'msx'],
    url: 'https://ares-emu.net/download',
    exe: ['ares.exe'],
    dirs: ['ares'],
    args: ['--fullscreen', '{file}'],
  },
  {
    id: 'flycast', name: 'Flycast', systems: ['dreamcast', 'naomi', 'atomiswave'],
    url: 'https://github.com/flyinghead/flycast/releases',
    exe: ['flycast.exe'],
    dirs: ['Flycast', 'flycast'],
    args: ['{file}'],
  },
  {
    id: 'redream', name: 'Redream', systems: ['dreamcast'],
    url: 'https://redream.io/download',
    exe: ['redream.exe'],
    dirs: ['redream', 'Redream'],
    args: ['{file}'],
  },
  {
    id: 'mednafen', name: 'Mednafen', systems: ['saturn', 'psx', 'tg16', 'tgcd', 'supergrafx', 'pcfx', 'lynx', 'virtualboy', 'ngp', 'ngpc', 'ws', 'wsc'],
    url: 'https://mednafen.github.io/releases/',
    exe: ['mednafen.exe'],
    dirs: ['mednafen', 'Mednafen'],
    args: ['{file}'],
  },
  {
    id: 'supermodel', name: 'Supermodel', systems: ['model3'],
    url: 'https://www.supermodel3.com/Download.html',
    exe: ['Supermodel.exe'],
    dirs: ['Supermodel'],
    args: ['{file}'],
  },
  {
    id: 'mame', name: 'MAME', systems: ['mame', 'fbneo', 'cps1', 'cps2', 'cps3', 'neogeo', 'stv', 'naomi'],
    url: 'https://www.mamedev.org/release.html',
    exe: ['mame.exe', 'mame64.exe'],
    dirs: ['MAME', 'mame'],
    // MAME attend le nom court du jeu et le dossier qui contient le zip.
    args: ['-rompath', '{dir}', '{basename}'],
  },
  {
    id: 'xemu', name: 'xemu', systems: ['xbox'],
    url: 'https://xemu.app/',
    exe: ['xemu.exe'],
    dirs: ['xemu'],
    args: ['-full-screen', '-dvd_path', '{file}'],
  },
  {
    id: 'xenia', name: 'Xenia Canary', systems: ['xbox360'],
    url: 'https://github.com/xenia-canary/xenia-canary-releases/releases',
    exe: ['xenia_canary.exe', 'xenia.exe'],
    dirs: ['Xenia', 'xenia', 'xenia_canary'],
    args: ['{file}'],
  },
  {
    id: 'stella', name: 'Stella', systems: ['atari2600'],
    url: 'https://stella-emu.github.io/downloads.html',
    exe: ['Stella.exe'],
    dirs: ['Stella'],
    args: ['{file}'],
  },
  {
    id: 'scummvm', name: 'ScummVM', systems: ['scummvm'],
    url: 'https://www.scummvm.org/downloads/',
    exe: ['scummvm.exe'],
    dirs: ['ScummVM'],
    // Détecte le jeu contenu dans le dossier du fichier.
    args: ['-p', '{dir}', '--auto-detect'],
  },
];

const byId = (id) => EMULATORS.find((e) => e.id === id) || null;

/** Émulateurs du catalogue adaptés à un système (identifiant ou nom court). */
function forSystem(system) {
  const keys = [system.shortname, system.id].filter(Boolean).map((k) => String(k).toLowerCase());
  return EMULATORS.filter((e) => e.systems.some((s) => keys.includes(s)));
}

/** Remplace les variables d'une liste d'arguments pour le fichier de jeu donné. */
function buildArgs(args, file) {
  const values = { file, dir: path.dirname(file), name: path.basename(file), basename: path.parse(file).name };
  return args.map((a) => a.replace(/\{(file|dir|name|basename)\}/g, (_m, k) => values[k]));
}

const quote = (s) => (/[\s"]/.test(s) || s === '' ? `"${s}"` : s);

/** Ligne de commande affichée à l'utilisateur (arguments contenant des espaces entre guillemets). */
function commandLine(exe, args) {
  return [exe, ...(args || [])].map(quote).join(' ');
}

/** Arguments seuls, sous forme de texte modifiable (« -b -e {file} »). */
const argsLine = (args) => (args || []).map(quote).join(' ');

// Noms des plateformes Daijishou citées dans le catalogue (affichage des systèmes d'un émulateur).
const SYSTEM_NAMES = {
  psx: 'PlayStation', ps2: 'PlayStation 2', ps3: 'PlayStation 3', psp: 'PSP', pspminis: 'PSP Minis', vita: 'PS Vita',
  gc: 'GameCube', wii: 'Wii', wiiware: 'WiiWare', triforce: 'Triforce', wiiu: 'Wii U', n64: 'Nintendo 64',
  nds: 'Nintendo DS', ndsi: 'Nintendo DSi', '3ds': 'Nintendo 3DS', gba: 'Game Boy Advance', gb: 'Game Boy',
  gbc: 'Game Boy Color', snes: 'Super Nintendo', satellaview: 'Satellaview', nes: 'NES', fds: 'Famicom Disk System',
  tg16: 'PC Engine', tgcd: 'PC Engine CD', supergrafx: 'SuperGrafx', pcfx: 'PC-FX', master: 'Master System',
  gamegear: 'Game Gear', sg1000: 'SG-1000', genesis: 'Mega Drive', segacd: 'Mega-CD', sega32x: '32X',
  saturn: 'Saturn', dreamcast: 'Dreamcast', naomi: 'Naomi', atomiswave: 'Atomiswave', model3: 'Model 3', stv: 'ST-V',
  ws: 'WonderSwan', wsc: 'WonderSwan Color', ngp: 'Neo Geo Pocket', ngpc: 'Neo Geo Pocket Color', neogeo: 'Neo Geo',
  lynx: 'Lynx', virtualboy: 'Virtual Boy', coleco: 'ColecoVision', msx: 'MSX', atari2600: 'Atari 2600',
  mame: 'Arcade (MAME)', fbneo: 'FinalBurn Neo', cps1: 'CPS-1', cps2: 'CPS-2', cps3: 'CPS-3',
  xbox: 'Xbox', xbox360: 'Xbox 360', scummvm: 'ScummVM',
};
const systemName = (id) => SYSTEM_NAMES[id] || id;

/**
 * Dossiers où chercher les émulateurs, avec la profondeur de recherche : installations
 * classiques, dossiers de l'utilisateur et dossiers d'émulation courants.
 */
function searchRoots(env = process.env) {
  const home = env.USERPROFILE || '';
  const roots = [
    [env.ProgramFiles || 'C:\\Program Files', 2],
    [env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)', 2],
    [env.LOCALAPPDATA && path.join(env.LOCALAPPDATA, 'Programs'), 2],
    [home && path.join(home, 'Desktop'), 2],
    [home && path.join(home, 'Downloads'), 2],
    [home && path.join(home, 'Documents'), 1],
    [home && path.join(home, 'scoop', 'apps'), 2],
    [home, 1],
  ];
  for (const drive of ['C', 'D', 'E', 'F']) {
    roots.push([`${drive}:\\`, 1]);
    for (const name of ['Emulators', 'Emulateurs', 'Emulation', 'Emu', 'Games', 'Jeux']) roots.push([`${drive}:\\${name}`, 3]);
  }
  const seen = new Set();
  return roots.filter(([dir]) => dir && !seen.has(dir.toLowerCase()) && seen.add(dir.toLowerCase()));
}

/** Emplacement par défaut affiché pour un émulateur (dossier d'installation habituel). */
function defaultLocation(emu, env = process.env) {
  const programFiles = env.ProgramFiles || 'C:\\Program Files';
  const dir = emu.id === 'project64' ? path.join(env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)', 'Project64 3.0') : path.join(programFiles, emu.dirs[0]);
  return path.join(dir, emu.exe[0]);
}

// Dossiers système ou volumineux jamais parcourus.
const SKIP_DIRS = new Set(['windows', 'appdata', 'node_modules', '$recycle.bin', 'system volume information', 'programdata', 'common files', 'windowsapps', '.git']);

/**
 * Cherche les exécutables des émulateurs du catalogue (un seul listage par dossier) dans les
 * dossiers racine et leurs sous-dossiers (ex. Downloads\dolphin-2506\Dolphin-x64\Dolphin.exe).
 * Renvoie { [id]: chemin trouvé }.
 */
function detect(env = process.env, emulators = EMULATORS) {
  const found = {};
  // Nom d'exécutable en minuscules -> émulateurs qui l'utilisent.
  const byExe = new Map();
  for (const emu of emulators) {
    for (const exe of emu.exe) byExe.set(exe.toLowerCase(), [...(byExe.get(exe.toLowerCase()) || []), emu]);
  }
  // D'abord les emplacements d'installation habituels (rapide).
  for (const emu of emulators) {
    const file = defaultLocation(emu, env);
    if (fs.existsSync(file)) found[emu.id] = file;
  }
  // Dossier -> profondeur déjà explorée (un dossier revu avec une profondeur supérieure est re-parcouru).
  const visited = new Map();
  const walk = (dir, depth) => {
    const key = dir.toLowerCase();
    if (visited.get(key) >= depth || Object.keys(found).length === emulators.length) return;
    visited.set(key, depth);
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      if (!e.isFile()) continue;
      for (const emu of byExe.get(e.name.toLowerCase()) || []) found[emu.id] ||= path.join(dir, e.name);
    }
    if (depth <= 0) return;
    for (const e of entries) {
      if (e.isDirectory() && !SKIP_DIRS.has(e.name.toLowerCase()) && !e.name.startsWith('.')) walk(path.join(dir, e.name), depth - 1);
    }
  };
  for (const [root, depth] of searchRoots(env)) walk(root, depth);
  return found;
}

module.exports = { EMULATORS, byId, forSystem, buildArgs, commandLine, argsLine, systemName, searchRoots, defaultLocation, detect };
