import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';

const dataDir = path.join(os.tmpdir(), `romcloud-downloads-test-${process.pid}`);
process.env.DATA_DIR = dataDir;

const express = (await import('express')).default;
const { api } = await import('../src/api.js');
const accounts = await import('../src/accounts.js');
const { db } = await import('../src/db.js');
const { rangeStart } = await import('../src/download-logs.js');

// Jeu de 100 Ko dans le dossier des ROMs.
fs.mkdirSync(path.join(dataDir, 'roms', 'snes'), { recursive: true });
const rom = Buffer.alloc(100_000, 7);
fs.writeFileSync(path.join(dataDir, 'roms', 'snes', 'zelda.sfc'), rom);
db.prepare("INSERT INTO systems (id, name, shortname, folder) VALUES ('snes', 'Super Nintendo', 'snes', 'snes')").run();
const gameId = Number(db.prepare("INSERT INTO games (system_id, file_name, size, mtime, title) VALUES ('snes', 'zelda.sfc', 100000, 0, 'Zelda')").run().lastInsertRowid);

const server = await new Promise((resolve) => {
  const s = express().use('/api', api).listen(0, '127.0.0.1', () => resolve(s));
});
after(() => server.close());

const get = (pathname, headers = {}) => new Promise((resolve, reject) => {
  const req = http.get({ host: '127.0.0.1', port: server.address().port, path: pathname, headers, agent: false }, (res) => {
    let size = 0;
    res.on('data', (c) => (size += c.length));
    res.on('end', () => resolve({ status: res.statusCode, size }));
  });
  req.on('error', reject);
});
// La ligne du journal est écrite quand la réponse se termine : laisse le temps à l'évènement.
const settle = () => new Promise((resolve) => setTimeout(resolve, 50));

test('plage demandée', () => {
  assert.equal(rangeStart('bytes=1048576-'), 1048576);
  assert.equal(rangeStart(undefined), 0);
});

test('téléchargements notés : profil, appareil, reprise, anonyme ; statistiques', async () => {
  const { token } = accounts.register('alice', 'secret1', { device: 'Pixel 8', platform: 'android', appVersion: '1.34.2', ip: '10.0.0.2', userAgent: 'okhttp' });
  const phone = { 'X-RomCloud-Session': token, 'X-RomCloud-Device': 'Pixel 8', 'X-RomCloud-Platform': 'android', 'X-RomCloud-Version': '1.34.2' };

  // Téléchargement interrompu à 40 000 octets puis repris (Range), par le profil.
  assert.equal((await get(`/api/games/${gameId}/file`, { ...phone, Range: 'bytes=0-39999' })).size, 40000);
  assert.equal((await get(`/api/games/${gameId}/file`, { ...phone, Range: 'bytes=40000-' })).size, 60000);
  // Téléchargement complet, sans profil, depuis un PC.
  assert.equal((await get(`/api/games/${gameId}/file`, { 'X-RomCloud-Device': 'PC-SALON', 'X-RomCloud-Platform': 'windows' })).size, 100000);
  // Fichier inconnu : pas de ligne.
  assert.equal((await get('/api/games/9999/file')).status, 404);
  await settle();

  const rows = db.prepare('SELECT * FROM download_logs ORDER BY id').all();
  assert.equal(rows.length, 3);
  assert.deepEqual(rows.map((r) => [r.kind, r.item_id, r.range_start, r.complete, r.device, r.platform]), [
    ['game', gameId, 0, 0, 'Pixel 8', 'android'],
    ['game', gameId, 40000, 1, 'Pixel 8', 'android'],
    ['game', gameId, 0, 1, 'PC-SALON', 'windows'],
  ]);
  assert.ok(rows[0].user_id && rows[1].user_id === rows[0].user_id && rows[2].user_id === null);
  assert.ok(rows[0].bytes >= 40000 && rows[2].bytes >= 100000); // octets envoyés (en-têtes compris)
  assert.equal(rows[0].app_version, '1.34.2');

  const { downloadStats, listDownloads } = await import('../src/download-logs.js');
  const stats = downloadStats();
  assert.equal(stats.totals.requests, 3);
  assert.equal(stats.totals.downloads, 2); // la reprise ne compte pas comme un nouveau téléchargement
  assert.equal(stats.totals.completed, 2);
  const alice = stats.byUser.find((u) => u.username === 'alice');
  assert.deepEqual([alice.downloads, alice.requests], [1, 2]);
  assert.ok(stats.byUser.some((u) => u.userId === null)); // anonyme
  assert.deepEqual(stats.byDevice.map((d) => d.device).sort(), ['PC-SALON', 'Pixel 8']);
  assert.equal(stats.topItems[0].label, 'Zelda');
  assert.equal(stats.topItems[0].downloads, 2);
  assert.equal(stats.byDay.length, 1);
  // Filtres.
  assert.equal(listDownloads({ userId: 'anonymous' }).length, 1);
  assert.equal(listDownloads({ device: 'Pixel 8' })[0].username, 'alice');
  assert.equal(listDownloads({ kind: 'bios' }).length, 0);
});

test('espace disque : ROMs par système, sauvegardes par profil, base de données', async () => {
  const { diskUsage } = await import('../src/disk-usage.js');
  const user = db.prepare("SELECT id FROM users WHERE username = 'alice'").get();
  fs.mkdirSync(path.join(dataDir, 'states', String(user.id), String(gameId)), { recursive: true });
  fs.writeFileSync(path.join(dataDir, 'states', String(user.id), String(gameId), 'a.state'), Buffer.alloc(3000));
  fs.mkdirSync(path.join(dataDir, 'bios', 'snes'), { recursive: true });
  fs.writeFileSync(path.join(dataDir, 'bios', 'snes', 'bios.bin'), Buffer.alloc(500));
  const { tree, disks } = await diskUsage({ refresh: true });
  const find = (id, n = tree) => (n.id === id ? n : (n.children || []).map((c) => find(id, c)).find(Boolean));
  assert.equal(find('roms/snes').label, 'Super Nintendo');
  assert.equal(find('roms/snes').size, 100000);
  assert.equal(find('roms').size, 100000);
  assert.equal(find(`saves/${user.id}`).label, 'alice');
  assert.equal(find(`saves/${user.id}/states`).size, 3000);
  assert.equal(find('bios/snes').size, 500);
  assert.ok(find('database').size > 0);
  assert.equal(tree.size, tree.children.reduce((n, c) => n + c.size, 0));
  assert.ok(disks[0].total > 0);
});

test('suppression de plusieurs jeux d’un système', async () => {
  const post = (pathname, body) => new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const req = http.request({ host: '127.0.0.1', port: server.address().port, path: pathname, method: 'POST', agent: false,
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) } }, (res) => {
      let text = '';
      res.on('data', (c) => (text += c));
      res.on('end', () => resolve({ status: res.statusCode, json: text ? JSON.parse(text) : null }));
    });
    req.on('error', reject);
    req.end(data);
  });
  for (const name of ['a.sfc', 'b.sfc']) fs.writeFileSync(path.join(dataDir, 'roms', 'snes', name), Buffer.alloc(10));
  const ids = ['a.sfc', 'b.sfc'].map((name) => Number(db.prepare("INSERT INTO games (system_id, file_name, size, mtime, title) VALUES ('snes', ?, 10, 0, ?)").run(name, name).lastInsertRowid));
  assert.equal((await post('/api/systems/snes/games/delete', { ids: [...ids, 999999] })).status, 400); // jeu d'un autre système
  const res = await post('/api/systems/snes/games/delete', { ids });
  assert.deepEqual(res.json, { deleted: 2, freed: 20 });
  assert.ok(!fs.existsSync(path.join(dataDir, 'roms', 'snes', 'a.sfc')));
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM games WHERE id IN (?, ?)').get(...ids).n, 0);
});
