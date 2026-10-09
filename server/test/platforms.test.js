import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-platforms-test-${process.pid}-${Date.now()}`);

const { db } = await import('../src/db.js');
const { availableOn, createSystem, listSystems, normalizePlatforms, updateSystem } = await import('../src/systems.js');
const { searchGames } = await import('../src/library.js');

createSystem({ id: 'snes', name: 'SNES' });
createSystem({ id: 'ps3', name: 'PlayStation 3', platforms: ['windows'] });
const add = db.prepare('INSERT INTO games (system_id, file_name, size, mtime, title) VALUES (?, ?, 1, 0, ?)');
add.run('snes', 'Super Mario World.sfc', 'Super Mario World');
add.run('ps3', 'Super Stardust HD.iso', 'Super Stardust HD');

const ids = (platform) => listSystems({ platform }).map((s) => s.id);
const found = (platform) => searchGames('super', { platform }).map((g) => g.systemId);

test('plateformes enregistrées : ordre fixe, toutes cochées = aucune restriction, inconnue refusée', () => {
  assert.deepEqual(normalizePlatforms(['windows', 'ANDROID']), ['android', 'windows']);
  assert.deepEqual(normalizePlatforms(['windows', 'android', 'androidtv']), []);
  assert.deepEqual(normalizePlatforms(undefined), []);
  assert.deepEqual(normalizePlatforms('androidtv'), ['androidtv']);
  assert.throws(() => normalizePlatforms(['ios']), { status: 400 });
});

test('systèmes proposés selon la plateforme de l’application ; administration : tous', () => {
  assert.deepEqual(ids('windows'), ['ps3', 'snes']);
  assert.deepEqual(ids('android'), ['snes']);
  assert.deepEqual(ids('androidtv'), ['snes']);
  assert.deepEqual(ids(''), ['ps3', 'snes']);
  assert.deepEqual(ids('inconnue'), ['ps3', 'snes']);
  assert.equal(availableOn({ platforms: [] }, 'android'), true);
});

test('recherche limitée aux systèmes de la plateforme', () => {
  assert.deepEqual(found('android'), ['snes']);
  assert.deepEqual(found('windows'), ['snes', 'ps3']);
  assert.deepEqual(found(undefined), ['snes', 'ps3']);
});

test('modification : plateformes gardées si absentes, remises à « toutes »', () => {
  assert.deepEqual(updateSystem('ps3', { name: 'PS3' }).platforms, ['windows']);
  assert.deepEqual(updateSystem('ps3', { platforms: ['android', 'androidtv', 'windows'] }).platforms, []);
  assert.deepEqual(ids('android'), ['ps3', 'snes']);
});
