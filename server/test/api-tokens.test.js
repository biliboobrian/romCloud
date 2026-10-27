import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-api-tokens-test-${process.pid}`);
const tokens = await import('../src/api-tokens.js');
const { access, tokenAccess } = await import('../src/api.js');

const PC = 'device-pc-0001';
const PHONE = 'device-phone-0002';
const TV = 'device-tv-0003';

test('clé générée : places prises par les appareils, refusée au-delà', () => {
  const created = tokens.createToken({ name: 'Famille', maxDevices: 2 });
  assert.match(created.token, /^rct_/);
  assert.equal(created.maxDevices, 2);
  assert.equal(created.devices, 0);
  assert.equal(tokens.checkToken(created.token, PC, { device: 'PC' }), 'ok');
  assert.equal(tokens.checkToken(created.token, PC), 'ok'); // même appareil : pas de nouvelle place
  assert.equal(tokens.checkToken(created.token, PHONE, { platform: 'android' }), 'ok');
  assert.equal(tokens.checkToken(created.token, TV), 'device-limit');
  // Les appareils déjà enregistrés gardent l'accès.
  assert.equal(tokens.checkToken(created.token, PHONE), 'ok');
  const detail = tokens.tokenDetail(created.id);
  assert.equal(detail.devices, 2);
  assert.deepEqual(detail.deviceList.map((d) => d.deviceId).sort(), [PC, PHONE].sort());
  // La clé elle-même n'est jamais relue.
  assert.equal(detail.token, undefined);
  assert.equal(tokens.listTokens().find((t) => t.id === created.id).token, undefined);
  // Appareil retiré : sa place est libérée.
  tokens.removeTokenDevice(created.id, PC);
  assert.equal(tokens.checkToken(created.token, TV), 'ok');
  assert.equal(tokens.checkToken(created.token, PC), 'device-limit');
  // Plus de places : un nouvel appareil passe.
  tokens.updateToken(created.id, { maxDevices: 3 });
  assert.equal(tokens.checkToken(created.token, PC), 'ok');
});

test('clé générée : appareil requis, désactivation, suppression, clés inconnues', () => {
  const { id, token } = tokens.createToken({ name: 'Ami', maxDevices: 1 });
  assert.equal(tokens.checkToken(token, ''), 'device-required');
  assert.equal(tokens.checkToken(token, 'court'), 'device-required');
  assert.equal(tokens.checkToken('rct_inconnue', PC), 'unknown');
  assert.equal(tokens.checkToken('app', PC), 'unknown');
  tokens.updateToken(id, { disabled: true });
  assert.equal(tokens.checkToken(token, PC), 'disabled');
  tokens.updateToken(id, { disabled: false });
  assert.equal(tokens.checkToken(token, PC), 'ok');
  tokens.deleteToken(id);
  assert.equal(tokens.checkToken(token, PC), 'unknown');
  assert.throws(() => tokens.tokenDetail(id), /errors.tokenNotFound/);
});

test('clé générée : nom et nombre d’appareils vérifiés', () => {
  assert.throws(() => tokens.createToken({ name: '', maxDevices: 1 }), /errors.invalidTokenName/);
  assert.throws(() => tokens.createToken({ name: 'x', maxDevices: 0 }), /errors.invalidMaxDevices/);
  assert.throws(() => tokens.createToken({ name: 'x', maxDevices: 1.5 }), /errors.invalidMaxDevices/);
});

test('clé générée : droits de la clé des applications, en plus des clés fixes', () => {
  const keys = { apiKey: 'app', adminKey: 'admin' };
  const { token } = tokens.createToken({ name: 'Lecture', maxDevices: 5 });
  // Les clés fixes ne changent pas.
  assert.equal(access('GET', 'app', keys), 'ok');
  assert.equal(access('POST', 'admin', keys), 'ok');
  assert.equal(access('GET', token, keys), 'denied');
  // Clé générée : lectures (et profil du joueur), pas d'administration.
  assert.equal(tokenAccess('GET', token, PC, {}), 'ok');
  assert.equal(tokenAccess('POST', token, PC, {}), 'admin-required');
  assert.equal(tokenAccess('GET', 'autre', PC, {}), 'unknown');
});
