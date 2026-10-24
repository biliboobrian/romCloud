import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { buffer } from 'node:stream/consumers';
import zlib from 'node:zlib';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-folder-pack-test-${process.pid}`);
delete process.env.API_KEY;
delete process.env.ADMIN_KEY;
const { default: express } = await import('express');
const { api } = await import('../src/api.js');
const { config } = await import('../src/config.js');
const { PACK_MANIFEST } = await import('../src/folder-games.js');
const { packFile } = await import('../src/folder-pack.js');
const { xzAvailable, xzDecompressStream } = await import('../src/xz.js');

const write = (file, data) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
};
const crcOf = (data) => zlib.crc32(data).toString(16).padStart(8, '0');

test('jeu PC compressé en xz fichier par fichier : liste, archive ou fichier à la volée, ancien zip converti, retour en clair', { skip: !xzAvailable() && 'outil xz absent' }, async () => {
  const app = express();
  app.use(express.json());
  app.use('/api', api);
  const server = app.listen(0);
  const base = `http://127.0.0.1:${server.address().port}/api`;
  const json = (url, options = {}) =>
    fetch(`${base}${url}`, { ...options, headers: { 'Content-Type': 'application/json' }, body: options.body && JSON.stringify(options.body) });
  const waitJobs = async () => {
    for (let i = 0; i < 400; i++) {
      const jobs = await (await fetch(`${base}/jobs`)).json();
      if (!jobs.some((j) => j.status === 'queued' || j.status === 'running')) return jobs;
      await new Promise((r) => setTimeout(r, 25));
    }
    throw new Error('tâche trop longue');
  };
  try {
    const dir = path.join(config.romsDir, 'windows', 'Jeu');
    const level = Buffer.from('niveau '.repeat(20000)); // très compressible
    const random = Buffer.from(Array.from({ length: 5000 }, (_, i) => (i * 7919) % 251));
    const legacy = Buffer.from('ancienne archive '.repeat(3000));
    write(path.join(dir, 'Game.exe'), random);
    write(path.join(dir, 'data', 'level1.pak'), level);
    write(path.join(dir, 'empty.txt'), Buffer.alloc(0));
    // Fichier compressé en zip par une version d'avant le xz (manifeste sans format).
    write(path.join(dir, 'old.dat'), legacy);
    const zipped = await packFile(path.join(dir, 'old.dat'), path.join(dir, 'old.dat.zip'));
    fs.rmSync(path.join(dir, 'old.dat'));
    write(path.join(dir, PACK_MANIFEST), JSON.stringify({ version: 1, files: { 'old.dat': { size: zipped.size, crc32: zipped.crc32, mtime: 0 } } }));

    await json('/systems', { method: 'POST', body: { id: 'windows', name: 'PC' } });
    const [game] = await (await fetch(`${base}/systems/windows/games`)).json();
    assert.equal(game.packed, 'partial');
    assert.equal(game.packFormat, 'zip');
    const before = await (await fetch(`${base}/games/${game.id}/files`)).json();
    const old = before.files.find((f) => f.path === 'old.dat');
    assert.equal(old.format, 'zip');
    assert.equal(old.zipSize, old.packedSize); // anciennes applications : zipSize
    assert.equal((await json(`/games/${game.id}/pack`, { method: 'POST', body: { mode: 'rar' } })).status, 400);

    assert.equal((await json(`/games/${game.id}/pack`, { method: 'POST', body: { mode: 'xz' } })).status, 202);
    const jobs = await waitJobs();
    assert.equal(jobs[0].status, 'done', jobs[0].lastError);
    assert.ok(fs.existsSync(path.join(dir, 'data', 'level1.pak.xz')));
    assert.ok(!fs.existsSync(path.join(dir, 'data', 'level1.pak')));
    assert.ok(fs.existsSync(path.join(dir, 'old.dat.xz')));
    assert.ok(!fs.existsSync(path.join(dir, 'old.dat.zip'))); // zip converti
    assert.deepEqual(await buffer(xzDecompressStream(path.join(dir, 'old.dat.xz'))), legacy);

    const packed = await (await fetch(`${base}/games/${game.id}`)).json();
    assert.equal(packed.packed, 'zip');
    assert.equal(packed.packFormat, 'xz');
    assert.equal(packed.size, game.size); // taille une fois installé : inchangée
    assert.ok(packed.packedSize < packed.size);
    assert.equal(packed.exe, 'Game.exe'); // exécutable toujours reconnu

    const listing = await (await fetch(`${base}/games/${game.id}/files`)).json();
    assert.equal(listing.packFormat, 'xz');
    const pak = listing.files.find((f) => f.path === 'data/level1.pak');
    assert.equal(pak.format, 'xz');
    assert.equal(pak.size, level.length);
    assert.equal(pak.crc32, crcOf(level));
    assert.equal(pak.zipSize, undefined); // anciennes applications : fichier demandé en clair
    assert.ok(pak.packedSize < pak.size / 10);

    // Application récente : l'archive xz, avec reprise ; ancienne : le fichier décompressé à la volée.
    const url = `${base}/games/${game.id}/file?path=${encodeURIComponent('data/level1.pak')}`;
    const xz = Buffer.from(await (await fetch(`${url}&packed=1`)).arrayBuffer());
    assert.equal(xz.length, pak.packedSize);
    assert.equal(xz.subarray(0, 6).toString('latin1'), 'ý7zXZ\u0000');
    const tail = await fetch(`${url}&packed=1`, { headers: { Range: 'bytes=10-' } });
    assert.equal(tail.status, 206);
    assert.equal((await tail.arrayBuffer()).byteLength, pak.packedSize - 10);
    const flat = await fetch(url);
    assert.equal(flat.headers.get('content-length'), String(level.length));
    assert.deepEqual(Buffer.from(await flat.arrayBuffer()), level);
    assert.equal((await (await fetch(`${base}/games/${game.id}/file?path=empty.txt`)).arrayBuffer()).byteLength, 0);

    // Retour en clair : fichiers d'origine, manifeste retiré.
    await json(`/games/${game.id}/pack`, { method: 'POST', body: { mode: 'flat' } });
    assert.equal((await waitJobs())[0].status, 'done');
    assert.deepEqual(fs.readFileSync(path.join(dir, 'data', 'level1.pak')), level);
    assert.deepEqual(fs.readFileSync(path.join(dir, 'Game.exe')), random);
    assert.deepEqual(fs.readFileSync(path.join(dir, 'old.dat')), legacy);
    assert.ok(!fs.existsSync(path.join(dir, 'data', 'level1.pak.xz')));
    assert.ok(!fs.existsSync(path.join(dir, PACK_MANIFEST)));
    const back = await (await fetch(`${base}/games/${game.id}`)).json();
    assert.equal(back.packed, 'flat');
    assert.equal(back.packFormat, null);
    assert.equal(back.packedSize, back.size);
  } finally {
    server.close();
  }
});
