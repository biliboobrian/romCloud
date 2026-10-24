import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-folder-pack-test-${process.pid}`);
delete process.env.API_KEY;
delete process.env.ADMIN_KEY;
const { default: express } = await import('express');
const { api } = await import('../src/api.js');
const { config } = await import('../src/config.js');
const { PACK_MANIFEST } = await import('../src/folder-games.js');
const { readZipEntries, readZipEntry } = await import('../src/zip.js');

const write = (file, data) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
};

test('jeu PC compressé fichier par fichier : liste, téléchargement (archive ou à la volée), retour en clair', async () => {
  const app = express();
  app.use(express.json());
  app.use('/api', api);
  const server = app.listen(0);
  const base = `http://127.0.0.1:${server.address().port}/api`;
  const json = (url, options = {}) =>
    fetch(`${base}${url}`, { ...options, headers: { 'Content-Type': 'application/json' }, body: options.body && JSON.stringify(options.body) });
  const waitJobs = async () => {
    for (let i = 0; i < 200; i++) {
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
    write(path.join(dir, 'Game.exe'), random);
    write(path.join(dir, 'data', 'level1.pak'), level);
    write(path.join(dir, 'empty.txt'), Buffer.alloc(0));
    await json('/systems', { method: 'POST', body: { id: 'windows', name: 'PC' } });
    const [game] = await (await fetch(`${base}/systems/windows/games`)).json();
    assert.equal(game.packed, 'flat');
    assert.equal(game.packedSize, game.size);

    // Jeu qui n'est pas un jeu PC, mode inconnu : refusés.
    assert.equal((await json(`/games/${game.id}/pack`, { method: 'POST', body: { mode: 'rar' } })).status, 400);

    assert.equal((await json(`/games/${game.id}/pack`, { method: 'POST', body: { mode: 'zip' } })).status, 202);
    const jobs = await waitJobs();
    assert.equal(jobs[0].status, 'done', jobs[0].lastError);
    assert.ok(fs.existsSync(path.join(dir, 'data', 'level1.pak.zip')));
    assert.ok(!fs.existsSync(path.join(dir, 'data', 'level1.pak')));
    assert.ok(fs.existsSync(path.join(dir, PACK_MANIFEST)));
    // Archive lisible par un outil ordinaire (un seul fichier, compressé).
    const [entry] = readZipEntries(path.join(dir, 'data', 'level1.pak.zip'));
    assert.equal(entry.name, 'level1.pak');
    assert.equal(entry.method, 8);
    assert.deepEqual(readZipEntry(path.join(dir, 'data', 'level1.pak.zip'), entry), level);

    const packed = await (await fetch(`${base}/games/${game.id}`)).json();
    assert.equal(packed.packed, 'zip');
    assert.equal(packed.size, game.size); // taille une fois installé : inchangée
    assert.ok(packed.packedSize < packed.size);
    assert.equal(packed.exe, 'Game.exe'); // exécutable toujours reconnu

    const listing = await (await fetch(`${base}/games/${game.id}/files`)).json();
    assert.equal(listing.packed, 'zip');
    const pak = listing.files.find((f) => f.path === 'data/level1.pak');
    assert.equal(pak.size, level.length);
    assert.equal(pak.crc32, zlib.crc32(level).toString(16).padStart(8, '0'));
    assert.ok(pak.zipSize < pak.size);

    // Application récente : l'archive, avec reprise ; ancienne : le fichier décompressé à la volée.
    const url = `${base}/games/${game.id}/file?path=${encodeURIComponent('data/level1.pak')}`;
    const zip = Buffer.from(await (await fetch(`${url}&packed=1`)).arrayBuffer());
    assert.equal(zip.length, pak.zipSize);
    const tail = await fetch(`${url}&packed=1`, { headers: { Range: 'bytes=10-' } });
    assert.equal(tail.status, 206);
    assert.equal((await tail.arrayBuffer()).byteLength, pak.zipSize - 10);
    const flat = await fetch(url);
    assert.equal(flat.headers.get('content-length'), String(level.length));
    assert.deepEqual(Buffer.from(await flat.arrayBuffer()), level);
    assert.equal((await (await fetch(`${base}/games/${game.id}/file?path=empty.txt`)).arrayBuffer()).byteLength, 0);

    // Retour en clair : fichiers d'origine, manifeste retiré.
    await json(`/games/${game.id}/pack`, { method: 'POST', body: { mode: 'flat' } });
    assert.equal((await waitJobs())[0].status, 'done');
    assert.deepEqual(fs.readFileSync(path.join(dir, 'data', 'level1.pak')), level);
    assert.deepEqual(fs.readFileSync(path.join(dir, 'Game.exe')), random);
    assert.ok(!fs.existsSync(path.join(dir, 'data', 'level1.pak.zip')));
    assert.ok(!fs.existsSync(path.join(dir, PACK_MANIFEST)));
    const back = await (await fetch(`${base}/games/${game.id}`)).json();
    assert.equal(back.packed, 'flat');
    assert.equal(back.packedSize, back.size);
  } finally {
    server.close();
  }
});
