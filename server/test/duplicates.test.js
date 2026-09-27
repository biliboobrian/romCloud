import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-dup-test-${process.pid}`);
process.env.SCRAPE_REGIONS = 'fr,eu,wor,us,ss,jp';

const { compareForKeep, findDuplicateGroups, hasCopyMark, normalizeTitle } = await import('../src/duplicates.js');

let nextId = 1;
const game = (fileName, extra = {}) => ({
  id: nextId++,
  fileName,
  title: fileName.replace(/\.[^.]+$/, '').replace(/\s*[([][^)\]]*[)\]]/g, '').trim(),
  size: 1000,
  scrapeStatus: 'none',
  ...extra,
});

test('marques de copie', () => {
  assert.equal(hasCopyMark('Super Mario 64 (Europe) (En,Fr,De) (2).zip'), true);
  assert.equal(hasCopyMark('Tetris - Copie.gb'), true);
  assert.equal(hasCopyMark('Tetris (copy).gb'), true);
  assert.equal(hasCopyMark('Super Mario 64 (Europe) (En,Fr,De).zip'), false);
  assert.equal(hasCopyMark('Street Fighter II (Rev 1).sfc'), false);
});

test('titre normalisé', () => {
  assert.equal(normalizeTitle('Super Mario 64 (Europe) (En,Fr,De) (2).zip'), 'supermario64');
  assert.equal(normalizeTitle('Legend of Zelda, The - A Link to the Past (USA).sfc'), 'legendofzeldaalinktothepast');
  assert.equal(normalizeTitle('Pokémon Rouge (France).gb'), 'pokemonrouge');
});

test('fichier à conserver', () => {
  const copy = game('Mario (Europe) (2).zip', { scrapeStatus: 'ok' });
  const eu = game('Mario (Europe).zip');
  const us = game('Mario (USA).zip', { scrapeStatus: 'ok' });
  const beta = game('Mario (Europe) (Beta).zip');
  assert.deepEqual([copy, eu, us, beta].sort(compareForKeep).map((g) => g.fileName), [
    'Mario (Europe).zip', // région préférée, même non scrapé
    'Mario (USA).zip',
    'Mario (Europe) (Beta).zip',
    'Mario (Europe) (2).zip',
  ]);
  const rev0 = game('SF2 (USA).sfc');
  const rev1 = game('SF2 (USA) (Rev 1).sfc');
  assert.equal([rev0, rev1].sort(compareForKeep)[0], rev1);
  // À région et révision égales, le fichier scrapé l'emporte.
  const plain = game('Zelda (France).sfc');
  const scraped = game('Zelda (France) [!].sfc', { scrapeStatus: 'ok' });
  assert.equal([plain, scraped].sort(compareForKeep)[0], scraped);
});

test('regroupement identiques / similaires', async () => {
  const original = game('Super Mario 64 (Europe) (En,Fr,De).zip', { size: 8 });
  const copy = game('Super Mario 64 (Europe) (En,Fr,De) (2).zip', { size: 8 });
  const usa = game('Super Mario 64 (USA).zip', { size: 9 });
  const sameSizeOther = game('Wave Race 64 (Europe).zip', { size: 8 });
  const alone = game('Mario Kart 64 (Europe).zip', { size: 7 });
  const hashes = { [original.id]: 'aaa', [copy.id]: 'aaa', [sameSizeOther.id]: 'bbb' };
  const hashed = [];
  const res = await findDuplicateGroups([original, copy, usa, sameSizeOther, alone], async (g) => {
    hashed.push(g.id);
    return hashes[g.id] ?? null;
  });

  // Seuls les fichiers de même taille sont hachés.
  assert.deepEqual(hashed.sort(), [original.id, copy.id, sameSizeOther.id].sort());

  assert.equal(res.identical.length, 1);
  assert.equal(res.identical[0].keepId, original.id);
  assert.deepEqual(res.identical[0].games.map((g) => g.id), [original.id, copy.id]);

  // La copie identique n'est pas répétée dans les versions différentes.
  assert.equal(res.similar.length, 1);
  assert.deepEqual(res.similar[0].games.map((g) => g.id).sort(), [original.id, usa.id].sort());
  assert.equal(res.similar[0].keepId, original.id); // Europe avant USA
});
