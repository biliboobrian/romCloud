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
