// Émulateurs Android : miroir des émulateurs seul mécanisme d'installation (emulator-mirror.js,
// « /emulators/android »), émulateurs du miroir proposés avant les autres, systèmes abandonnés.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-android-emu-test-${process.pid}-${Date.now()}`);
// APK envoyés par les versions d'avant : supprimés à l'ouverture de la base.
fs.mkdirSync(path.join(process.env.DATA_DIR, 'apks'), { recursive: true });
fs.writeFileSync(path.join(process.env.DATA_DIR, 'apks', 'org.example.apk'), 'apk');

const { db } = await import('../src/db.js');
const { androidPackages, isMirroredPackage, SOURCES } = await import('../src/emulator-mirror.js');
const { packageOfPlayer, playersFor } = await import('../src/extra-cores.js');
const { createSystem, getSystem, importDaijishouPlatform, purgeDroppedSystems, DROPPED_SYSTEMS } = await import('../src/systems.js');

const external = (name, pkg) => ({ name, uniqueId: `x.${name}`, amStartArguments: `-n ${pkg}/.Main -d {file.uri}` });

test('APK envoyés à la main supprimés (fichiers et table)', () => {
  assert.equal(fs.existsSync(path.join(process.env.DATA_DIR, 'apks')), false);
  assert.equal(db.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'apks'").get(), undefined);
});

test('applications Android du miroir : paquets uniques, motifs valides, sources Android', () => {
  const list = androidPackages();
  assert.ok(list.length >= 15);
  assert.equal(new Set(list.map((a) => a.package)).size, list.length);
  for (const a of list) {
    assert.ok(SOURCES[a.source].platforms.includes('android'), a.package);
    assert.doesNotThrow(() => new RegExp(`^${a.asset}$`), a.package);
    assert.equal(typeof a.playStore, 'boolean');
  }
  const asset = (pkg) => new RegExp(`^${list.find((a) => a.package === pkg).asset}$`);
  assert.ok(asset('com.armsx3').test('ARMSX3-1.6.7-a13-armv8.2-sdk33.apk'));
  assert.ok(!asset('com.armsx3').test('ARMSX3-1.6.7-legacy-armv8.1-sdk30.apk'));
  assert.ok(asset('app.gamenative').test('gamenative-v1.4.0.apk'));
  assert.ok(!asset('app.gamenative').test('gamenative-v1.4.0-legacy-xr.apk'));
  assert.ok(asset('Ali.Xanite.green').test('Xenra-0.4.0-green-release.apk'));
  assert.equal(list.find((a) => a.package === 'com.izzy2lost.x1box').playStore, false);
  assert.ok(isMirroredPackage('dev.eden.eden_emulator') && !isMirroredPackage('org.citron.citron_emu'));
});

test('Android : émulateurs du miroir avant les autres émulateurs externes', () => {
  const xbox = {
    id: 'xbox',
    shortname: 'xbox',
    players: [external('ax360e', 'aenu.ax360e'), external('X1 Box', 'com.izzy2lost.x1box'), external('Xenra', 'Ali.Xanite.green')],
  };
  assert.deepEqual(playersFor(xbox, 'android').map((p) => p.name), ['X1 Box', 'Xenra', 'ax360e']);
  assert.deepEqual(playersFor(xbox, 'androidtv').map((p) => p.name), ['X1 Box', 'Xenra', 'ax360e']);
  assert.equal(packageOfPlayer(xbox.players[1]), 'com.izzy2lost.x1box');
  assert.equal(packageOfPlayer({ amStartArguments: '-a android.intent.action.VIEW -p com.github.stenzek.duckstation' }), 'com.github.stenzek.duckstation');
});

test('systèmes abandonnés : import refusé, supprimés au démarrage', async () => {
  assert.ok(DROPPED_SYSTEMS.has('triforce') && DROPPED_SYSTEMS.has('model3') && DROPPED_SYSTEMS.has('videos'));
  const json = { platform: { uniqueId: 'model3', name: 'Sega Model 3', shortname: 'model3' }, playerList: [] };
  await assert.rejects(importDaijishouPlatform({ json }), /systemDropped/);
  createSystem({ id: 'steam', name: 'Steam', shortname: 'steam', folder: 'steam', players: [] });
  createSystem({ id: 'xbox', name: 'Microsoft - Xbox', shortname: 'xbox', folder: 'xbox', players: [] });
  assert.deepEqual(purgeDroppedSystems(), ['Steam']);
  assert.equal(getSystem('steam'), null);
  assert.ok(getSystem('xbox'));
});
