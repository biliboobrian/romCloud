import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-accounts-test-${process.pid}`);
const accounts = await import('../src/accounts.js');
const { access, api, routeMethod } = await import('../src/api.js');
const { db } = await import('../src/db.js');

db.prepare("INSERT INTO systems (id, name, shortname, folder) VALUES ('snes', 'Super Nintendo', 'snes', 'snes')").run();
const gameId = Number(db.prepare("INSERT INTO games (system_id, file_name, size, mtime, title) VALUES ('snes', 'zelda.sfc', 1, 0, 'Zelda')").run().lastInsertRowid);

const phone = { device: 'Pixel 8', platform: 'android', appVersion: '1.0', ip: '10.0.0.2', userAgent: 'okhttp' };
const tv = { device: 'Shield', platform: 'androidtv', appVersion: '1.0', ip: '10.0.0.3', userAgent: 'okhttp' };

const statusAsync = async (promise) => {
  try {
    await promise;
  } catch (err) {
    return err.status;
  }
  return 200;
};

const status = (fn) => {
  try {
    fn();
  } catch (err) {
    return err.status;
  }
  return 200;
};

test('mot de passe haché et vérifié', () => {
  const stored = accounts.hashPassword('secret1');
  assert.match(stored, /^scrypt\$/);
  assert.ok(accounts.verifyPassword('secret1', stored));
  assert.ok(!accounts.verifyPassword('secret2', stored));
});

test('profil : la clé des applications suffit ; utilisateurs et journaux : administration', () => {
  const keys = { apiKey: 'app', adminKey: 'admin' };
  assert.equal(access(routeMethod('POST', '/account/login'), 'app', keys), 'ok');
  assert.equal(access(routeMethod('PUT', '/account/saves/1/snes9x/state'), 'app', keys), 'ok');
  assert.equal(access(routeMethod('GET', '/users'), 'app', keys), 'admin-required');
  assert.equal(access(routeMethod('GET', '/logs/errors'), 'admin', keys), 'ok');
  assert.equal(access(routeMethod('POST', '/systems'), 'app', keys), 'admin-required');
});

test('création de compte, connexion, session, déconnexion', () => {
  const { token, user } = accounts.register('Alice', 'secret1', phone);
  assert.equal(user.username, 'Alice');
  assert.equal(status(() => accounts.register('alice', 'secret1', phone)), 409); // insensible à la casse
  assert.equal(status(() => accounts.register('al', 'secret1', phone)), 400);
  assert.equal(status(() => accounts.register('bob', '123', phone)), 400);

  const auth = accounts.authenticate(token, phone);
  assert.equal(auth.user.id, user.id);
  assert.equal(accounts.authenticate('faux', phone), null);

  assert.equal(status(() => accounts.login('alice', 'mauvais', phone)), 401);
  const second = accounts.login('ALICE', 'secret1', tv);
  assert.notEqual(second.token, token);
  accounts.logout(accounts.authenticate(second.token, tv), tv);
  assert.equal(accounts.authenticate(second.token, tv), null);

  const detail = accounts.adminUserDetail(user.id);
  assert.equal(detail.sessions.length, 1);
  assert.deepEqual(detail.logins.map((e) => [e.event, e.success]), [['logout', true], ['login', true], ['login', false], ['register', true]]);
});

test('trop de tentatives : connexion bloquée', () => {
  accounts.createUser('carol', 'secret1');
  for (let i = 0; i < 10; i++) assert.equal(status(() => accounts.login('carol', 'non', phone)), 401);
  assert.equal(status(() => accounts.login('carol', 'secret1', phone)), 429);
});

test('compte désactivé : sessions fermées, connexion refusée', () => {
  const { token, user } = accounts.register('dave', 'secret1', phone);
  accounts.adminUpdateUser(user.id, { disabled: true });
  assert.equal(accounts.authenticate(token, phone), null);
  assert.equal(status(() => accounts.login('dave', 'secret1', phone)), 403);
});

test('TV connectée par QR code validé depuis le téléphone', () => {
  const { token } = accounts.login('alice', 'secret1', phone);
  const pair = accounts.createPair(tv);
  assert.match(pair.code, /^[A-Z2-9]{8}$/);
  assert.equal(accounts.pollPair(pair.code, pair.secret).status, 'pending');
  assert.equal(status(() => accounts.pollPair(pair.code, 'mauvais')), 403);
  assert.equal(accounts.pairInfo(pair.code.toLowerCase()).device, 'Shield');

  accounts.approvePair(pair.code, accounts.authenticate(token, phone), phone);
  const result = accounts.pollPair(pair.code, pair.secret);
  assert.equal(result.status, 'approved');
  assert.equal(result.user.username, 'Alice');
  assert.equal(accounts.authenticate(result.token, tv).user.username, 'Alice');
  // Jeton remis une seule fois.
  assert.equal(accounts.pollPair(pair.code, pair.secret).status, 'expired');
});

test('diffusion : TV du profil proposées au téléphone, pas aux autres profils', () => {
  const phoneAuth = accounts.authenticate(accounts.login('alice', 'secret1', phone).token, phone);
  const tvAuth = accounts.authenticate(accounts.login('alice', 'secret1', tv).token, tv);
  const key = 'a'.repeat(32);
  assert.equal(status(() => accounts.announceReceiver(phoneAuth, { addresses: ['10.0.0.2'], port: 5000, key }, phone)), 400);
  assert.equal(status(() => accounts.announceReceiver(tvAuth, { addresses: [], port: 5000, key }, tv)), 400);
  accounts.announceReceiver(tvAuth, { addresses: ['10.0.0.3'], port: 41000, key, width: 3840, height: 2160 }, tv);

  const [receiver, ...others] = accounts.listReceivers(phoneAuth);
  assert.equal(others.length, 0);
  assert.deepEqual(receiver.addresses, ['10.0.0.3']);
  assert.equal(receiver.port, 41000);
  assert.equal(receiver.key, key);
  assert.equal(receiver.device, 'Shield');
  assert.equal(receiver.height, 2160);
  // La TV ne se voit pas elle-même ; un autre profil ne la voit pas.
  assert.equal(accounts.listReceivers(tvAuth).length, 0);
  accounts.createUser('bob', 'secret1');
  const bobAuth = accounts.authenticate(accounts.login('bob', 'secret1', phone).token, phone);
  assert.equal(accounts.listReceivers(bobAuth).length, 0);

  accounts.withdrawReceiver(tvAuth);
  assert.equal(accounts.listReceivers(phoneAuth).length, 0);
});

test('temps de jeu cumulé par jeu', () => {
  const { user } = accounts.authenticate(accounts.login('alice', 'secret1', phone).token, phone);
  accounts.addPlaytime(user.id, gameId, 600);
  const p = accounts.addPlaytime(user.id, gameId, 1200.4);
  assert.equal(p.seconds, 1800);
  assert.equal(p.sessions, 2);
  assert.equal(status(() => accounts.addPlaytime(user.id, 999999, 10)), 404);
  assert.equal(status(() => accounts.addPlaytime(user.id, gameId, -5)), 400);
  assert.equal(accounts.adminListUsers().find((u) => u.id === user.id).playSeconds, 1800);
});

test('sauvegardes en ligne : enregistrées, relues, remplacées, supprimées', async () => {
  const { user } = accounts.authenticate(accounts.login('alice', 'secret1', phone).token, phone);
  assert.equal(status(() => accounts.getSave(user.id, gameId, 'snes9x', 'state')), 404);
  assert.equal(await statusAsync(accounts.putSave(user.id, gameId, '../x', 'state', Buffer.from('a'), 1, phone)), 400);
  assert.equal(await statusAsync(accounts.putSave(user.id, gameId, 'snes9x', 'autre', Buffer.from('a'), 1, phone)), 400);

  await accounts.putSave(user.id, gameId, 'snes9x', 'state', Buffer.from('v1'), 1000, phone);
  const saved = await accounts.putSave(user.id, gameId, 'snes9x', 'state', Buffer.from('v2'), 2000, tv);
  assert.equal(saved.savedAt, new Date(2000).toISOString());
  assert.equal(saved.device, 'Shield');
  const { file, encoding } = accounts.getSave(user.id, gameId, 'snes9x', 'state');
  assert.equal(accounts.readStored(file, encoding).toString(), 'v2');
  assert.equal(accounts.listSaves(user.id, gameId).length, 1);

  accounts.deleteSave(user.id, gameId, 'snes9x', 'state');
  assert.equal(accounts.listSaves(user.id).length, 0);
  assert.ok(!fs.existsSync(file));
});

test('historique des états : 10 par appareil, épinglés gardés, miniature, profils séparés', async () => {
  const { user } = accounts.authenticate(accounts.login('alice', 'secret1', phone).token, phone);
  const id = (n) => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`;
  assert.equal(await statusAsync(accounts.putState(user.id, gameId, 'snes9x', '../x', Buffer.from('a'), {}, phone)), 400);

  // 12 états du téléphone (le premier épinglé) et 1 de la TV.
  for (let n = 1; n <= 12; n++) await accounts.putState(user.id, gameId, 'snes9x', id(n), Buffer.from(`s${n}`), { createdAt: n * 1000, pinned: n === 1 }, phone);
  await accounts.putState(user.id, gameId, 'snes9x', id(100), Buffer.from('tv'), { createdAt: 500 }, tv);
  const list = accounts.listStates(user.id, gameId);
  const fromPhone = list.filter((s) => s.device === 'Pixel 8');
  assert.equal(fromPhone.length, 11); // 10 plus récents + l'épinglé
  assert.ok(fromPhone.some((s) => s.id === id(1) && s.pinned));
  assert.ok(!fromPhone.some((s) => s.id === id(2)));
  assert.ok(list.some((s) => s.id === id(100) && s.device === 'Shield')); // la TV garde le sien
  assert.equal(list[0].id, id(12));
  assert.equal(list[0].createdAt, new Date(12000).toISOString());

  // Miniature, fichier relu.
  assert.equal(status(() => accounts.putStateThumbnail(user.id, id(12), Buffer.from('x'), 'text/plain')), 400);
  assert.ok(accounts.putStateThumbnail(user.id, id(12), Buffer.from('jpeg'), 'image/jpeg').thumbnail);
  assert.equal(fs.readFileSync(accounts.getStateThumbnail(user.id, id(12)).file, 'utf8'), 'jpeg');
  const { file: s12, encoding: e12 } = accounts.getState(user.id, id(12));
  assert.equal(accounts.readStored(s12, e12).toString(), 's12');

  // Désépinglé : au-delà de la limite, supprimé avec ses fichiers.
  const pinnedFile = accounts.getState(user.id, id(1)).file;
  assert.equal(accounts.pinState(user.id, id(1), false), null);
  assert.ok(!fs.existsSync(pinnedFile));

  // Autre profil : ne voit ni ne remplace les états d'alice.
  const erin = accounts.authenticate(accounts.register('erin', 'secret1', phone).token, phone).user;
  assert.equal(accounts.listStates(erin.id, gameId).length, 0);
  assert.equal(status(() => accounts.getState(erin.id, id(12))), 404);
  assert.equal(await statusAsync(accounts.putState(erin.id, gameId, 'snes9x', id(12), Buffer.from('b'), {}, phone)), 409);

  const thumb = accounts.getStateThumbnail(user.id, id(12)).file;
  accounts.deleteState(user.id, id(12));
  assert.ok(!fs.existsSync(thumb));
  assert.equal(status(() => accounts.getState(user.id, id(12))), 404);
});

test('sauvegardes compressées par les applications : gardées telles quelles, décompressées pour les anciennes', async () => {
  const http = await import('node:http');
  const zlib = await import('node:zlib');
  const express = (await import('express')).default;
  const app = express().use('/api', api);
  const server = await new Promise((resolve) => { const s = app.listen(0, '127.0.0.1', () => resolve(s)); });
  const { port } = server.address();
  const { token } = accounts.login('alice', 'secret1', phone);
  const request = (method, path, { body, headers = {} } = {}) => new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, method, path, headers: { 'X-RomCloud-Session': token, ...headers } }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
    });
    req.on('error', reject);
    req.end(body);
  });
  try {
    // État typique : mémoire en grande partie répétitive.
    const state = Buffer.alloc(200_000, 0);
    for (let i = 0; i < state.length; i += 97) state[i] = i % 251;
    const packed = zlib.gzipSync(state);
    const put = await request('PUT', `/api/account/saves/${gameId}/genesis_plus_gx/state`, {
      body: packed,
      headers: { 'Content-Type': 'application/octet-stream', 'Content-Encoding': 'gzip', 'X-Uncompressed-Size': String(state.length), 'X-Saved-At': '5000' },
    });
    assert.equal(put.status, 200);
    assert.equal(JSON.parse(put.body).size, state.length);
    const { user } = accounts.authenticate(token, phone);
    const { file, encoding } = accounts.getSave(user.id, gameId, 'genesis_plus_gx', 'state');
    assert.equal(encoding, 'gzip');
    assert.deepEqual(fs.readFileSync(file), packed); // gardé compressé
    assert.deepEqual(accounts.readStored(file, encoding), state);

    // Application qui accepte gzip : fichier compressé renvoyé tel quel.
    const gz = await request('GET', `/api/account/saves/${gameId}/genesis_plus_gx/state`, { headers: { 'Accept-Encoding': 'gzip' } });
    assert.equal(gz.headers['content-encoding'], 'gzip');
    assert.deepEqual(zlib.gunzipSync(gz.body), state);
    // Version précédente (sans Accept-Encoding) : décompressé par le serveur.
    const plain = await request('GET', `/api/account/saves/${gameId}/genesis_plus_gx/state`);
    assert.equal(plain.headers['content-encoding'], undefined);
    assert.deepEqual(plain.body, state);

    // Envoi non compressé (version précédente) : gardé tel quel ; encodage inconnu refusé.
    const raw = await request('PUT', `/api/account/saves/${gameId}/ppsspp/state`, { body: Buffer.from('brut'), headers: { 'Content-Type': 'application/octet-stream' } });
    assert.equal(raw.status, 200);
    assert.equal(accounts.getSave(user.id, gameId, 'ppsspp', 'state').encoding, 'identity');
    const br = await request('PUT', `/api/account/saves/${gameId}/ppsspp/state`, { body: Buffer.from('x'), headers: { 'Content-Encoding': 'br' } });
    assert.equal(br.status, 415);
  } finally {
    server.close();
  }
});

test('erreurs des applications, avec ou sans joueur', () => {
  const auth = accounts.authenticate(accounts.login('alice', 'secret1', phone).token, phone);
  accounts.logError({ message: 'Cœur introuvable', context: 'launch', details: 'stack' }, auth, phone);
  accounts.logError({ message: 'Anonyme' }, null, tv);
  assert.equal(status(() => accounts.logError({}, null, tv)), 400);
  const log = accounts.adminErrorLog();
  assert.deepEqual(log.slice(0, 2).map((e) => [e.message, e.username]), [['Anonyme', null], ['Cœur introuvable', 'Alice']]);
  assert.equal(accounts.adminUserDetail(auth.user.id).errors.length, 1);
});

test('rapport gardé hors ligne : date de l’erreur conservée, long journal accepté', () => {
  const twoDaysAgo = Date.now() - 2 * 86400_000;
  accounts.logError({ context: 'report', message: 'Écran noir', details: 'x'.repeat(150_000), at: twoDaysAgo }, null, phone);
  accounts.logError({ context: 'report', message: 'Date future', at: Date.now() + 86400_000 * 365 }, null, phone);
  const rows = db.prepare("SELECT message, at, length(details) AS size FROM error_logs WHERE context = 'report' ORDER BY id").all();
  const day = (ms) => new Date(ms).toISOString().slice(0, 10);
  assert.equal(rows[0].at.slice(0, 10), day(twoDaysAgo));
  assert.equal(rows[0].size, 150_000);
  assert.equal(rows[1].at.slice(0, 10), day(Date.now())); // date invraisemblable : maintenant
});
