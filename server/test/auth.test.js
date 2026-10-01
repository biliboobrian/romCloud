import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-auth-test-${process.pid}`);
const { access } = await import('../src/api.js');

test('deux clés : applications en lecture seule, administration complète', () => {
  const keys = { apiKey: 'app', adminKey: 'admin' };
  assert.equal(access('GET', 'app', keys), 'ok');
  assert.equal(access('HEAD', 'app', keys), 'ok');
  assert.equal(access('POST', 'app', keys), 'admin-required');
  assert.equal(access('DELETE', 'app', keys), 'admin-required');
  assert.equal(access('GET', 'admin', keys), 'ok');
  assert.equal(access('PUT', 'admin', keys), 'ok');
  assert.equal(access('GET', 'autre', keys), 'denied');
  assert.equal(access('GET', undefined, keys), 'denied');
});

test('une seule clé (comportement précédent) ou aucune', () => {
  assert.equal(access('DELETE', 'app', { apiKey: 'app', adminKey: '' }), 'ok');
  assert.equal(access('GET', 'x', { apiKey: 'app', adminKey: '' }), 'denied');
  assert.equal(access('POST', undefined, { apiKey: '', adminKey: '' }), 'ok');
  // Clé d'administration seule : lectures libres, modifications protégées.
  assert.equal(access('GET', undefined, { apiKey: '', adminKey: 'admin' }), 'ok');
  assert.equal(access('POST', undefined, { apiKey: '', adminKey: 'admin' }), 'denied');
});
