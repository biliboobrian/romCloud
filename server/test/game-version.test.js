import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-game-version-test-${process.pid}`);
delete process.env.API_KEY;
delete process.env.ADMIN_KEY;
const { cleanVersion, versionFromName, exeVersionFrom, isEngineExe } = await import('../src/game-version.js');
const { createSystem } = await import('../src/systems.js');
const { scanSystem, detectVersions, getGameRow, gameFolderFiles, gameWithParts, replaceWithVersion, updateGame } = await import('../src/library.js');
const { config } = await import('../src/config.js');
const { db } = await import('../src/db.js');

/** Exécutable Windows minimal : en-têtes PE et section .rsrc avec la chaîne ProductVersion [version]. */
function fakeExe(version) {
  const buf = Buffer.alloc(0x400);
  buf.write('MZ', 0, 'latin1');
  buf.writeUInt32LE(0x80, 0x3c);
  buf.writeUInt32LE(0x4550, 0x80);
  buf.writeUInt16LE(1, 0x86); // une section
  buf.writeUInt16LE(0, 0x94); // pas d'en-tête optionnel
  buf.write('.rsrc', 0x98, 'latin1');
  buf.writeUInt32LE(0x200, 0x98 + 16);
  buf.writeUInt32LE(0x200, 0x98 + 20);
  const key = Buffer.from('ProductVersion\0', 'utf16le');
  key.copy(buf, 0x210);
  Buffer.from(`${version}\0`, 'utf16le').copy(buf, 0x210 + key.length + 2);
  return buf;
}

const reader = (buf) => (pos, len) => buf.subarray(pos, pos + len);

test('version dans le nom du dossier et nettoyée', () => {
  assert.equal(versionFromName('Rock.Bottom.v1.0.3-TENOKE'), '1.0.3');
  assert.equal(versionFromName('Jeu v1.2-GOG'), '1.2');
  assert.equal(versionFromName('Jeu.Build.12345678-TENOKE'), 'build 12345678');
  assert.equal(versionFromName('Jeu (1.0.4)'), '1.0.4');
  assert.equal(versionFromName('Hades v1.03'), '1.03');
  for (const name of ['Half-Life', 'Frostpunk 2', 'Mr. Robot', 'Version Française']) assert.equal(versionFromName(name), null);
  assert.equal(cleanVersion('1.2.0.0'), '1.2');
  assert.equal(cleanVersion('1, 0, 3, 0'), '1.0.3');
  assert.equal(cleanVersion('1.0.0.0'), null);
  assert.equal(cleanVersion('0.0.0.0'), null);
  assert.equal(cleanVersion('1.4.2 (build 55)'), '1.4.2 (build 55)');
});

test('version lue dans l’exécutable ; jeux Unity et Unreal : version du moteur ignorée', () => {
  assert.equal(exeVersionFrom(reader(fakeExe('1.4.2'))), '1.4.2');
  assert.equal(exeVersionFrom(reader(fakeExe('1.0.0.0'))), null);
  assert.equal(exeVersionFrom(reader(Buffer.from('pas un exécutable'))), null);
  assert.equal(isEngineExe([{ path: 'Game.exe' }, { path: 'UnityPlayer.dll' }], 'Game.exe'), true);
  assert.equal(isEngineExe([{ path: 'Game.exe' }, { path: 'Game/Binaries/Win64/Game-Win64-Shipping.exe' }], 'Game/Binaries/Win64/Game-Win64-Shipping.exe'), true);
  assert.equal(isEngineExe([{ path: 'Game.exe' }, { path: 'data.pak' }], 'Game.exe'), false);
});

test('jeu PC : version devinée, choisie, empreinte des fichiers ; nouvelle version qui garde la fiche', async () => {
  createSystem({ id: 'windows', name: 'PC Windows', folderGames: true });
  const root = path.join(config.romsDir, 'windows');
  const write = (rel, data) => {
    fs.mkdirSync(path.dirname(path.join(root, rel)), { recursive: true });
    fs.writeFileSync(path.join(root, rel), data);
  };
  write('Jeu v1.2-GOG/Jeu.exe', fakeExe('9.9'));
  write('Jeu v1.2-GOG/data.pak', 'ancien');
  write('Autre/Autre.exe', fakeExe('2.5.0.0'));
  const { added } = scanSystem('windows');
  await detectVersions();
  const [jeu, autre] = added.map((id) => gameWithParts(id)).sort((a, b) => a.fileName.localeCompare(b.fileName)).reverse();
  assert.equal(jeu.version, '1.2'); // nom du dossier avant l'exécutable
  assert.equal(autre.version, '2.5');
  assert.equal(jeu.versionChosen, false);
  assert.match(jeu.contentStamp, /^\d+-\d+$/);
  const listing = gameFolderFiles(getGameRow(jeu.id));
  assert.ok(listing.files.every((f) => Number.isInteger(f.mtime)));
  assert.equal(listing.contentStamp, jeu.contentStamp);

  // Version choisie : gardée ; vide : devinée de nouveau.
  assert.equal(updateGame(jeu.id, { version: '1.2 Deluxe' }).version, '1.2 Deluxe');
  await detectVersions();
  assert.equal(gameWithParts(jeu.id).versionChosen, true);
  assert.equal(gameWithParts(jeu.id).version, '1.2 Deluxe');
  updateGame(jeu.id, { version: '' });
  await detectVersions();
  assert.equal(gameWithParts(jeu.id).version, '1.2');

  // Nouvelle version ajoutée à côté puis choisie : le jeu garde son identifiant et sa fiche.
  updateGame(jeu.id, { description: 'Mon résumé' });
  write('Jeu v1.3-GOG/Jeu.exe', fakeExe('9.9'));
  write('Jeu v1.3-GOG/data.pak', 'nouvelle version');
  const nouveau = scanSystem('windows').added[0];
  const updated = replaceWithVersion(jeu.id, nouveau);
  await detectVersions();
  assert.equal(updated.id, jeu.id);
  assert.equal(updated.fileName, 'Jeu v1.3-GOG');
  assert.equal(updated.description, 'Mon résumé');
  assert.equal(getGameRow(nouveau), undefined);
  assert.equal(fs.existsSync(path.join(root, 'Jeu v1.2-GOG')), false);
  assert.equal(gameWithParts(jeu.id).version, '1.3');
  assert.notEqual(gameWithParts(jeu.id).contentStamp, jeu.contentStamp);
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM games WHERE system_id = ?').get('windows').n, 2);
  assert.throws(() => replaceWithVersion(jeu.id, jeu.id), /invalidNewVersion/);
});
