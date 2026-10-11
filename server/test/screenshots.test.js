import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-screenshots-test-${process.pid}-${Date.now()}`);

const { db } = await import('../src/db.js');
const { createSystem } = await import('../src/systems.js');
const { deleteGameMedia, gameMediaDir, gameWithParts } = await import('../src/library.js');
const { saveCustomMedia } = await import('../src/scraper/index.js');
const { pickImages } = await import('../src/scraper/launchbox.js');

createSystem({ id: 'snes', name: 'SNES' });
const id = Number(db.prepare('INSERT INTO games (system_id, file_name, size, mtime, title) VALUES (?, ?, 1, 0, ?)')
  .run('snes', 'Chrono Trigger.sfc', 'Chrono Trigger').lastInsertRowid);
const png = (n) => Buffer.from(`image ${n}`);
const files = () => fs.readdirSync(gameMediaDir(id)).sort();
const content = (type) => {
  const row = db.prepare(`SELECT ${type} AS f FROM games WHERE id = ?`).get(id);
  return row.f && fs.readFileSync(path.join(gameMediaDir(id), row.f), 'utf8');
};

test('jusqu’à 4 captures : ajoutées sans trou, supprimées en remontant les suivantes', () => {
  saveCustomMedia(id, 'screenshot', png(1), 'image/png');
  // Capture envoyée à la 4e place alors que la 2e est vide : remontée à la 2e.
  let game = saveCustomMedia(id, 'screenshot4', png(2), 'image/jpeg');
  assert.equal(game.screenshotCount, 2);
  assert.deepEqual(files(), ['screenshot.png', 'screenshot2.jpg']);
  saveCustomMedia(id, 'screenshot3', png(3), 'image/png');
  game = saveCustomMedia(id, 'screenshot4', png(4), 'image/png');
  assert.equal(game.screenshotCount, 4);
  assert.equal(game.hasScreenshot, true);

  // Première supprimée : les autres remontent (2 → 1, 3 → 2, 4 → 3).
  game = deleteGameMedia(id, 'screenshot');
  assert.equal(game.screenshotCount, 3);
  assert.deepEqual([content('screenshot'), content('screenshot2'), content('screenshot3'), content('screenshot4')], ['image 2', 'image 3', 'image 4', null]);
  assert.deepEqual(files(), ['screenshot.jpg', 'screenshot2.png', 'screenshot3.png']);

  game = deleteGameMedia(id, 'screenshot2');
  assert.deepEqual([content('screenshot'), content('screenshot2')], ['image 2', 'image 4']);
  assert.equal(gameWithParts(id).screenshotCount, 2);
  assert.throws(() => deleteGameMedia(id, 'fanart'), { status: 404 });
});

test('LaunchBox : toutes les images d’un type, région préférée d’abord', () => {
  const images = [
    { type: 'Screenshot - Gameplay', region: 'Japan', file: 'jp.png' },
    { type: 'Screenshot - Gameplay', region: null, file: 'a.png' },
    { type: 'Screenshot - Gameplay', region: 'Europe', file: 'eu.png' },
    { type: 'Box - Front', region: null, file: 'box.png' },
  ];
  const names = pickImages(images, 'Screenshot - Gameplay', ['eu']).map((u) => u.split('/').pop());
  assert.equal(names.length, 3);
  assert.equal(names[0], 'eu.png');
});
