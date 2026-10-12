import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';
import sharp from 'sharp';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-media-route-test-${process.pid}`);
delete process.env.API_KEY;
delete process.env.ADMIN_KEY;
const { default: express } = await import('express');
const { api } = await import('../src/api.js');
const { createSystem } = await import('../src/systems.js');

test('image du système : originale, ou vignette WebP avec ?size=thumb', async () => {
  createSystem({ id: 'testsys', name: 'Test' });
  const app = express();
  app.use('/api', api);
  const server = app.listen(0);
  try {
    const base = `http://127.0.0.1:${server.address().port}/api/systems/testsys/image`;
    const png = await sharp({ create: { width: 1000, height: 500, channels: 4, background: '#3366cc' } }).png().toBuffer();
    const put = await fetch(base, { method: 'PUT', headers: { 'Content-Type': 'image/png' }, body: png });
    assert.equal(put.status, 200);
    const original = await fetch(base);
    assert.equal(original.headers.get('content-type'), 'image/png');
    const thumb = await fetch(`${base}?size=thumb`);
    assert.equal(thumb.status, 200);
    assert.equal(thumb.headers.get('content-type'), 'image/webp');
    assert.match(thumb.headers.get('cache-control'), /max-age=2592000/);
    assert.equal((await sharp(Buffer.from(await thumb.arrayBuffer())).metadata()).width, 400);
    // Taille inconnue : l'original.
    assert.equal((await fetch(`${base}?size=enorme`)).headers.get('content-type'), 'image/png');
  } finally {
    server.close();
  }
});
