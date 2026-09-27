import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';

const dataDir = path.join(os.tmpdir(), `romcloud-bios-test-${process.pid}`);
process.env.DATA_DIR = dataDir;

// Faux dépôt libretro-core-info : flycast (BIOS facultatifs dans dc/) et mednafen_psx_hw.
const INFOS = {
  flycast: [
    'display_name = "Sega - Dreamcast/NAOMI (Flycast)"',
    'firmware_count = 2',
    'firmware0_desc = "dc/dc_boot.bin (Dreamcast BIOS)"',
    'firmware0_path = "dc/dc_boot.bin"',
    'firmware0_opt = "true"',
    'firmware1_desc = "dc/dc_flash.bin (Date/Time/Language)"',
    'firmware1_path = "dc/dc_flash.bin"',
    'firmware1_opt = "true"',
    'notes = "(!) dc_boot.bin (md5): e10c53c2f8b90bab96ead2d368858623|(!) dc_flash.bin (md5): 0a93f7940c455905bea6e392dfde92a4"',
  ].join('\n'),
  mednafen_psx_hw: [
    'firmware_count = 1',
    'firmware0_desc = "scph5501.bin (PS1 US BIOS)"',
    'firmware0_path = "scph5501.bin"',
    'firmware0_opt = "false"',
    'notes = "(!) scph5501.bin (md5): 490f666e1afb15b7362b406ed1cea246"',
  ].join('\n'),
};
let requests = 0;
const server = http.createServer((req, res) => {
  requests++;
  const core = path.basename(req.url).replace(/_libretro\.info$/, '');
  if (INFOS[core]) res.end(INFOS[core]);
  else res.writeHead(404).end();
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
process.env.LIBRETRO_CORE_INFO_URL = `http://127.0.0.1:${server.address().port}/`;

const bios = await import('../src/bios.js');
const { createSystem, getSystem } = await import('../src/systems.js');

after(() => {
  server.close();
  try {
    fs.rmSync(dataDir, { recursive: true, force: true });
  } catch {
    // Base SQLite encore ouverte sous Windows : le dossier temporaire sera nettoyé plus tard.
  }
});

const retroarch = (core) => ({
  name: `RetroArch ${core}`,
  amStartArguments: `-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n -e ROM {file.path}\n -e LIBRETRO /data/data/com.retroarch.aarch64/cores/${core}_libretro_android.so\n`,
});

test('fiche de cœur : BIOS, caractère obligatoire et MD5', () => {
  const info = bios.parseCoreInfo(INFOS.flycast);
  assert.equal(info.name, 'Sega - Dreamcast/NAOMI (Flycast)');
  assert.deepEqual(info.firmware[0], {
    path: 'dc/dc_boot.bin',
    description: 'dc/dc_boot.bin (Dreamcast BIOS)',
    required: false,
    md5: 'e10c53c2f8b90bab96ead2d368858623',
  });
  assert.equal(bios.parseCoreInfo(INFOS.mednafen_psx_hw).firmware[0].required, true);
  assert.deepEqual(bios.parseCoreInfo('display_name = "x"').firmware, []);
});

test('cœurs d’un système et chemins de BIOS sûrs', () => {
  const system = { players: [retroarch('flycast'), retroarch('flycast'), { amStartArguments: '-n x/.Y' }] };
  assert.deepEqual(bios.coresOfSystem(system), ['flycast']);
  assert.equal(bios.safeBiosPath('dc\\dc_boot.bin'), 'dc/dc_boot.bin');
  assert.equal(bios.safeBiosPath('/scph5501.bin'), 'scph5501.bin');
  for (const bad of ['', '../x.bin', 'dc/../../x', 'a//b', 'c:x']) assert.throws(() => bios.safeBiosPath(bad));
});

test('BIOS attendus, envoi, contrôle MD5, téléchargement et suppression', async () => {
  createSystem({ id: 'dc', name: 'Dreamcast', players: [retroarch('flycast_gles2'), retroarch('mednafen_psx_hw')] });

  const empty = await bios.systemBios('dc');
  assert.equal(empty.coreInfoAvailable, true);
  // flycast_gles2 n'a pas de fiche : on se rabat sur flycast. Les obligatoires d'abord.
  assert.deepEqual(empty.expected.map((e) => [e.path, e.required, e.present]), [
    ['scph5501.bin', true, false],
    ['dc/dc_boot.bin', false, false],
    ['dc/dc_flash.bin', false, false],
  ]);
  assert.deepEqual(empty.expected[1].cores, ['flycast_gles2']);

  // Un fichier « dc_boot.bin » envoyé seul est rangé dans dc/ comme l'attend le cœur.
  const upload = (name, content) => {
    const temp = path.join(dataDir, `upload-${crypto.randomUUID()}`);
    fs.writeFileSync(temp, content);
    return { tempPath: temp, originalName: name };
  };
  const saved = await bios.addBiosFiles('dc', [upload('dc_boot.bin', 'boot'), upload('extra.bin', 'x')]);
  assert.deepEqual(saved, ['dc/dc_boot.bin', 'extra.bin']);
  assert.deepEqual(await bios.addBiosFiles('dc', [upload('whatever.rom', 'psx')], 'scph5501.bin'), ['scph5501.bin']);

  const state = await bios.systemBios('dc');
  assert.deepEqual(state.files.map((f) => [f.path, f.size, f.md5Status]), [
    ['dc/dc_boot.bin', 4, 'mismatch'],
    ['extra.bin', 1, 'unknown'],
    ['scph5501.bin', 3, 'mismatch'],
  ]);
  assert.deepEqual(state.expected.map((e) => e.present), [true, true, false]);
  assert.equal(getSystem('dc').biosCount, 3);

  const boot = state.files[0];
  const file = bios.biosFilePath(bios.requireBios(boot.id));
  assert.equal(file, path.join(dataDir, 'bios', 'dc', 'dc', 'dc_boot.bin'));
  assert.equal(fs.readFileSync(file, 'utf8'), 'boot');

  // Sans catalogue : aucune requête vers libretro.
  const before = requests;
  const light = await bios.systemBios('dc', { catalog: false });
  assert.equal(requests, before);
  assert.deepEqual(light.expected, []);
  assert.equal(light.files.length, 3);

  bios.deleteBios(boot.id);
  assert.equal(fs.existsSync(file), false);
  assert.equal(getSystem('dc').biosCount, 2);
  assert.throws(() => bios.requireBios(boot.id));
});
