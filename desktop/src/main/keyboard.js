// Touches du clavier du joueur 1 dans le moteur intégré (fonctions pures, sans Electron).
// Une touche est notée par son code physique (KeyboardEvent.code : « KeyZ », « ArrowUp »…), donc
// indépendant de la disposition AZERTY / QWERTY, et transmise au moteur en code SDL (scancode USB).

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

/** Argument --keys du moteur : « identifiant libretro=scancode » séparés par des virgules. */
function playerKeys(saved) {
  const keys = resolve(saved);
  return BUTTONS.filter(({ name }) => keys[name]).map(({ name, id }) => `${id}=${SCANCODES[keys[name]]}`).join(',');
}

module.exports = { BUTTONS, DEFAULTS, RESERVED, SCANCODES, assignable, resolve, playerKeys };
