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

test('couleur de fond : celle de la console par défaut, choisie dans les réglages, vide = retour au défaut', () => {
  const snes = listSystems().find((s) => s.id === 'snes');
  assert.equal(snes.color, snes.defaultColor);
  assert.equal(snes.customColor, false);
  assert.equal(updateSystem('snes', { color: '#12AB34' }).color, '#12ab34');
  assert.equal(updateSystem('snes', { name: 'Super Nintendo' }).color, '#12ab34');
  assert.throws(() => updateSystem('snes', { color: 'rouge' }), { status: 400 });
  const reset = updateSystem('snes', { color: '' });
  assert.equal(reset.color, snes.defaultColor);
  assert.equal(reset.customColor, false);
  assert.equal(createSystem({ id: 'maison', name: 'Console maison' }).color, null);
});

test('licence zRIF d’un jeu PS Vita : enregistrée sans espaces, vide = effacée, texte invalide refusé', async () => {
  const { updateGame } = await import('../src/library.js');
  createSystem({ id: 'vita', name: 'Sony PS Vita' });
  const { lastInsertRowid } = db.prepare('INSERT INTO games (system_id, file_name, size, mtime, title) VALUES (?, ?, 1, 0, ?)')
    .run('vita', 'EP0001-PCSB00079_00-RAYMANORIGINS000.pkg', 'Rayman Origins');
  const id = Number(lastInsertRowid);
  assert.equal(updateGame(id, { zrif: ' KO5ifR1dQ+eHBl3lI\n0VyBbYTnFA== ' }).zrif, 'KO5ifR1dQ+eHBl3lI0VyBbYTnFA==');
  assert.equal(updateGame(id, { title: 'Rayman Origins' }).zrif, 'KO5ifR1dQ+eHBl3lI0VyBbYTnFA==');
  assert.throws(() => updateGame(id, { zrif: 'pas une licence !' }), { status: 400 });
  assert.equal(updateGame(id, { zrif: '' }).zrif, null);
});

test('catalogue RomCloud : PS4 importée, proposée sous Windows seulement', async () => {
  const { importDaijishouPlatform } = await import('../src/systems.js');
  const ps4 = await importDaijishouPlatform({ filename: 'SonyPlayStation4.json' });
  assert.equal(ps4.id, 'ps4');
  assert.equal(ps4.source, 'romcloud');
  assert.deepEqual(ps4.platforms, ['windows']);
  assert.equal(ps4.screenscraperId, 60);
  assert.ok(!ids('android').includes('ps4'));
  assert.ok(ids('windows').includes('ps4'));
});
