import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-groups-test-${process.pid}-${Date.now()}`);

const { db } = await import('../src/db.js');
const { autoGroups, parsePkgHeader, partInfo, setGroup } = await import('../src/groups.js');
const { createSystem, systemDir, requireSystem } = await import('../src/systems.js');
const { deleteGame, listGames, scanSystem, searchGames } = await import('../src/library.js');

test('parties reconnues dans les noms de fichiers', () => {
  assert.deepEqual(partInfo('Final Fantasy VII (Europe) (Disc 2).chd'), { kind: 'disc', index: 2, key: 'final fantasy vii (europe)' });
  assert.equal(partInfo('Final Fantasy VII (Europe) (Disc 1).chd').key, 'final fantasy vii (europe)');
  assert.equal(partInfo('Riven (Disk 3 of 5).cue').index, 3);
  assert.equal(partInfo('Lunar [CD2].iso').index, 2);
  assert.equal(partInfo('Policenauts - Disc B.chd').index, 2);
  assert.equal(partInfo('Metal Gear Solid_CD2.pbp').index, 2);
  assert.equal(partInfo('Super Mario World (USA).sfc'), null);
  assert.equal(partInfo('Discworld (Europe).chd'), null); // « Disc » dans le titre
  assert.equal(partInfo('Mario Kart 8 [DLC].wua').kind, 'dlc');
  assert.equal(partInfo('Mario Kart 8 (Update v4.1).wua').kind, 'update');
  assert.equal(partInfo('Mario Kart 8 [UPD].wua').kind, 'update');
  assert.deepEqual(
    [partInfo('Zelda BotW [0005000E101C9400].wua').kind, partInfo('Zelda BotW [0005000C101C9400].wua').kind, partInfo('Zelda BotW [00050000101C9400].wua')],
    ['update', 'dlc', null],
  );
  assert.equal(partInfo('UP0001-BLUS30443_00-DEMONSSOULSDLC01.pkg').serial, 'BLUS30443');
});

/** En-tête de paquet Sony : métadonnées 2 (type de contenu) et 3 (drapeaux) à l'adresse 640. */
function pkg(type, flags) {
  const b = Buffer.alloc(1024);
  b.writeUInt32BE(0x7f504b47, 0);
  b.writeUInt32BE(640, 8);
  b.writeUInt32BE(3, 12);
  let off = 640;
  for (const [id, value] of [[1, 0], [2, type], [3, flags]]) {
    b.writeUInt32BE(id, off);
    b.writeUInt32BE(4, off + 4);
    b.writeUInt32BE(value, off + 8);
    off += 12;
  }
  return b;
}

test('paquets Sony : type de contenu lu dans l’en-tête', () => {
  assert.deepEqual(parsePkgHeader(pkg(0x15, 0x300e)), { type: 0x15, patch: false });
  assert.deepEqual(parsePkgHeader(pkg(0x15, 0x301e)), { type: 0x15, patch: true });
  assert.deepEqual(parsePkgHeader(pkg(0x16, 0x0e)), { type: 0x16, patch: false });
  assert.equal(parsePkgHeader(Buffer.from('pas un paquet')), null);
  // Libellé sans « DLC » (« AC3LIBERATIONULC ») : DLC d'après l'en-tête.
  assert.equal(partInfo('EP0001-PCSB00074_00-AC3LIBERATIONULC.pkg', { type: 0x16, patch: false }).kind, 'dlc');
  assert.equal(partInfo('EP0001-PCSB00074_00-AC3LIBERATION575.pkg', { type: 0x15, patch: false }), null);
  assert.equal(partInfo('EP0001-PCSB00061_00-LUMINESELECTROSY_patch_01.02.pkg', { type: 0x15, patch: true }).kind, 'update');
  // Sans en-tête : identifiant de contenu.
  assert.equal(partInfo('EP0001-PCSB00598_00-DLC0100000000000.pkg').kind, 'dlc');
  assert.equal(partInfo('EP0031-PCSB00701_00-NORDLCONTENTS001.pkg').serial, 'PCSB00701');
  assert.equal(partInfo('EP0001-PCSB00061_00-LUMINESELECTROSY_patch_01.02.pkg').kind, 'update');
  assert.equal(partInfo('EP0001-PCSB00598_00-CHILDOFLIGHT0000.pkg'), null);
});

test('PS Vita : patchs et DLC rattachés au jeu du même numéro de série', () => {
  const files = [
    ['EP0001-PCSB00598_00-CHILDOFLIGHT0000.pkg', { type: 0x15, patch: false }],
    ['EP0001-PCSB00598_00-DLC0100000000000.pkg', { type: 0x16, patch: false }],
    ['EP0001-PCSB00598_00-DLC0200000000000.pkg', { type: 0x16, patch: false }],
    ['EP0001-PCSB00074_00-AC3LIBERATION575.pkg', { type: 0x15, patch: false }],
    ['EP0001-PCSB00074_00-AC3LIBERATION575_patch_01.02.pkg', { type: 0x15, patch: true }],
    ['EP0001-PCSB00074_00-AC3LIBERATIONULC.pkg', { type: 0x16, patch: false }],
    ['EP0002-PCSB00428_00-AMAZINGSPIDERMDL.pkg', { type: 0x15, patch: false }],
    ['EP0031-PCSB00999_00-ORPHANDLC0000001.pkg', { type: 0x16, patch: false }], // jeu absent
  ].map(([fileName, header], i) => ({ id: i + 1, fileName, pkg: header }));
  const groups = autoGroups(files);
  const g = (id) => [groups.get(id).parentId, groups.get(id).kind];
  assert.deepEqual([g(1), g(2), g(3)], [[null, null], [1, 'dlc'], [1, 'dlc']]);
  assert.deepEqual([g(4), g(5), g(6)], [[null, null], [4, 'update'], [4, 'dlc']]);
  assert.deepEqual([g(7), g(8)], [[null, null], [null, null]]);
});

test('regroupement : disques, mises à jour et DLC rattachés à leur jeu', () => {
  const files = [
    'Final Fantasy VII (Europe) (Disc 1).chd',
    'Final Fantasy VII (Europe) (Disc 2).chd',
    'Final Fantasy VII (Europe) (Disc 3).chd',
    'Final Fantasy VIII (Europe) (Disc 2).chd', // disque seul : jeu à part entière
    'Mario Kart 8 (Europe).wua',
    'Mario Kart 8 [DLC] Pack 1.wua',
    'Mario Kart 8 (Update v4.1).wua',
    'Zelda BotW [00050000101C9400].wua',
    'Zelda BotW Update [0005000E101C9400].wua',
    "Demon's Souls (USA) [BLUS30443].iso",
    'UP0001-BLUS30443_00-DEMONSSOULSDLC01.pkg',
    'Orphan Game [DLC].wua', // sans jeu correspondant
    'Star Sky [ATZP7P] [Europe].wua',
    'Star Sky 2 [BY2P7P] [UPDATE v32] [Europe].wua', // suite, pas la mise à jour de Star Sky
    'Toki Tori [WKRPTW] [Europe].wua',
    'Toki Tori 2+ [WAAPTW] [UPDATE v32] [Europe].wua',
  ].map((fileName, i) => ({ id: i + 1, fileName }));
  const groups = autoGroups(files);
  const parent = (id) => groups.get(id).parentId;
  assert.deepEqual([parent(1), parent(2), parent(3), parent(4)], [null, 1, 1, null]);
  assert.deepEqual(groups.get(3), { parentId: 1, kind: 'disc', index: 3 });
  assert.deepEqual([parent(6), groups.get(6).kind, parent(7), groups.get(7).kind], [5, 'dlc', 5, 'update']);
  assert.deepEqual([parent(9), groups.get(9).kind], [8, 'update']);
  assert.equal(parent(11), 10);
  assert.equal(parent(12), null);
  assert.deepEqual([parent(14), parent(16)], [null, null]);
});

test('scan, liste, recherche, rattachement manuel et suppression', () => {
  createSystem({ id: 'psx', name: 'PlayStation' });
  const dir = systemDir(requireSystem('psx'));
  for (const [name, size] of [['Lunar (USA) (Disc 1).chd', 10], ['Lunar (USA) (Disc 2).chd', 20], ['Lunar Bonus.chd', 5], ['Tekken (USA).chd', 7]]) {
    fs.writeFileSync(path.join(dir, name), Buffer.alloc(size));
  }
  scanSystem('psx');
  let games = listGames('psx');
  assert.deepEqual(games.map((g) => g.fileName), ['Lunar (USA) (Disc 1).chd', 'Lunar Bonus.chd', 'Tekken (USA).chd']);
  const lunar = games[0];
  assert.deepEqual(lunar.parts.map((p) => [p.fileName, p.kind, p.index]), [['Lunar (USA) (Disc 2).chd', 'disc', 2]]);
  assert.equal(lunar.totalSize, 30);
  assert.deepEqual(searchGames('lunar').map((g) => g.fileName), ['Lunar (USA) (Disc 1).chd', 'Lunar Bonus.chd']);

  // Rattachement manuel : gardé au scan suivant.
  const bonus = games[1];
  setGroup(bonus.id, { parentId: lunar.id, kind: 'dlc' });
  scanSystem('psx');
  games = listGames('psx');
  assert.deepEqual(games.map((g) => g.fileName), ['Lunar (USA) (Disc 1).chd', 'Tekken (USA).chd']);
  assert.deepEqual(games[0].parts.map((p) => p.kind), ['disc', 'dlc']);

  // Détaché à la main : reste un jeu même si son nom ressemble à un disque.
  const disc2 = games[0].parts[0];
  setGroup(disc2.id, { parentId: null });
  scanSystem('psx');
  assert.equal(listGames('psx').length, 3);
  setGroup(disc2.id, { auto: true });
  assert.equal(listGames('psx').length, 2);

  assert.throws(() => setGroup(games[1].id, { parentId: disc2.id, kind: 'dlc' }), { status: 400 }); // une partie ne reçoit pas de partie
  assert.throws(() => setGroup(games[1].id, { parentId: lunar.id, kind: 'bonus' }), { status: 400 });

  // Supprimer le jeu supprime aussi ses parties.
  deleteGame(lunar.id);
  assert.deepEqual(listGames('psx').map((g) => g.fileName), ['Tekken (USA).chd']);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM games').get().n, 1);
  assert.deepEqual(fs.readdirSync(dir), ['Tekken (USA).chd']);
});
