import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-search-test-${process.pid}-${Date.now()}`);

const { db } = await import('../src/db.js');
const { searchGames } = await import('../src/library.js');

db.exec(`INSERT INTO systems (id, name, shortname, folder) VALUES ('snes', 'SNES', 'snes', 'snes'), ('n64', 'N64', 'n64', 'n64')`);
const add = db.prepare('INSERT INTO games (system_id, file_name, size, mtime, title) VALUES (?, ?, 1, 0, ?)');
add.run('snes', 'Super Mario World (USA).sfc', 'Super Mario World');
add.run('n64', 'Super Mario 64 (Europe).zip', 'Super Mario 64');
add.run('n64', 'Mario Kart 64 (Europe).zip', 'Mario Kart 64');
add.run('snes', '100%_Pure (Demo).sfc', '100% Pure');

const titles = (q, opts) => searchGames(q, opts).map((g) => `${g.systemId}:${g.title}`);

test('recherche dans tous les systèmes, triée par titre', () => {
  assert.deepEqual(titles('mario'), ['n64:Mario Kart 64', 'n64:Super Mario 64', 'snes:Super Mario World']);
});

test('tous les mots doivent correspondre, dans le titre ou le nom de fichier', () => {
  assert.deepEqual(titles('super 64'), ['n64:Super Mario 64']);
  assert.deepEqual(titles('europe kart'), ['n64:Mario Kart 64']);
});

test('caractères spéciaux cherchés tels quels, requête vide, limite', () => {
  assert.deepEqual(titles('100%'), ['snes:100% Pure']);
  assert.deepEqual(titles('_'), ['snes:100% Pure']);
  assert.deepEqual(titles('   '), []);
  assert.equal(titles('mario', { limit: 2 }).length, 2);
});
