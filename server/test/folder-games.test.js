import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-folder-games-test-${process.pid}`);
delete process.env.API_KEY;
delete process.env.ADMIN_KEY;
const { default: express } = await import('express');
const { api } = await import('../src/api.js');
const { config } = await import('../src/config.js');
const { exeCandidates, fileInFolder } = await import('../src/folder-games.js');

const write = (file, bytes) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, Buffer.alloc(bytes, 1));
};

test('exécutable deviné : hors installation et prérequis, le moins profond puis le plus gros', () => {
  const files = [
    { path: 'unins000.exe', size: 900 },
    { path: 'Redist/vc_redist.x64.exe', size: 9000 },
    { path: 'Bin/Game.exe', size: 5000 },
    { path: 'Launcher.exe', size: 300 },
    { path: 'Game.exe', size: 800 },
    { path: 'data.pak', size: 99999 },
  ];
  assert.deepEqual(exeCandidates(files), ['Game.exe', 'Launcher.exe', 'Bin/Game.exe', 'unins000.exe', 'Redist/vc_redist.x64.exe']);
});

test('chemin hors du dossier du jeu refusé', () => {
  const dir = path.join(os.tmpdir(), 'jeu');
  assert.equal(fileInFolder(dir, 'Bin/Game.exe'), path.join(dir, 'Bin', 'Game.exe'));
  for (const bad of ['../secret.txt', 'Bin/../../x', '/etc/passwd', 'C:/Windows/win.ini', '', 'a//b']) {
    assert.throws(() => fileInFolder(dir, bad), /invalidFilePath/);
  }
});

test('système PC : un jeu par dossier, exécutable choisi, fichiers listés et téléchargés un par un', async () => {
  const app = express();
  app.use(express.json());
  app.use('/api', api);
  const server = app.listen(0);
  const base = `http://127.0.0.1:${server.address().port}/api`;
  const json = (url, options = {}) =>
    fetch(`${base}${url}`, { ...options, headers: { 'Content-Type': 'application/json' }, body: options.body && JSON.stringify(options.body) });
  try {
    const dir = path.join(config.romsDir, 'windows');
    write(path.join(dir, 'Mr. Robot', 'Game.exe'), 100);
    write(path.join(dir, 'Mr. Robot', 'Tools', 'Editor.exe'), 400);
    write(path.join(dir, 'Mr. Robot', 'data', 'level1.pak'), 1000);
    write(path.join(dir, 'Mr. Robot', 'desktop.ini'), 10);
    write(path.join(dir, 'Mr. Robot', 'unins000.exe'), 50);
    fs.mkdirSync(path.join(dir, 'Vide'), { recursive: true });
    write(path.join(dir, 'fichier-isole.zip'), 50);

    const created = await json('/systems', { method: 'POST', body: { id: 'windows', name: 'PC' } /* « windows » : jeux en dossiers par défaut */ });
    assert.equal(created.status, 201);
    assert.equal((await created.json()).folderGames, true);

    const games = await (await fetch(`${base}/systems/windows/games`)).json();
    assert.equal(games.length, 1); // dossier vide et fichier isolé ignorés
    const game = games[0];
    assert.equal(game.title, 'Mr. Robot'); // pas d'« extension » retirée
    assert.equal(game.folder, true);
    assert.equal(game.exe, 'Game.exe');
    assert.equal(game.uninstallExe, 'unins000.exe'); // désinstalleur deviné
    assert.equal(game.size, 1550);

    const listing = await (await fetch(`${base}/games/${game.id}/files`)).json();
    assert.deepEqual(listing.files.map((f) => f.path), ['data/level1.pak', 'Game.exe', 'Tools/Editor.exe', 'unins000.exe']);
    assert.deepEqual(listing.exes, ['Game.exe', 'Tools/Editor.exe', 'unins000.exe']);

    // Un fichier du dossier, avec reprise (Range) ; sans chemin ou hors du dossier : refusé.
    const part = await fetch(`${base}/games/${game.id}/file?path=${encodeURIComponent('data/level1.pak')}`, { headers: { Range: 'bytes=600-' } });
    assert.equal(part.status, 206);
    assert.equal((await part.arrayBuffer()).byteLength, 400);
    assert.equal((await fetch(`${base}/games/${game.id}/file`)).status, 400);
    assert.equal((await fetch(`${base}/games/${game.id}/file?path=..%2F..%2Fsecret`)).status, 400);

    // Exécutable choisi dans la fiche : gardé au scan suivant ; inconnu : refusé.
    assert.equal((await (await json(`/games/${game.id}`, { method: 'PUT', body: { exe: 'Tools/Editor.exe' } })).json()).exe, 'Tools/Editor.exe');
    assert.equal((await json(`/games/${game.id}`, { method: 'PUT', body: { exe: 'Absent.exe' } })).status, 400);
    // Désinstalleur « aucun » choisi : pas deviné de nouveau au scan.
    assert.equal((await (await json(`/games/${game.id}`, { method: 'PUT', body: { uninstallExe: '' } })).json()).uninstallExe, null);
    await fetch(`${base}/systems/windows/scan`, { method: 'POST' });
    const rescanned = await (await fetch(`${base}/games/${game.id}`)).json();
    assert.equal(rescanned.exe, 'Tools/Editor.exe');
    assert.equal(rescanned.uninstallExe, null);

    // Jeux en dossiers coupés : fiches refaites à partir des fichiers.
    const off = await (await json('/systems/windows', { method: 'PUT', body: { folderGames: false } })).json();
    assert.equal(off.folderGames, false);
    const files = await (await fetch(`${base}/systems/windows/games`)).json();
    assert.deepEqual(files.map((g) => g.fileName), ['fichier-isole.zip']);
  } finally {
    server.close();
  }
});
