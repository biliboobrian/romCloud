const { test } = require('node:test');
const assert = require('node:assert/strict');
const { tokenize, coreOf, cores } = require('../src/main/emulators');

const retroarch = (core) => ({
  amStartArguments: `-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n -e ROM {file.path}\n -e LIBRETRO ${core}\n -e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg`,
});

test('découpage d’une commande Windows : guillemets et barres obliques inverses conservées', () => {
  const command = String.raw`"C:\Emulateurs\Dolphin 5\Dolphin.exe" -b -e "{file}"`;
  assert.deepEqual(tokenize(command), [String.raw`C:\Emulateurs\Dolphin 5\Dolphin.exe`, '-b', '-e', '{file}']);
  assert.deepEqual(tokenize(String.raw`C:\RetroArch\retroarch.exe -L snes9x`), [String.raw`C:\RetroArch\retroarch.exe`, '-L', 'snes9x']);
  assert.deepEqual(tokenize("a '' b"), ['a', '', 'b']);
});

test('cœur Libretro d’un modèle Android (nom court ou chemin .so)', () => {
  assert.equal(coreOf(retroarch('mupen64plus_next_gles3')), 'mupen64plus_next_gles3');
  assert.equal(coreOf(retroarch('/data/data/com.retroarch/cores/snes9x_libretro_android.so')), 'snes9x');
  assert.equal(coreOf({ amStartArguments: '-n org.ppsspp.ppsspp/.PpssppActivity -d {file.uri}' }), null);
});

test('cœurs Windows d’un système : sans doublon, variantes GLES ramenées au cœur Windows', () => {
  const system = {
    players: [retroarch('mupen64plus_next_gles3'), retroarch('mupen64plus_next_gles2'), retroarch('parallel_n64'), { amStartArguments: '-n x/.Y' }],
  };
  assert.deepEqual(cores(system), ['mupen64plus_next', 'parallel_n64']);
  assert.deepEqual(cores({ players: [] }), []);
});

test('cœurs d’un système : du plus abouti au moins abouti, les inconnus ensuite dans l’ordre des modèles', () => {
  const gba = { id: 'gba', players: ['vba_next', 'vbam', 'mgba', 'inconnu', 'gpsp', 'autre'].map(retroarch) };
  assert.deepEqual(cores(gba), ['mgba', 'vbam', 'vba_next', 'gpsp', 'inconnu', 'autre']);
  const ps2 = { id: 'ps2', players: ['play', 'pcee2', 'armsx2', 'pcsx2'].map(retroarch) };
  assert.deepEqual(cores(ps2), ['pcsx2', 'pcee2', 'play', 'armsx2']); // armsx2 : absent sous Windows
  // MAME (arcade) d'Android : « mame » sous Windows.
  const mame = { id: 'mame', players: ['mame2003_plus', 'mamearcade', 'mame2010'].map(retroarch) };
  assert.deepEqual(cores(mame), ['mame', 'mame2010', 'mame2003_plus']);
  // Cœur absent du buildbot Windows : en dernier.
  const psx = { id: 'psx', players: ['duckstation', 'pcsx_rearmed'].map(retroarch) };
  assert.deepEqual(cores(psx), ['pcsx_rearmed', 'duckstation']);
  // Système sans classement : ordre des modèles.
  assert.deepEqual(cores({ id: 'perso', players: ['b', 'a'].map(retroarch) }), ['b', 'a']);
});
