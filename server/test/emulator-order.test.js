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

test('émulateurs proposés : Android (intégrés triés, sans les émulateurs externes d’une console du moteur intégré), Windows', () => {
  const psx = {
    id: 'psx',
    shortname: 'psx',
    players: [coreTemplate('pcsx_rearmed'), coreTemplate('duckstation'), coreTemplate('mednafen_psx_hw'), external('duck', 'DuckStation'), external('duck', 'DuckStation 2')],
  };
  const { android, androidtv, windows } = emulatorCandidates(psx);
  assert.deepEqual(android.map((e) => e.id), [
    'libretrodroid.mednafen_psx_hw', 'libretrodroid.pcsx_rearmed', 'libretrodroid.duckstation',
  ]);
  // Console hors moteur intégré (Naomi) : émulateur externe gardé après le cœur.
  const naomi = { id: 'naomi', shortname: 'naomi', players: [coreTemplate('flycast'), external('flycast-app', 'Flycast')] };
  assert.deepEqual(emulatorCandidates(naomi).android.map((e) => e.id), ['libretrodroid.flycast', 'flycast-app']);
  // Seul cœur de la console absent du buildbot Android : émulateur externe gardé.
  const onlyDuck = { id: 'psx', shortname: 'psx', players: [coreTemplate('duckstation'), external('duck', 'DuckStation')] };
  assert.deepEqual(emulatorCandidates(onlyDuck).android.map((e) => e.id), ['libretrodroid.duckstation', 'duck']);
  assert.deepEqual(androidtv, android);
  assert.deepEqual(windows.map((e) => e.id), [
    'builtin:mednafen_psx_hw', 'builtin:pcsx_rearmed', 'builtin:duckstation',
  ]);

  const n64 = { id: 'n64', shortname: 'n64', players: [coreTemplate('mupen64plus_next_gles3'), coreTemplate('parallel_n64')] };
  assert.deepEqual(emulatorCandidates(n64).windows.map((e) => e.id), ['builtin:mupen64plus_next', 'builtin:parallel_n64']);
  assert.deepEqual(emulatorCandidates({ id: 'ps3', shortname: 'ps3', players: [] }).windows.map((e) => e.id), ['builtin:rpcs3']);
  assert.deepEqual(emulatorCandidates({ id: 'switch', shortname: 'switch', players: [] }).windows.map((e) => e.id), ['emu:eden', 'emu:lichii', 'emu:orchid', 'emu:ryujinx']);
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

test('cœurs ajoutés par RomCloud envoyés aux applications (systemFor) : selon la plateforme, cœur par défaut inchangé', async () => {
  const { coreOf, systemFor } = await import('../src/extra-cores.js');
  const saturn = { id: 'saturn', shortname: 'saturn', players: [coreTemplate('mednafen_saturn'), coreTemplate('yabasanshiro')] };
  const ids = (s) => s.players.map(coreOf);
  // Kronos : Windows seulement (OpenGL de bureau).
  assert.deepEqual(ids(systemFor(saturn, 'windows')), ['mednafen_saturn', 'yabasanshiro', 'kronos']);
  assert.deepEqual(ids(systemFor(saturn, 'android')), ['mednafen_saturn', 'yabasanshiro']);
  // Administration (pas de plateforme) : modèles enregistrés tels quels.
  assert.equal(systemFor(saturn, '').players.length, 2);
  // Cœurs ajoutés après ceux de la table : le premier choix ne change pas.
  const nes = { id: 'nes', shortname: 'nes', players: [coreTemplate('fceumm'), coreTemplate('nestopia')] };
  const android = emulatorCandidates(nes).android.map((e) => e.id);
  assert.equal(android[0], 'libretrodroid.nestopia');
  assert.ok(android.includes('libretrodroid.mesen2'));
  assert.equal(emulatorCandidates(nes).windows[0].id, 'builtin:nestopia');
});

test('Xbox sous Windows : xemu installé avant le cœur xemu (pas encore essayé)', () => {
  const xbox = { id: 'xbox', shortname: 'xbox', players: [] };
  assert.deepEqual(emulatorCandidates(xbox).windows.map((e) => e.id), ['emu:xemu', 'builtin:xemu']);
  assert.deepEqual(emulatorCandidates(xbox).android, []);
});

test('réglages du système : émulateurs externes des consoles du moteur intégré retirés des modèles enregistrés', async () => {
  const { cleanStoredPlayers } = await import('../src/systems.js');
  const { db } = await import('../src/db.js');
  const psx = createSystem({ id: 'psx', name: 'PlayStation', players: [coreTemplate('mednafen_psx_hw'), external('duck', 'DuckStation')] });
  assert.deepEqual(psx.players.map((p) => p.uniqueId), ['core.mednafen_psx_hw']);
  // Ajout (copie depuis un autre système) : externe non gardé.
  assert.deepEqual(updateSystem('psx', { players: [...psx.players, external('epsxe', 'ePSXe')] }).players.map((p) => p.uniqueId), ['core.mednafen_psx_hw']);
  // Hors moteur intégré (Naomi), ou aucun cœur téléchargeable sous Android : gardés.
  assert.equal(createSystem({ id: 'naomi', name: 'Naomi', players: [coreTemplate('flycast'), external('fly', 'Flycast')] }).players.length, 2);
  assert.equal(createSystem({ id: 'psxduck', shortname: 'psx', name: 'PSX', players: [coreTemplate('duckstation'), external('duck', 'DuckStation')] }).players.length, 2);
  // Systèmes enregistrés par une version précédente : nettoyés au démarrage.
  db.prepare('UPDATE systems SET players = ? WHERE id = ?').run(JSON.stringify([coreTemplate('mednafen_psx_hw'), external('duck', 'DuckStation'), external('fpse', 'FPse')]), 'psx');
  assert.equal(cleanStoredPlayers(), 2);
  assert.equal(getSystem('psx').players.length, 1);
  assert.equal(cleanStoredPlayers(), 0);
});
