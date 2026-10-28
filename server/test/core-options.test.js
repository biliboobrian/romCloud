import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-core-options-test-${process.pid}-${Date.now()}`);

const { db } = await import('../src/db.js');
const { getSystem, updateSystem } = await import('../src/systems.js');
const { gameWithParts, updateGame } = await import('../src/library.js');
const { listCatalog, normalizeCoreOptions, reportCatalog } = await import('../src/core-options.js');

db.prepare("INSERT INTO systems (id, name, shortname, folder) VALUES ('n64', 'Nintendo 64', 'n64', 'n64')").run();
const gameId = Number(db.prepare("INSERT INTO games (system_id, file_name, size, mtime, title) VALUES ('n64', 'mario.z64', 1, 0, 'Mario')").run().lastInsertRowid);

test('options normalisées : valeurs en texte, valeur vide retirée, clé invalide refusée', () => {
  assert.deepEqual(normalizeCoreOptions({ 'mupen64plus-43screensize': '640x480', other: '', gone: null, n: 2 }), {
    'mupen64plus-43screensize': '640x480',
    n: '2',
  });
  assert.deepEqual(normalizeCoreOptions(null), {});
  assert.throws(() => normalizeCoreOptions({ 'a b': 'x' }), /invalidCoreOptions/);
  assert.throws(() => normalizeCoreOptions({ 'a=b': 'x' }), /invalidCoreOptions/);
  assert.throws(() => normalizeCoreOptions({ a: 'x\ny' }), /invalidCoreOptions/);
  assert.throws(() => normalizeCoreOptions(['a']), /invalidCoreOptions/);
});

test('options du système et du jeu enregistrées et renvoyées', () => {
  assert.deepEqual(getSystem('n64').coreOptions, {});
  assert.deepEqual(gameWithParts(gameId).coreOptions, {});
  updateSystem('n64', { coreOptions: { 'mupen64plus-rdp-plugin': 'gliden64' } });
  assert.deepEqual(getSystem('n64').coreOptions, { 'mupen64plus-rdp-plugin': 'gliden64' });
  // Autre réglage du système : options gardées.
  updateSystem('n64', { color: '#123456' });
  assert.deepEqual(getSystem('n64').coreOptions, { 'mupen64plus-rdp-plugin': 'gliden64' });
  updateGame(gameId, { coreOptions: { 'mupen64plus-rdp-plugin': 'angrylion' } });
  assert.deepEqual(gameWithParts(gameId).coreOptions, { 'mupen64plus-rdp-plugin': 'angrylion' });
  updateGame(gameId, { coreOptions: {} });
  assert.deepEqual(gameWithParts(gameId).coreOptions, {});
});

test('catalogue des options envoyé par les applications, par cœur et plateforme', () => {
  reportCatalog(
    {
      core: 'mupen64plus_next_gles3',
      system: 'n64',
      options: [
        { key: 'mupen64plus-rdp-plugin', label: 'RDP Plugin', values: ['gliden64', 'angrylion'] },
        { key: 'mupen64plus-rdp-plugin', label: 'doublon' },
        { key: 'bad key', label: 'ignorée' },
        { key: 'mupen64plus-43screensize', label: 'Resolution', values: [{ value: '640x480', label: '640 × 480' }], default: '640x480' },
      ],
    },
    { platform: 'android', appVersion: '1.84.0' },
  );
  assert.deepEqual(listCatalog('n64')[0].options, [
    { key: 'mupen64plus-rdp-plugin', label: 'RDP Plugin', values: [{ value: 'gliden64', label: 'gliden64' }, { value: 'angrylion', label: 'angrylion' }], default: 'gliden64' },
    { key: 'mupen64plus-43screensize', label: 'Resolution', values: [{ value: '640x480', label: '640 × 480' }], default: '640x480' },
  ]);
  assert.equal(listCatalog('n64')[0].appVersion, '1.84.0');
  reportCatalog({ core: 'mupen64plus_next_gles3', system: 'n64dd', options: [] }, { platform: 'android' });
  reportCatalog({ core: 'snes9x', system: 'snes', options: [{ key: 'snes9x_region', values: ['auto'] }] }, { platform: 'windows' });

  const n64 = listCatalog('n64');
  assert.equal(n64.length, 1);
  assert.equal(n64[0].core, 'mupen64plus_next_gles3');
  assert.deepEqual(n64[0].systems, ['n64', 'n64dd']);
  // Dernier envoi : remplace les options.
  assert.deepEqual(n64[0].options, []);
  assert.equal(listCatalog().length, 2);
  assert.deepEqual(listCatalog('snes')[0].options, [
    { key: 'snes9x_region', label: 'snes9x_region', values: [{ value: 'auto', label: 'auto' }], default: 'auto' },
  ]);
  assert.throws(() => reportCatalog({ core: '../x', options: [] }, {}), /invalidCoreOptions/);
});
