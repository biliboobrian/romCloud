import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-licenses-test-${process.pid}-${Date.now()}`);

const { db } = await import('../src/db.js');
const { createSystem } = await import('../src/systems.js');
const { gameWithParts, updateGame } = await import('../src/library.js');
const { importLicenseList, parseLicenseTsv } = await import('../src/licenses.js');

// Extrait d'une liste NoPayStation (PSV_GAMES.tsv).
const VITA_TSV = [
  'Title ID\tRegion\tName\tPKG direct link\tzRIF\tContent ID\tLast Modification Date\tOriginal Name\tFile Size\tSHA256\tRequired FW\tApp Version',
  'PCSG00312\tJP\tJ-STARS Victory VS AR Appli\thttp://x/a.pkg\tKO5ifR1dg/J5YXyvAJBLoLYYGxqB7A/3MjH1Cg4LC3YMcgwI8PHE5z9R2VQZhsSbzzP9pCRfuDp5jcb44AYA4XESlAAA\tJP0700-PCSG00312_00-WJ45JSVVSARAPPLI\t2018-05-20 09:25:52\tジェイスターズ\t229297056\t7e3c\t3\t1',
  'PCSA00001\tUS\tModNation Racers: Road Trip\thttp://x/b.pkg\tKO5ifR1dQ+eHBlgi6TIwBNnv6xcUZoAE8Pmvr7x+GZfOUenqhdMW+V+78HQ0xgc3AACVEBRW\tUP9000-PCSA00001_00-MNRV000000000000\t2017-09-26 00:00:00\t\t1566749952\t4a11\t1.05\t1',
  'PCSA00002\tUS\tMLB 12 The Show\thttp://x/c.pkg\tKO5ifR1dQ+eHBlgi6TIwAtnv6+NkaBTi4Rrs4R8e5hniiM9/Icc1Fv9jP54XopBzze3F/sujMT64AQDLrBWQ\tUP9000-PCSA00002_00-MLB12THESHOWVITA\t2018-03-29 03:41:54\t\t2938047056\t4c8c\t1.52\t1',
  'PCSA00003\tUS\tSans licence\thttp://x/d.pkg\tMISSING\tUP9000-PCSA00003_00-NOLICENSE0000000\t\t\t1\t\t1\t1',
].join('\r\n');

// Extrait d'une liste NoPayStation (PS3_GAMES.tsv).
const PS3_TSV = [
  'Title ID\tRegion\tName\tPKG direct link\tRAP\tContent ID\tLast Modification Date\tDownload .RAP file\tFile Size\tSHA256',
  'BCUS98181\tUS\tStarhawk Ultimate Edition\tMISSING\tMISSING\tUP9000-BCUS98181_00-ULTIMA0000000000\t\t\t25333088\t',
  'NPEB00113\tEU\tCall of Duty Classic French Version\thttp://x/a.pkg\t1763B2473C1553028847437992400021\tEP0002-NPEB00113_00-CALLOFDUTYCLSCFR\t2018-02-17 01:59:45\t\t987927856\t',
  'NPJJ00137\tJP\tMoney Idol Exchanger (PSX)\thttp://x/b.pkg\tD0AC94316D545425DBF709216C69D5DE\tJP0035-NPJJ00137_00-0000000000000001\t2017-12-03 00:56:26\t\t82114112',
].join('\n');

createSystem({ id: 'vita', name: 'Sony PS Vita' });
createSystem({ id: 'ps3', name: 'Sony PlayStation 3' });
const add = (system, fileName, title, { zrif = null, parentId = null, kind = null } = {}) => Number(
  db.prepare('INSERT INTO games (system_id, file_name, size, mtime, title, zrif, parent_id, part_kind) VALUES (?, ?, 1, 0, ?, ?, ?, ?)')
    .run(system, fileName, title, zrif, parentId, kind).lastInsertRowid,
);
const column = (id, name) => db.prepare(`SELECT ${name} AS v FROM games WHERE id = ?`).get(id).v;

test('liste TSV NoPayStation : colonnes lues par leur en-tête, licences absentes ignorées, fichier non reconnu refusé', () => {
  const entries = parseLicenseTsv(`﻿${VITA_TSV}`, 'zrif');
  assert.equal(entries.length, 3);
  assert.deepEqual(entries[1], {
    titleId: 'PCSA00001',
    contentId: 'UP9000-PCSA00001_00-MNRV000000000000',
    name: 'ModNation Racers: Road Trip',
    license: 'KO5ifR1dQ+eHBlgi6TIwBNnv6xcUZoAE8Pmvr7x+GZfOUenqhdMW+V+78HQ0xgc3AACVEBRW',
  });
  assert.deepEqual(parseLicenseTsv(PS3_TSV, 'rap').map((e) => [e.titleId, e.license]), [
    ['NPEB00113', '1763B2473C1553028847437992400021'],
    ['NPJJ00137', 'D0AC94316D545425DBF709216C69D5DE'],
  ]);
  assert.throws(() => parseLicenseTsv(PS3_TSV, 'zrif'), { status: 400 });
  assert.throws(() => parseLicenseTsv(PS3_TSV, 'licence'), { status: 400 });
  assert.throws(() => parseLicenseTsv('nom;licence\nA;B', 'zrif'), { status: 400 });
  assert.throws(() => parseLicenseTsv('', 'zrif'), { status: 400 });
});

test('zRIF : jeux reconnus par identifiant de contenu, sinon Title ID ; licence saisie gardée sauf remplacement', () => {
  const byContent = add('vita', 'JP0700-PCSG00312_00-WJ45JSVVSARAPPLI.pkg', 'J-Stars AR');
  const byTitle = add('vita', 'ModNation Racers (USA) [PCSA00001].vpk', 'ModNation Racers');
  const other = add('vita', 'UP9000-PCSA00002_00-MLB12THESHOWVITA.pkg', 'MLB 12', { zrif: 'KO5ifR1dQ+eHBlAUTRELICENCE' });
  const unknown = add('vita', 'Rayman Origins.vpk', 'Rayman Origins');
  // Mise à jour rattachée au jeu : pas de licence par son Title ID, pas signalée comme absente.
  add('vita', 'PCSA00001 v1.05 update.pkg', 'ModNation Racers', { parentId: byTitle, kind: 'update' });

  const report = importLicenseList('vita', 'zrif', VITA_TSV);
  assert.equal(report.entries, 3);
  assert.deepEqual(report.updated.map((g) => g.id).sort(), [byContent, byTitle].sort());
  assert.deepEqual(report.kept.map((g) => g.id), [other]);
  assert.deepEqual(report.notFound.map((g) => g.id), [unknown]);
  assert.match(column(byContent, 'zrif'), /^KO5ifR1dg\/J5YX/);
  assert.equal(column(other, 'zrif'), 'KO5ifR1dQ+eHBlAUTRELICENCE');

  const again = importLicenseList('vita', 'zrif', VITA_TSV, { replace: true });
  assert.deepEqual(again.updated.map((g) => g.id), [other]);
  assert.equal(again.unchanged.length, 2);
  assert.match(column(other, 'zrif'), /^KO5ifR1dQ\+eHBlgi6TIwAtnv/);
});

test('RAP : jeux et DLC PS3 reconnus, licence de 32 caractères hexadécimaux, envoyée avec les parties du jeu', () => {
  const cod = add('ps3', 'Call of Duty Classic [NPEB00113].pkg', 'Call of Duty Classic');
  const dlc = add('ps3', 'JP0035-NPJJ00137_00-0000000000000001.pkg', 'Money Idol Exchanger', { parentId: cod, kind: 'dlc' });
  const report = importLicenseList('ps3', 'rap', PS3_TSV);
  assert.deepEqual(report.updated.map((g) => g.id).sort(), [cod, dlc].sort());
  const game = gameWithParts(cod);
  assert.equal(game.rap, '1763B2473C1553028847437992400021');
  assert.equal(game.parts[0].rap, 'D0AC94316D545425DBF709216C69D5DE');

  assert.equal(updateGame(cod, { rap: ' d0ac9431 6d545425dbf709216c69d5de ' }).rap, 'D0AC94316D545425DBF709216C69D5DE');
  assert.throws(() => updateGame(cod, { rap: '1234' }), { status: 400 });
  assert.equal(updateGame(cod, { rap: '' }).rap, null);
  assert.throws(() => importLicenseList('ps3', 'licence', PS3_TSV), { status: 400 });
});
