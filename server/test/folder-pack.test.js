import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Readable } from 'node:stream';
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
const { xzAvailable, xzCompress } = await import('../src/xz.js');
const { zstdAvailable, zstdDecompressStream } = await import('../src/zstd.js');

const write = (file, data) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
};
const crcOf = (data) => zlib.crc32(data).toString(16).padStart(8, '0');

test('jeu PC compressé en zstd fichier par fichier : liste, archive ou fichier à la volée, anciens zip et xz convertis, retour en clair', { skip: (!zstdAvailable() || !xzAvailable()) && 'outil zstd ou xz absent' }, async () => {
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
    // Fichier compressé en xz par une version d'avant le zstd.
    const xzData = Buffer.from('archive xz '.repeat(3000));
    const xzPacked = await xzCompress(Readable.from([xzData]), path.join(dir, 'older.dat.xz'));
    write(path.join(dir, PACK_MANIFEST), JSON.stringify({ version: 2, files: {
      'old.dat': { size: zipped.size, crc32: zipped.crc32, mtime: 0 },
      'older.dat': { size: xzPacked.size, crc32: xzPacked.crc32, mtime: 0, format: 'xz' },
    } }));

    await json('/systems', { method: 'POST', body: { id: 'windows', name: 'PC' } });
    const [game] = await (await fetch(`${base}/systems/windows/games`)).json();
    assert.equal(game.packed, 'partial');
    assert.equal(game.packFormat, 'mixed');
    const before = await (await fetch(`${base}/games/${game.id}/files`)).json();
    const old = before.files.find((f) => f.path === 'old.dat');
    assert.equal(old.format, 'zip');
    assert.equal(old.zipSize, old.packedSize); // anciennes applications : zipSize
    assert.equal((await json(`/games/${game.id}/pack`, { method: 'POST', body: { mode: 'rar' } })).status, 400);

    assert.equal((await json(`/games/${game.id}/pack`, { method: 'POST', body: { mode: 'zstd' } })).status, 202);
    const jobs = await waitJobs();
    assert.equal(jobs[0].status, 'done', jobs[0].lastError);
    assert.ok(fs.existsSync(path.join(dir, 'data', 'level1.pak.zst')));
    assert.ok(!fs.existsSync(path.join(dir, 'data', 'level1.pak')));
    assert.ok(fs.existsSync(path.join(dir, 'old.dat.zst')));
    assert.ok(!fs.existsSync(path.join(dir, 'old.dat.zip'))); // zip converti
    assert.deepEqual(await buffer(zstdDecompressStream(path.join(dir, 'old.dat.zst'))), legacy);
    assert.ok(!fs.existsSync(path.join(dir, 'older.dat.xz'))); // xz converti
    assert.deepEqual(await buffer(zstdDecompressStream(path.join(dir, 'older.dat.zst'))), xzData);

    const packed = await (await fetch(`${base}/games/${game.id}`)).json();
    assert.equal(packed.packed, 'zip');
    assert.equal(packed.packFormat, 'zst');
    assert.equal(packed.size, game.size); // taille une fois installé : inchangée
    assert.ok(packed.packedSize < packed.size);
    assert.equal(packed.exe, 'Game.exe'); // exécutable toujours reconnu

    const listing = await (await fetch(`${base}/games/${game.id}/files?zst=1`)).json();
    assert.equal(listing.packFormat, 'zst');
    const pak = listing.files.find((f) => f.path === 'data/level1.pak');
    assert.equal(pak.format, 'zst');
    assert.equal(pak.size, level.length);
    assert.equal(pak.crc32, crcOf(level));
    assert.equal(pak.zipSize, undefined); // anciennes applications : fichier demandé en clair
    assert.ok(pak.packedSize < pak.size / 10);

    // Application récente : l'archive zstd, avec reprise ; ancienne : le fichier décompressé à la volée.
    const url = `${base}/games/${game.id}/file?path=${encodeURIComponent('data/level1.pak')}`;
    const zst = Buffer.from(await (await fetch(`${url}&packed=1&zst=1`)).arrayBuffer());
    assert.equal(zst.length, pak.packedSize);
    assert.equal(zst.readUInt32LE(0), 0xfd2fb528);
    const tail = await fetch(`${url}&packed=1&zst=1`, { headers: { Range: 'bytes=10-' } });
    assert.equal(tail.status, 206);
    assert.equal((await tail.arrayBuffer()).byteLength, pak.packedSize - 10);
    // Applications d'avant le zstd : fichiers listés en clair, envoyés décompressés.
    const older = await (await fetch(`${base}/games/${game.id}/files`)).json();
    const { mtime, ...flatEntry } = older.files.find((f) => f.path === 'data/level1.pak');
    assert.deepEqual(flatEntry, { path: 'data/level1.pak', size: level.length });
    assert.equal(mtime, pak.mtime); // date du fichier d'origine (mises à jour des applications)
    for (const old of [url, `${url}&packed=1`]) {
      const flat = await fetch(old);
      assert.equal(flat.headers.get('content-length'), String(level.length));
      assert.deepEqual(Buffer.from(await flat.arrayBuffer()), level);
    }
    assert.equal((await (await fetch(`${base}/games/${game.id}/file?path=empty.txt`)).arrayBuffer()).byteLength, 0);

    // Retour en clair : fichiers d'origine, manifeste retiré.
    await json(`/games/${game.id}/pack`, { method: 'POST', body: { mode: 'flat' } });
    assert.equal((await waitJobs())[0].status, 'done');
    assert.deepEqual(fs.readFileSync(path.join(dir, 'data', 'level1.pak')), level);
    assert.deepEqual(fs.readFileSync(path.join(dir, 'Game.exe')), random);
    assert.deepEqual(fs.readFileSync(path.join(dir, 'old.dat')), legacy);
    assert.deepEqual(fs.readFileSync(path.join(dir, 'older.dat')), xzData);
    assert.ok(!fs.existsSync(path.join(dir, 'data', 'level1.pak.zst')));
    assert.ok(!fs.existsSync(path.join(dir, PACK_MANIFEST)));
    const back = await (await fetch(`${base}/games/${game.id}`)).json();
    assert.equal(back.packed, 'flat');
    assert.equal(back.packFormat, null);
    assert.equal(back.packedSize, back.size);
  } finally {
    server.close();
  }
});
