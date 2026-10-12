import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import sharp from 'sharp';
import { mediaVariant, VARIANTS } from '../src/media-variants.js';

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'romcloud-variants-'));
const variants = path.join(dir, '_variants');

/** Jaquette de test : PNG de [width] x [height] avec un dégradé (transparent sur un bord). */
async function png(file, width, height) {
  const data = Buffer.alloc(width * height * 4);
  for (let i = 0; i < width * height; i++) {
    data[i * 4] = i % 256;
    data[i * 4 + 1] = (i >> 8) % 256;
    data[i * 4 + 2] = 128;
    data[i * 4 + 3] = i % width < 10 ? 0 : 255;
  }
  await sharp(data, { raw: { width, height, channels: 4 } }).png().toFile(file);
}

test('vignette et version moyenne en WebP, réduites sans être agrandies', async () => {
  const file = path.join(dir, 'boxart.png');
  await png(file, 1200, 1600);
  const thumb = await mediaVariant(file, 'thumb', variants);
  const meta = await sharp(thumb).metadata();
  assert.equal(meta.format, 'webp');
  assert.equal(meta.width, VARIANTS.thumb);
  assert.equal(meta.height, Math.round((1600 * VARIANTS.thumb) / 1200));
  assert.ok(meta.hasAlpha, 'transparence gardée');
  assert.ok(fs.statSync(thumb).size < fs.statSync(file).size / 4);
  // Même demande : même fichier, sans nouvelle conversion.
  assert.equal(await mediaVariant(file, 'thumb', variants), thumb);
  // Image plus petite que la taille demandée : gardée à sa taille.
  const small = path.join(dir, 'screenshot.png');
  await png(small, 320, 240);
  assert.equal((await sharp(await mediaVariant(small, 'medium', variants)).metadata()).width, 320);
});

test('original remplacé : nouvelle version, l\'ancienne retirée', async () => {
  const file = path.join(dir, 'boxart2.png');
  await png(file, 900, 900);
  const first = await mediaVariant(file, 'thumb', variants);
  await png(file, 600, 300);
  fs.utimesSync(file, new Date(), new Date(Date.now() + 5000));
  const second = await mediaVariant(file, 'thumb', variants);
  assert.notEqual(second, first);
  assert.equal(fs.existsSync(first), false);
  assert.equal((await sharp(second).metadata()).height, 200);
});

test('taille inconnue, fichier absent ou illisible : l\'original est servi', async () => {
  const file = path.join(dir, 'boxart.png');
  assert.equal(await mediaVariant(file, 'huge', variants), null);
  assert.equal(await mediaVariant(path.join(dir, 'absent.png'), 'thumb', variants), null);
  const broken = path.join(dir, 'broken.png');
  fs.writeFileSync(broken, 'pas une image');
  assert.equal(await mediaVariant(broken, 'thumb', variants), null);
});
