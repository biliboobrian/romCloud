import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Readable } from 'node:stream';
import { buffer } from 'node:stream/consumers';
import zlib from 'node:zlib';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-folder-pack-test-${process.pid}`);
// Archives regroupées de 100 000 octets au plus (100 Mo hors des tests).
process.env.PACK_BUNDLE_BYTES = '100000';
delete process.env.API_KEY;
delete process.env.ADMIN_KEY;
const { default: express } = await import('express');
const { api } = await import('../src/api.js');
const { config } = await import('../src/config.js');
const { PACK_MANIFEST } = await import('../src/folder-games.js');
const { bundleGroups, packFile } = await import('../src/folder-pack.js');
const { xzAvailable, xzCompress } = await import('../src/xz.js');
const { zstdAvailable, zstdCompress, zstdDecompressStream } = await import('../src/zstd.js');

const write = (file, data) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
};
const crcOf = (data) => zlib.crc32(data).toString(16).padStart(8, '0');

test("fichiers répartis en groupes de 100 Mo au plus, les plus lourds d'abord", () => {
  const sizes = (groups) => groups.map((g) => g.map((f) => f.size));
  assert.deepEqual(sizes(bundleGroups([10, 95, 40, 5, 50].map((size) => ({ size })), 100)), [[95, 5], [50, 40, 10]]);
  // Fichier qu'aucun autre ne complète : seul dans son groupe.
  assert.deepEqual(sizes(bundleGroups([95, 30, 80].map((size) => ({ size })), 100)), [[95], [80], [30]]);
  assert.deepEqual(bundleGroups([], 100), []);
});

test('jeu PC compressé en zstd : gros fichiers seuls, petits regroupés ; liste, archive ou fichier à la volée, anciens zip, xz et zstd convertis, retour en clair', { skip: (!zstdAvailable() || !xzAvailable()) && 'outil zstd ou xz absent' }, async () => {
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
    // Petit fichier compressé seul en zstd, d'avant les archives regroupées.
    const tinyData = Buffer.from('petit fichier '.repeat(100));
    const tinyPacked = await zstdCompress(Readable.from([tinyData]), path.join(dir, 'data', 'tiny.dat.zst'));
    write(path.join(dir, PACK_MANIFEST), JSON.stringify({ version: 2, files: {
      'old.dat': { size: zipped.size, crc32: zipped.crc32, mtime: 0 },
      'older.dat': { size: xzPacked.size, crc32: xzPacked.crc32, mtime: 0, format: 'xz' },
      'data/tiny.dat': { size: tinyPacked.size, crc32: tinyPacked.crc32, mtime: 0, format: 'zst' },
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
    // Plus de 100 000 octets : son archive à lui ; les autres regroupés dans une seule archive
    // (anciennes archives zip, xz et zstd converties).
    assert.ok(fs.existsSync(path.join(dir, 'data', 'level1.pak.zst')));
    assert.ok(!fs.existsSync(path.join(dir, 'data', 'level1.pak')));
    for (const gone of ['old.dat.zip', 'older.dat.xz', 'data/tiny.dat.zst', 'Game.exe', 'empty.txt']) assert.ok(!fs.existsSync(path.join(dir, gone)), gone);
    const bundleFile = path.join(dir, '.romcloud-bundles', '1.zst');
    assert.deepEqual(fs.readdirSync(path.dirname(bundleFile)), ['1.zst']);
    const bundled = await buffer(zstdDecompressStream(bundleFile));
    const entries = JSON.parse(fs.readFileSync(path.join(dir, PACK_MANIFEST), 'utf8')).files;
    const contents = { 'Game.exe': random, 'empty.txt': Buffer.alloc(0), 'old.dat': legacy, 'older.dat': xzData, 'data/tiny.dat': tinyData };
    assert.equal(bundled.length, Object.values(contents).reduce((n, b) => n + b.length, 0));
    for (const [rel, data] of Object.entries(contents)) {
      const entry = entries[rel];
      assert.equal(entry.bundle, '1', rel);
      assert.deepEqual(bundled.subarray(entry.offset, entry.offset + entry.size), data, rel);
      assert.equal(entry.crc32, crcOf(data), rel);
    }
    assert.equal(entries['data/level1.pak'].bundle, undefined);

    const packed = await (await fetch(`${base}/games/${game.id}`)).json();
    assert.equal(packed.packed, 'zip');
    assert.equal(packed.packFormat, 'zst');
    assert.equal(packed.size, game.size); // taille une fois installé : inchangée
    assert.ok(packed.packedSize < packed.size);
    assert.equal(packed.exe, 'Game.exe'); // exécutable toujours reconnu

    assert.equal(packed.packedSize, fs.statSync(path.join(dir, 'data', 'level1.pak.zst')).size + fs.statSync(bundleFile).size);

    const listing = await (await fetch(`${base}/games/${game.id}/files?zst=1&bundles=1`)).json();
    assert.equal(listing.packFormat, 'zst');
    const pak = listing.files.find((f) => f.path === 'data/level1.pak');
    assert.equal(pak.format, 'zst');
    assert.equal(pak.size, level.length);
    assert.equal(pak.crc32, crcOf(level));
    assert.equal(pak.zipSize, undefined); // anciennes applications : fichier demandé en clair
    assert.ok(pak.packedSize < pak.size / 10);
    const { mtime: _, ...legacyEntry } = listing.files.find((f) => f.path === 'old.dat');
    assert.deepEqual(legacyEntry, { path: 'old.dat', size: legacy.length, format: 'zst', crc32: crcOf(legacy), bundle: '1', offset: entries['old.dat'].offset });
    assert.deepEqual(listing.bundles, { 1: { packedSize: fs.statSync(bundleFile).size, size: bundled.length } });
    // Archive regroupée téléchargée telle quelle, avec reprise.
    const bundleUrl = `${base}/games/${game.id}/bundle?id=1`;
    assert.deepEqual(Buffer.from(await (await fetch(bundleUrl)).arrayBuffer()), fs.readFileSync(bundleFile));
    const bundleTail = await fetch(bundleUrl, { headers: { Range: 'bytes=4-' } });
    assert.equal(bundleTail.status, 206);
    assert.equal((await bundleTail.arrayBuffer()).byteLength, listing.bundles[1].packedSize - 4);
    assert.equal((await fetch(`${base}/games/${game.id}/bundle?id=..%2Fx`)).status, 400);
    // Applications qui ne lisent pas les archives regroupées : fichiers regroupés listés en clair,
    // tirés de leur archive à la volée (même demandés compressés).
    const zstOnly = await (await fetch(`${base}/games/${game.id}/files?zst=1`)).json();
    assert.equal(zstOnly.bundles, undefined);
    assert.deepEqual(Object.keys(zstOnly.files.find((f) => f.path === 'older.dat')).sort(), ['mtime', 'path', 'size']);
    assert.equal(zstOnly.files.find((f) => f.path === 'data/level1.pak').format, 'zst');
    for (const rel of ['older.dat', 'data/tiny.dat', 'empty.txt']) {
      const fileUrl = `${base}/games/${game.id}/file?path=${encodeURIComponent(rel)}`;
      for (const query of ['', '&packed=1&zst=1']) {
        const got = await fetch(`${fileUrl}${query}`);
        assert.equal(got.headers.get('content-length'), String(contents[rel].length));
        assert.deepEqual(Buffer.from(await got.arrayBuffer()), contents[rel], rel);
      }
    }

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
    assert.deepEqual(fs.readFileSync(path.join(dir, 'data', 'tiny.dat')), tinyData);
    assert.equal(fs.readFileSync(path.join(dir, 'empty.txt')).length, 0);
    assert.ok(!fs.existsSync(path.dirname(bundleFile)));
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
