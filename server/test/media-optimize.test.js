import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import sharp from 'sharp';

const dataDir = path.join(os.tmpdir(), `romcloud-media-optimize-test-${process.pid}`);
process.env.DATA_DIR = dataDir;

const { MAX_SIZE, optimizeOriginal, recompressAll } = await import('../src/media-optimize.js');
const { mediaVariant } = await import('../src/media-variants.js');
const { db } = await import('../src/db.js');
const { createSystem } = await import('../src/systems.js');
const { gameMediaDir } = await import('../src/library.js');

after(() => {
  try {
    fs.rmSync(dataDir, { recursive: true, force: true });
  } catch {
    // Base SQLite encore ouverte sous Windows.
  }
});

/** Jaquette « photo » : dégradés et bruit, en PNG (comme celles de ScreenScraper). */
function photo(width, height) {
  const data = Buffer.alloc(width * height * 3);
  for (let i = 0; i < width * height; i++) {
    const x = i % width;
    const y = Math.floor(i / width);
    data[i * 3] = (x * 255) / width;
    data[i * 3 + 1] = (y * 255) / height;
    data[i * 3 + 2] = (x * y + ((i * 7919) % 31)) % 256;
  }
  return sharp(data, { raw: { width, height, channels: 3 } }).png().toBuffer();
}

/** Capture d'une console 16 bits : 256 x 224, quelques couleurs en aplats (pixel art). */
function pixelArt() {
  const width = 256;
  const height = 224;
  const palette = [[0, 0, 0], [92, 148, 252], [252, 252, 252], [200, 76, 12], [0, 168, 0]];
  const data = Buffer.alloc(width * height * 3);
  for (let i = 0; i < width * height; i++) {
    const c = palette[(Math.floor((i % width) / 16) + Math.floor(i / width / 16)) % palette.length];
    data.set(c, i * 3);
  }
  return sharp(data, { raw: { width, height, channels: 3 } }).png({ palette: true }).toBuffer();
}

test('original : jaquette réduite à 1600 px et enregistrée en WebP, bien plus légère', async () => {
  const png = await photo(2000, 2600);
  const out = await optimizeOriginal(png, '.png');
  assert.equal(out.ext, '.webp');
  const meta = await sharp(out.buffer).metadata();
  assert.equal(meta.format, 'webp');
  assert.equal(meta.height, MAX_SIZE);
  assert.ok(out.buffer.length < png.length / 3, `${out.buffer.length} / ${png.length}`);
});

test('capture pixel art : WebP sans perte (couleurs exactes) ou original s\'il est plus léger', async () => {
  const png = await pixelArt();
  const out = await optimizeOriginal(png, '.png');
  assert.ok(out.buffer.length <= png.length);
  const pixels = async (b) => (await sharp(b).removeAlpha().raw().toBuffer()).toString('hex');
  assert.equal(await pixels(out.buffer), await pixels(png));
});

test('image illisible ou animée : gardée telle quelle', async () => {
  const junk = Buffer.from('pas une image');
  assert.deepEqual(await optimizeOriginal(junk, '.png'), { buffer: junk, ext: '.png' });
  // GIF animé de 2 images (16 x 16).
  const frames = Buffer.concat([Buffer.alloc(16 * 16 * 4, 200), Buffer.alloc(16 * 16 * 4, 40)]);
  const animated = await sharp(frames, { raw: { width: 16, height: 32, channels: 4, pageHeight: 16 } }).gif().toBuffer();
  assert.equal((await sharp(animated, { animated: true }).metadata()).pages, 2);
  const kept = await optimizeOriginal(animated, '.gif');
  assert.deepEqual([kept.ext, kept.buffer], ['.gif', animated]);
});

test('version réduite : original servi tel quel quand il est déjà le plus léger', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'romcloud-same-'));
  const file = path.join(dir, 'screenshot.png');
  fs.writeFileSync(file, await sharp(await pixelArt()).png({ palette: true, compressionLevel: 9 }).toBuffer());
  const variant = await mediaVariant(file, 'medium', path.join(dir, '_variants'));
  const size = fs.statSync(variant).size;
  assert.ok(size <= fs.statSync(file).size);
  // Demande suivante : même réponse, sans nouvelle conversion.
  assert.equal(await mediaVariant(file, 'medium', path.join(dir, '_variants')), variant);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('recompression des images gardées : fichiers et fiches mis à jour, versions réduites retirées', async () => {
  createSystem({ id: 'snes', name: 'SNES' });
  const id = Number(db.prepare('INSERT INTO games (system_id, file_name, size, mtime, title, boxart, screenshot) VALUES (?, ?, 1, 0, ?, ?, ?)')
    .run('snes', 'Jeu.sfc', 'Jeu', 'boxart.png', 'screenshot.webp').lastInsertRowid);
  const dir = gameMediaDir(id);
  fs.mkdirSync(path.join(dir, '_variants'), { recursive: true });
  const box = await photo(1200, 1500);
  fs.writeFileSync(path.join(dir, 'boxart.png'), box);
  fs.writeFileSync(path.join(dir, '_variants', 'boxart.png.thumb.1-1.webp'), 'ancienne');
  const shot = await sharp(await pixelArt()).webp({ lossless: true }).toBuffer();
  fs.writeFileSync(path.join(dir, 'screenshot.webp'), shot);

  const job = { cancelRequested: false };
  await recompressAll(job);
  assert.equal(job.total, 2);
  assert.equal(job.done, 2);
  assert.ok(job.saved > box.length / 2);
  const row = db.prepare('SELECT boxart, screenshot FROM games WHERE id = ?').get(id);
  assert.deepEqual([row.boxart, row.screenshot], ['boxart.webp', 'screenshot.webp']);
  assert.deepEqual(fs.readdirSync(dir).filter((f) => f !== '_variants').sort(), ['boxart.webp', 'screenshot.webp']);
  assert.deepEqual(fs.readdirSync(path.join(dir, '_variants')), []);
  // Capture déjà au plus léger : inchangée ; nouvelle passe : rien à gagner.
  assert.equal(fs.readFileSync(path.join(dir, 'screenshot.webp')).length, shot.length);
  const again = { cancelRequested: false };
  await recompressAll(again);
  assert.equal(again.saved, 0);
});
