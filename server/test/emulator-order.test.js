import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-emu-order-test-${process.pid}-${Date.now()}`);

const { createSystem, getSystem, updateSystem } = await import('../src/systems.js');
const { coreOf, emulatorCandidates, normalizeEmulatorOrder } = await import('../src/emulator-order.js');

const coreTemplate = (core, uniqueId = `core.${core}`) => ({
  name: `psx - ${core}`,
  uniqueId,
  amStartArguments: `-n com.example.frontend/.GameActivity -e ROM {file.path} -e LIBRETRO /data/data/com.example.frontend/cores/${core}_libretro_android.so`,
});
const external = (uniqueId, name) => ({ name, uniqueId, amStartArguments: '-n org.example/.Main -d {file.uri}' });

test('cœur libretro d’un modèle', () => {
  assert.equal(coreOf(coreTemplate('snes9x')), 'snes9x');
  assert.equal(coreOf({ amStartArguments: '-e LIBRETRO "mupen64plus_next_gles3"' }), 'mupen64plus_next_gles3');
  assert.equal(coreOf(external('x', 'X')), null);
});

test('émulateurs proposés : Android (intégrés triés, puis émulateurs externes sans les modèles de cœur), Windows', () => {
  const psx = {
    id: 'psx',
    shortname: 'psx',
    players: [coreTemplate('pcsx_rearmed'), coreTemplate('duckstation'), coreTemplate('mednafen_psx_hw'), external('duck', 'DuckStation'), external('duck', 'DuckStation 2')],
  };
  const { android, androidtv, windows } = emulatorCandidates(psx);
  assert.deepEqual(android.map((e) => e.id), [
    'libretrodroid.mednafen_psx_hw', 'libretrodroid.pcsx_rearmed', 'libretrodroid.duckstation',
    'duck', 'duck#2',
  ]);
  assert.deepEqual(androidtv, android);
  assert.deepEqual(windows.map((e) => e.id), [
    'builtin:mednafen_psx_hw', 'builtin:pcsx_rearmed', 'builtin:duckstation',
  ]);

  const n64 = { id: 'n64', shortname: 'n64', players: [coreTemplate('mupen64plus_next_gles3'), coreTemplate('parallel_n64')] };
  assert.deepEqual(emulatorCandidates(n64).windows.map((e) => e.id), ['builtin:mupen64plus_next', 'builtin:parallel_n64']);
  assert.deepEqual(emulatorCandidates({ id: 'ps3', shortname: 'ps3', players: [] }).windows.map((e) => e.id), ['builtin:rpcs3', 'emu:rpcs3']);
  assert.deepEqual(emulatorCandidates({ id: 'switch', shortname: 'switch', players: [] }).windows.map((e) => e.id), ['emu:eden', 'emu:lichii', 'emu:ryujinx']);
});

test('ordre enregistré : plateformes connues, sans doublon, listes vides retirées', () => {
  assert.deepEqual(normalizeEmulatorOrder({ windows: ['a', 'b', 'a'], android: [], androidtv: null }), { windows: ['a', 'b'] });
  assert.deepEqual(normalizeEmulatorOrder(undefined), {});
  assert.throws(() => normalizeEmulatorOrder({ ios: ['a'] }), { status: 400 });
  assert.throws(() => normalizeEmulatorOrder({ windows: 'a' }), { status: 400 });
  assert.throws(() => normalizeEmulatorOrder(['a']), { status: 400 });
  assert.throws(() => normalizeEmulatorOrder({ windows: [1] }), { status: 400 });
});

test('ordre gardé avec le système, modifiable, remis par défaut', () => {
  createSystem({ id: 'snes', name: 'SNES' });
  assert.deepEqual(getSystem('snes').emulatorOrder, {});
  const order = { windows: ['builtin:snes9x', 'builtin:bsnes'], androidtv: ['libretrodroid.snes9x'] };
  assert.deepEqual(updateSystem('snes', { emulatorOrder: order }).emulatorOrder, order);
  assert.deepEqual(updateSystem('snes', { name: 'Super Nintendo' }).emulatorOrder, order);
  assert.deepEqual(updateSystem('snes', { emulatorOrder: { windows: [] } }).emulatorOrder, {});
});
