// Touches du clavier du joueur 1 dans le moteur intégré (fonctions pures, sans Electron).
// Une touche est notée par son code physique (KeyboardEvent.code : « KeyZ », « ArrowUp »…), donc
// indépendant de la disposition AZERTY / QWERTY. Disposition mémorisée dans un fichier partagé avec
// le moteur, qui la modifie aussi depuis son menu : une ligne « bouton=scancode SDL » (USB) par
// bouton, valeur vide = aucune touche, bouton absent = touche par défaut.

/** Boutons de la manette libretro (RetroPad) : nom et identifiant RETRO_DEVICE_ID_JOYPAD_*. */
const BUTTONS = [
  { name: 'up', id: 4 },
  { name: 'down', id: 5 },
  { name: 'left', id: 6 },
  { name: 'right', id: 7 },
  { name: 'a', id: 8 },
  { name: 'b', id: 0 },
  { name: 'x', id: 9 },
  { name: 'y', id: 1 },
  { name: 'l', id: 10 },
  { name: 'r', id: 11 },
  { name: 'l2', id: 12 },
  { name: 'r2', id: 13 },
  { name: 'l3', id: 14 },
  { name: 'r3', id: 15 },
  { name: 'start', id: 3 },
  { name: 'select', id: 2 },
];

/** Disposition par défaut (proche de RetroArch), identique à celle du moteur sans réglage. */
const DEFAULTS = {
  up: 'ArrowUp', down: 'ArrowDown', left: 'ArrowLeft', right: 'ArrowRight',
  a: 'KeyX', b: 'KeyZ', x: 'KeyS', y: 'KeyA',
  l: 'KeyQ', r: 'KeyW', l2: 'KeyE', r2: 'KeyR', l3: '', r3: '',
  start: 'Enter', select: 'ShiftRight',
};

/** Touches du moteur lui-même (menu, états de sauvegarde, plein écran) : non attribuables. */
const RESERVED = ['Escape', 'F1', 'F2', 'F4', 'F11'];

// KeyboardEvent.code -> SDL_Scancode.
const SCANCODES = (() => {
  const map = {};
  for (let i = 0; i < 26; i++) map[`Key${String.fromCharCode(65 + i)}`] = 4 + i;
  for (let i = 1; i <= 9; i++) map[`Digit${i}`] = 29 + i;
  map.Digit0 = 39;
  for (let i = 1; i <= 12; i++) map[`F${i}`] = 57 + i;
  for (let i = 1; i <= 9; i++) map[`Numpad${i}`] = 88 + i;
  Object.assign(map, {
    Enter: 40, Escape: 41, Backspace: 42, Tab: 43, Space: 44, Minus: 45, Equal: 46,
    BracketLeft: 47, BracketRight: 48, Backslash: 49, Semicolon: 51, Quote: 52, Backquote: 53,
    Comma: 54, Period: 55, Slash: 56, CapsLock: 57, PrintScreen: 70, ScrollLock: 71, Pause: 72,
    Insert: 73, Home: 74, PageUp: 75, Delete: 76, End: 77, PageDown: 78,
    ArrowRight: 79, ArrowLeft: 80, ArrowDown: 81, ArrowUp: 82,
    NumLock: 83, NumpadDivide: 84, NumpadMultiply: 85, NumpadSubtract: 86, NumpadAdd: 87,
    NumpadEnter: 88, Numpad0: 98, NumpadDecimal: 99, IntlBackslash: 100, ContextMenu: 101,
    ControlLeft: 224, ShiftLeft: 225, AltLeft: 226, MetaLeft: 227,
    ControlRight: 228, ShiftRight: 229, AltRight: 230, MetaRight: 231,
  });
  return map;
})();

/** Touche utilisable pour un bouton (connue du moteur et non réservée). */
const assignable = (code) => Object.hasOwn(SCANCODES, code) && !RESERVED.includes(code);

/** Disposition complète : réglage de l'utilisateur, touches par défaut pour le reste ; '' = aucune. */
function resolve(saved = {}) {
  const keys = {};
  for (const { name } of BUTTONS) {
    const code = Object.hasOwn(saved, name) ? saved[name] : DEFAULTS[name];
    keys[name] = code === '' || assignable(code) ? code : DEFAULTS[name];
  }
  return keys;
}

/** Contenu du fichier des touches -> { bouton: code } (touches inconnues ignorées). */
function parseFile(text) {
  const codes = Object.fromEntries(Object.entries(SCANCODES).map(([code, sc]) => [sc, code]));
  const saved = {};
  for (const line of String(text || '').split(/\r?\n/)) {
    const match = /^(\w+)=(\d*)$/.exec(line.trim());
    if (!match || !BUTTONS.some((b) => b.name === match[1])) continue;
    if (match[2] === '') saved[match[1]] = '';
    else if (codes[match[2]]) saved[match[1]] = codes[match[2]];
  }
  return resolve(saved);
}

/** Fichier des touches pour le moteur. */
function formatFile(saved) {
  const keys = resolve(saved);
  return BUTTONS.map(({ name }) => `${name}=${keys[name] ? SCANCODES[keys[name]] : ''}\n`).join('');
}

// Boutons de chaque console (comme PadLayouts de l'application Android) : bouton RetroPad -> nom
// sur la console, dans l'ordre d'affichage ; les directions sont toujours proposées. Convention
// des cœurs libretro : B = bouton du bas, A = droite, Y = gauche, X = haut (Super Nintendo).
const tr = (fr, en) => ({ fr, en });
const START_SELECT = { start: 'Start', select: 'Select' };
const PADS = {
  default: { a: 'A', b: 'B', x: 'X', y: 'Y', l: 'L', r: 'R', l2: 'L2', r2: 'R2', l3: 'L3', r3: 'R3', ...START_SELECT },
  nes: { b: 'B', a: 'A', ...START_SELECT },
  fds: { b: 'B', a: 'A', l: tr('Face du disque', 'Disk side'), r: tr('Éjecter / insérer', 'Eject / insert'), ...START_SELECT },
  gameBoy: { b: 'B', a: 'A', ...START_SELECT },
  gba: { b: 'B', a: 'A', l: 'L', r: 'R', ...START_SELECT },
  snes: { b: 'B', a: 'A', y: 'Y', x: 'X', l: 'L', r: 'R', ...START_SELECT },
  n64: { b: 'A', y: 'B', l2: 'Z', l: 'L', r: 'R', start: 'Start' },
  psx: {
    b: tr('Croix', 'Cross'), a: tr('Rond', 'Circle'), y: tr('Carré', 'Square'), x: 'Triangle',
    l: 'L1', r: 'R1', l2: 'L2', r2: 'R2', l3: 'L3', r3: 'R3', ...START_SELECT,
  },
  psp: { b: tr('Croix', 'Cross'), a: tr('Rond', 'Circle'), y: tr('Carré', 'Square'), x: 'Triangle', l: 'L', r: 'R', ...START_SELECT },
  genesis: { y: 'A', b: 'B', a: 'C', l: 'X', x: 'Y', r: 'Z', start: 'Start' },
  saturn: { b: 'A', a: 'B', r: 'C', y: 'X', x: 'Y', l: 'Z', l2: 'L', r2: 'R', start: 'Start' },
  master: { b: '1', a: '2', start: 'Start' },
  dreamcast: { b: 'A', a: 'B', y: 'X', x: 'Y', l2: 'L', r2: 'R', start: 'Start' },
  pcEngine: { b: 'II', a: 'I', select: 'Select', start: 'Run' },
  neoGeo: { b: 'A', a: 'B', y: 'C', x: 'D', select: tr('Pièce', 'Coin'), start: 'Start' },
  ngp: { b: 'A', a: 'B', start: 'Option' },
  cps: { y: 'LP', x: 'MP', l: 'HP', b: 'LK', a: 'MK', r: 'HK', select: tr('Pièce', 'Coin'), start: 'Start' },
  arcade: { b: '1', a: '2', y: '3', x: '4', l: '5', r: '6', select: tr('Pièce', 'Coin'), start: 'Start' },
  gx4000: { b: tr('Feu 1', 'Fire 1'), a: tr('Feu 2', 'Fire 2') },
  atari2600: { b: tr('Feu', 'Fire'), select: 'Select', start: 'Reset' },
  atari7800: { b: '1', a: '2', select: 'Select', start: 'Pause' },
  lynx: { b: 'B', a: 'A', l: 'Option 1', r: 'Option 2', start: 'Pause' },
};

const PAD_BY_SYSTEM = {
  nes: 'nes', fds: 'fds', gba: 'gba', n64: 'n64', psx: 'psx', gx4000: 'gx4000',
  atari2600: 'atari2600', atari7800: 'atari7800', lynx: 'lynx',
  ...Object.fromEntries(['gb', 'gbc', 'megaduck', 'gw', 'pokemini', 'supervision'].map((s) => [s, 'gameBoy'])),
  ...Object.fromEntries(['snes', 'snesmsu1', 'satellaview'].map((s) => [s, 'snes'])),
  ...Object.fromEntries(['psp', 'pspminis'].map((s) => [s, 'psp'])),
  ...Object.fromEntries(['genesis', 'genesismsu', 'segacd', 'sega32x', 'pico'].map((s) => [s, 'genesis'])),
  ...Object.fromEntries(['saturn', 'stv'].map((s) => [s, 'saturn'])),
  ...Object.fromEntries(['master', 'gamegear', 'sg1000'].map((s) => [s, 'master'])),
  ...Object.fromEntries(['dreamcast', 'naomi', 'atomiswave'].map((s) => [s, 'dreamcast'])),
  ...Object.fromEntries(['tg16', 'tgcd', 'supergrafx'].map((s) => [s, 'pcEngine'])),
  ...Object.fromEntries(['neogeo', 'neogeocd'].map((s) => [s, 'neoGeo'])),
  ...Object.fromEntries(['ngp', 'ngpc'].map((s) => [s, 'ngp'])),
  ...Object.fromEntries(['cps1', 'cps2', 'cps3'].map((s) => [s, 'cps'])),
  ...Object.fromEntries(['fbneo', 'mame'].map((s) => [s, 'arcade'])),
};

/** Système inconnu (ajouté à la main sur le serveur) : d'après le cœur ; préfixes longs d'abord. */
const PAD_BY_CORE = [
  ['mesen-s', 'snes'],
  ['fceumm', 'nes'], ['nestopia', 'nes'], ['mesen', 'nes'], ['quicknes', 'nes'],
  ['gambatte', 'gameBoy'], ['sameboy', 'gameBoy'], ['gearboy', 'gameBoy'], ['tgbdual', 'gameBoy'],
  ['mgba', 'gba'], ['gpsp', 'gba'], ['vba', 'gba'], ['mednafen_gba', 'gba'],
  ['snes9x', 'snes'], ['bsnes', 'snes'],
  ['mupen64plus', 'n64'], ['parallel_n64', 'n64'],
  ['pcsx_rearmed', 'psx'], ['swanstation', 'psx'], ['duckstation', 'psx'], ['mednafen_psx', 'psx'],
  ['ppsspp', 'psp'],
  ['genesis_plus_gx', 'genesis'], ['picodrive', 'genesis'],
  ['mednafen_saturn', 'saturn'], ['yabause', 'saturn'], ['yabasanshiro', 'saturn'], ['kronos', 'saturn'],
  ['gearsystem', 'master'], ['smsplus', 'master'],
  ['flycast', 'dreamcast'],
  ['mednafen_pce', 'pcEngine'], ['mednafen_supergrafx', 'pcEngine'],
  ['neocd', 'neoGeo'], ['geolith', 'neoGeo'],
  ['mednafen_ngp', 'ngp'], ['race', 'ngp'],
  ['fbalpha2012_cps', 'cps'],
  ['fbneo', 'arcade'], ['fbalpha', 'arcade'], ['mame', 'arcade'],
  ['stella', 'atari2600'],
  ['prosystem', 'atari7800'],
  ['handy', 'lynx'], ['mednafen_lynx', 'lynx'],
];

/**
 * Boutons de la console proposés dans l'écran des touches du moteur : argument --buttons,
 * « bouton[=nom sur la console] » séparés par des virgules (directions sans nom : traduites par
 * le moteur). Système, puis cœur, sinon tous les boutons du RetroPad.
 */
function consoleButtons(systemId, core, language = 'fr') {
  const system = String(systemId || '').toLowerCase();
  const pad = PADS[PAD_BY_SYSTEM[system] || PAD_BY_CORE.find(([prefix]) => String(core || '').startsWith(prefix))?.[1] || 'default'];
  const label = (l) => (typeof l === 'string' ? l : l[language] || l.en);
  const clean = (s) => s.replace(/[,=]/g, ' ');
  return ['up', 'down', 'left', 'right', ...Object.entries(pad).map(([name, l]) => `${name}=${clean(label(l))}`)].join(',');
}

module.exports = { BUTTONS, DEFAULTS, RESERVED, SCANCODES, assignable, resolve, parseFile, formatFile, consoleButtons };
