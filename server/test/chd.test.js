import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-chd-test-${process.pid}`);
delete process.env.API_KEY;
delete process.env.ADMIN_KEY;
const { default: express } = await import('express');
const { api } = await import('../src/api.js');
const { config } = await import('../src/config.js');
const { discImage, setChdman, trackFiles } = await import('../src/chd.js');
const { chdDisc } = await import('../src/chd-systems.js');
const { ZipWriter } = await import('../src/zip.js');

// chdman imité : écrit dans le fichier de sortie la commande, puis le contenu de l'image et de ses pistes.
const fake = path.join(process.env.DATA_DIR, 'fake-chdman.mjs');
fs.mkdirSync(process.env.DATA_DIR, { recursive: true });
fs.writeFileSync(
  fake,
  `import fs from 'node:fs';
import path from 'node:path';
const [command, ...rest] = process.argv.slice(2);
if (command === 'help') process.exit(0);
const arg = (name) => rest[rest.indexOf(name) + 1];
const input = arg('-i');
let content = 'CHD ' + command + '\\n' + fs.readFileSync(input, 'latin1');
for (const m of fs.readFileSync(input, 'latin1').matchAll(/FILE "([^"]+)"/g)) content += fs.readFileSync(path.join(path.dirname(input), m[1]), 'latin1');
process.stderr.write('Compressing, 50.0% complete... (ratio=40.0%)\\r');
if (content.includes('PLANTE')) { process.stderr.write('\\nError: corrupt track\\n'); process.exit(1); }
fs.writeFileSync(arg('-o'), content);
process.stderr.write('Compression complete ... final ratio = 40.0%\\n');
`,
);
setChdman(process.execPath, [fake]);

const write = (file, data) => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
};

/** Archive .zip (fichiers stockés) des fichiers { nom: contenu }. */
function zip(file, files) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const writer = new ZipWriter(file);
  for (const [name, data] of Object.entries(files)) {
    const buf = Buffer.from(data);
    const entry = writer.beginFile(name, buf.length);
    writer.writeData(entry, buf);
    writer.endFile(entry);
  }
  writer.close();
}

test('pistes citées par les images de disque, image choisie dans une archive', () => {
  const cue = 'FILE "Game (Track 1).bin" BINARY\n  TRACK 01 MODE2/2352\nFILE "Game (Track 2).bin" BINARY\n  TRACK 02 AUDIO\nFILE plain.bin BINARY\n';
  assert.deepEqual(trackFiles(cue, 'cue'), ['Game (Track 1).bin', 'Game (Track 2).bin', 'plain.bin']);
  const gdi = '3\n1 0 4 2352 track01.bin 0\n2 600 0 2352 "track 02.raw" 0\n3 45000 4 2352 track03.bin 0\n';
  assert.deepEqual(trackFiles(gdi, 'gdi'), ['track01.bin', 'track 02.raw', 'track03.bin']);
  assert.equal(discImage(['Game/Game.bin', 'Game/Game.cue', 'readme.iso']), 'Game/Game.cue');
  assert.equal(discImage(['Game.iso']), 'Game.iso');
  assert.equal(discImage(['Game.bin']), null);
  assert.throws(() => discImage(['Disc 1.cue', 'Disc 2.cue']), /chdSeveralDiscs/);
  assert.equal(chdDisc({ id: 'psx' }), 'cd');
  assert.equal(chdDisc({ id: 'ps2' }), 'dvd');
  assert.equal(chdDisc({ id: 'custom', shortname: 'saturn' }), 'cd');
  assert.equal(chdDisc({ id: 'snes' }), null);
  assert.equal(chdDisc({ id: 'psx', folderGames: true }), null);
});

test('jeux convertis en CHD : .cue et pistes, archives .zip de plusieurs disques, .iso de la PS2 en DVD ; fiche gardée', async () => {
  const app = express();
  app.use(express.json());
  app.use('/api', api);
  const server = app.listen(0);
  const base = `http://127.0.0.1:${server.address().port}/api`;
  const json = (url, options = {}) =>
    fetch(`${base}${url}`, { ...options, headers: { 'Content-Type': 'application/json' }, body: options.body && JSON.stringify(options.body) });
  const waitJobs = async () => {
    for (let i = 0; i < 400; i++) {
      const jobs = await (await fetch(`${base}/jobs`)).json();
      if (!jobs.some((j) => j.status === 'queued' || j.status === 'running')) return jobs;
      await new Promise((r) => setTimeout(r, 25));
    }
    throw new Error('tâche trop longue');
  };
  const gamesOf = async (system) => (await (await fetch(`${base}/systems/${system}/games`)).json());
  try {
    const psx = path.join(config.romsDir, 'psx');
    write(path.join(psx, 'Tekken (USA).cue'), 'FILE "Tekken (USA) (Track 1).bin" BINARY\nFILE "Tekken (USA) (Track 2).bin" BINARY\n');
    write(path.join(psx, 'Tekken (USA) (Track 1).bin'), 'piste1');
    write(path.join(psx, 'Tekken (USA) (Track 2).bin'), 'piste2');
    zip(path.join(psx, 'Lunar (USA) (Disc 1).zip'), { 'Lunar (USA) (Disc 1).cue': 'FILE "Lunar 1.bin" BINARY\n', 'Lunar 1.bin': 'disque1' });
    zip(path.join(psx, 'Lunar (USA) (Disc 2).zip'), { 'Lunar (USA) (Disc 2).cue': 'FILE "Lunar 2.bin" BINARY\n', 'Lunar 2.bin': 'disque2' });
    write(path.join(psx, 'Broken (USA).cue'), 'FILE "absent.bin" BINARY\n');
    write(path.join(psx, 'Crash (USA).cue'), 'FILE "Crash.bin" BINARY\n');
    write(path.join(psx, 'Crash.bin'), 'PLANTE');
    const created = await json('/systems', { method: 'POST', body: { id: 'psx', name: 'PlayStation', shortname: 'psx', filenameRegex: '^(?!(?:\\._|\\.).*).*(?<!bin)$' } });
    assert.equal(created.status, 201);
    await json('/systems/psx/scan', { method: 'POST' });
    const systems = await (await fetch(`${base}/systems`)).json();
    assert.equal(systems.find((s) => s.id === 'psx').chd, 'cd');

    let games = await gamesOf('psx');
    const tekken = games.find((g) => g.fileName === 'Tekken (USA).cue');
    const lunar = games.find((g) => g.fileName === 'Lunar (USA) (Disc 1).zip');
    assert.deepEqual(lunar.parts.map((p) => p.fileName), ['Lunar (USA) (Disc 2).zip']);
    await json(`/games/${tekken.id}`, { method: 'PUT', body: { title: 'Tekken (titre modifié)' } });

    // Piste absente : refusé avant la tâche.
    const broken = games.find((g) => g.fileName === 'Broken (USA).cue');
    const refused = await json(`/games/${broken.id}/chd`, { method: 'POST' });
    assert.equal(refused.status, 400);
    assert.equal((await refused.json()).code, 'errors.chdTrackMissing');

    assert.equal((await json(`/games/${tekken.id}/chd`, { method: 'POST' })).status, 202);
    assert.equal((await json(`/games/${lunar.id}/chd`, { method: 'POST' })).status, 202);
    const crash = games.find((g) => g.fileName === 'Crash (USA).cue');
    assert.equal((await json(`/games/${crash.id}/chd`, { method: 'POST' })).status, 202);
    const jobs = await waitJobs();
    assert.deepEqual(jobs.filter((j) => j.type === 'convert').map((j) => j.status).sort(), ['done', 'done', 'failed']);
    assert.match(jobs.find((j) => j.status === 'failed').lastError, /chdman : .*corrupt track/);

    // Tekken : un seul .chd (image et pistes), originaux supprimés, même fiche (titre, favori).
    assert.equal(fs.readFileSync(path.join(psx, 'Tekken (USA).chd'), 'latin1'), 'CHD createcd\nFILE "Tekken (USA) (Track 1).bin" BINARY\nFILE "Tekken (USA) (Track 2).bin" BINARY\npiste1piste2');
    for (const gone of ['Tekken (USA).cue', 'Tekken (USA) (Track 1).bin', 'Lunar (USA) (Disc 1).zip', 'Lunar (USA) (Disc 2).zip']) assert.equal(fs.existsSync(path.join(psx, gone)), false, gone);
    assert.ok(fs.readFileSync(path.join(psx, 'Lunar (USA) (Disc 2).chd'), 'latin1').endsWith('disque2'));
    games = await gamesOf('psx');
    const converted = games.find((g) => g.id === tekken.id);
    assert.equal(converted.fileName, 'Tekken (USA).chd');
    assert.equal(converted.title, 'Tekken (titre modifié)');
    assert.equal(converted.size, fs.statSync(path.join(psx, 'Tekken (USA).chd')).size);
    const lunarAfter = games.find((g) => g.id === lunar.id);
    assert.equal(lunarAfter.fileName, 'Lunar (USA) (Disc 1).chd');
    assert.deepEqual(lunarAfter.parts.map((p) => p.fileName), ['Lunar (USA) (Disc 2).chd']);
    // Échec : fichiers d'origine gardés, aucun fichier temporaire laissé.
    assert.ok(fs.existsSync(path.join(psx, 'Crash (USA).cue')) && fs.existsSync(path.join(psx, 'Crash.bin')));
    assert.deepEqual(fs.readdirSync(psx).filter((n) => n.startsWith('.')), []);
    // Déjà en CHD : rien à convertir.
    assert.equal((await (await json(`/games/${tekken.id}/chd`, { method: 'POST' })).json()).code, 'errors.chdNotConvertible');

    // PS2 : image .iso en DVD.
    write(path.join(config.romsDir, 'ps2', 'Okami (USA).iso'), 'image dvd');
    await json('/systems', { method: 'POST', body: { id: 'ps2', name: 'PlayStation 2', shortname: 'ps2' } });
    await json('/systems/ps2/scan', { method: 'POST' });
    const [okami] = await gamesOf('ps2');
    assert.equal((await json(`/games/${okami.id}/chd`, { method: 'POST' })).status, 202);
    await waitJobs();
    assert.equal(fs.readFileSync(path.join(config.romsDir, 'ps2', 'Okami (USA).chd'), 'latin1'), 'CHD createdvd\nimage dvd');

    // Système sans CHD : refusé.
    write(path.join(config.romsDir, 'snes', 'Mario.zip'), 'x');
    await json('/systems', { method: 'POST', body: { id: 'snes', name: 'Super Nintendo', shortname: 'snes' } });
    await json('/systems/snes/scan', { method: 'POST' });
    const [mario] = await gamesOf('snes');
    assert.equal((await (await json(`/games/${mario.id}/chd`, { method: 'POST' })).json()).code, 'errors.chdUnsupportedSystem');
  } finally {
    server.close();
  }
});
