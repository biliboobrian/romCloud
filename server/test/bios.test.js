import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';

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
  pcsx2: [
    'firmware_count = 2',
    'firmware0_desc = "\'pcsx2/bios\' folder (any valid PS2 BIOS dump, e.g. scph39001.bin)"',
    'firmware0_path = "pcsx2/bios"',
    'firmware0_opt = "false"',
    'firmware1_desc = "pcsx2/resources/GameIndex.yaml (Game Database)"',
    'firmware1_path = "pcsx2/resources/GameIndex.yaml"',
    'firmware1_opt = "true"',
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
// Fichiers des sources Internet simulées (firmware…) : /files/<nom>.
const FILES = { 'PS3UPDAT.PUP': 'FIRMWARE-4.93' };
const server = http.createServer((req, res) => {
  if (req.url.startsWith('/files/')) {
    const body = FILES[path.basename(req.url)];
    if (body) res.end(body);
    else res.writeHead(404).end();
    return;
  }
  requests++;
  const core = path.basename(req.url).replace(/_libretro\.info$/, '');
  if (INFOS[core]) res.end(INFOS[core]);
  else res.writeHead(404).end();
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
process.env.LIBRETRO_CORE_INFO_URL = `http://127.0.0.1:${server.address().port}/`;

const bios = await import('../src/bios.js');
const systemFiles = await import('../src/system-files.js');
// Pas de requête vers les vraies sources pendant les tests.
for (const source of Object.values(systemFiles.SOURCES)) source.latest = async () => null;
const filesUrl = (name) => `http://127.0.0.1:${server.address().port}/files/${name}`;
const { createSystem, getSystem } = await import('../src/systems.js');
const { db } = await import('../src/db.js');

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
    folder: false,
  });
  assert.equal(bios.parseCoreInfo(INFOS.mednafen_psx_hw).firmware[0].required, true);
  const ps2 = bios.parseCoreInfo(INFOS.pcsx2).firmware;
  assert.deepEqual(ps2.map((f) => [f.path, f.folder]), [['pcsx2/bios', true], ['pcsx2/resources/GameIndex.yaml', false]]);
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
  // Empreintes acceptées : MD5 de référence libretro, puis MD5 et SHA1 de bios-hashes.json (sans doublon).
  assert.deepEqual(empty.expected[0].md5s, ['490f666e1afb15b7362b406ed1cea246']);
  assert.deepEqual(empty.expected[0].sha1s, ['0555c6fae8906f3f09baf5988f00e55f88e9f30b']);
  assert.equal(empty.expected[0].md5, '490f666e1afb15b7362b406ed1cea246');

  const upload = (name, content) => {
    const temp = path.join(dataDir, `upload-${crypto.randomUUID()}`);
    fs.writeFileSync(temp, content);
    return { tempPath: temp, originalName: name };
  };
  const sha1 = (content) => crypto.createHash('sha1').update(content).digest('hex');
  const refused = async (promise, path) => {
    await assert.rejects(promise, (err) => err.status === 422 && err.key === 'errors.biosHashMismatch' && err.vars.path === path);
  };

  // Empreinte connue mais différente : tout l'envoi est refusé, rien n'est enregistré.
  await refused(bios.addBiosFiles('dc', [upload('extra.bin', 'x'), upload('dc_boot.bin', 'boot')]), 'dc/dc_boot.bin');
  await refused(bios.addBiosFiles('dc', [upload('whatever.rom', 'psx')], 'scph5501.bin'), 'scph5501.bin');
  assert.equal(getSystem('dc').biosCount, 0);

  // Empreintes de l'utilisateur : SHA1 dans DATA_DIR/bios-hashes.json, MD5 dans l'ancien DATA_DIR/bios-md5.json.
  fs.writeFileSync(path.join(dataDir, 'bios-hashes.json'), JSON.stringify({ _comment: 'test', 'dc_boot.bin': [sha1('boot')] }));
  fs.writeFileSync(path.join(dataDir, 'bios-md5.json'), JSON.stringify({ 'EXTRA.bin': '9dd4e461268c8034f5c8564e155c67a6' }));
  // Un fichier « dc_boot.bin » envoyé seul est rangé dans dc/ comme l'attend le cœur ; sans empreinte connue, accepté.
  const saved = await bios.addBiosFiles('dc', [upload('dc_boot.bin', 'boot'), upload('extra.bin', 'x'), upload('other.bin', 'o')]);
  assert.deepEqual(saved, ['dc/dc_boot.bin', 'extra.bin', 'other.bin']);
  const state = await bios.systemBios('dc');
  assert.deepEqual(state.files.map((f) => [f.path, f.size, f.hashStatus]), [
    ['dc/dc_boot.bin', 4, 'ok'],
    ['extra.bin', 1, 'ok'],
    ['other.bin', 1, 'unknown'],
  ]);
  assert.equal(state.files[0].sha1, sha1('boot'));
  assert.deepEqual(state.expected.map((e) => e.present), [false, true, false]);
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

/** Archive .zip minimale (fichiers stockés sans compression). */
function makeZip(files) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, content] of files) {
    const data = Buffer.from(content);
    const nameBuf = Buffer.from(name);
    const crc = zlib.crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(offset, 42);
    locals.push(local, nameBuf, data);
    centrals.push(central, nameBuf);
    offset += 30 + nameBuf.length + data.length;
  }
  const dir = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(files.length, 8);
  eocd.writeUInt16LE(files.length, 10);
  eocd.writeUInt32LE(dir.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, dir, eocd]);
}

test('dossier attendu (PCSX2) : .zip extrait dans le dossier, ancien .zip réparé', async () => {
  createSystem({ id: 'ps2', name: 'PlayStation 2', players: [retroarch('pcsx2')] });
  const upload = (name, content) => {
    const temp = path.join(dataDir, `upload-${crypto.randomUUID()}`);
    fs.writeFileSync(temp, content);
    return { tempPath: temp, originalName: name };
  };
  // Archive du dossier lui-même (« bios/… ») : ce niveau est retiré.
  const saved = await bios.addBiosFiles('ps2', [upload('bios.zip', makeZip([['bios/scph39001.bin', 'BIOS'], ['bios/scph70004.bin', 'BIOS2']]))], 'pcsx2/bios');
  assert.deepEqual(saved, ['pcsx2/bios/scph39001.bin', 'pcsx2/bios/scph70004.bin']);
  let state = await bios.systemBios('ps2');
  const folder = state.expected.find((e) => e.path === 'pcsx2/bios');
  assert.equal(folder.present, true);
  assert.equal(folder.fileCount, 2);
  assert.equal(fs.readFileSync(path.join(dataDir, 'bios', 'ps2', 'pcsx2', 'bios', 'scph39001.bin'), 'utf8'), 'BIOS');

  // Fichier seul envoyé pour le dossier : rangé dedans sous son nom.
  assert.deepEqual(await bios.addBiosFiles('ps2', [upload('scph10000.bin', 'B3')], 'pcsx2/bios'), ['pcsx2/bios/scph10000.bin']);

  // Ancien envoi : .zip enregistré tel quel sous « pcsx2/resources » -> extrait au prochain affichage.
  const legacy = path.join(dataDir, 'bios', 'ps2', 'pcsx2', 'resources');
  fs.writeFileSync(legacy, makeZip([['shaders/a.glsl', 'void main(){}'], ['GameIndex.yaml', 'x: 1']]));
  db.prepare("INSERT INTO bios (system_id, path, size, md5) VALUES ('ps2', 'pcsx2/resources', 1, 'x')").run();
  state = await bios.systemBios('ps2', { catalog: false });
  assert.deepEqual(state.files.map((f) => f.path), [
    'pcsx2/bios/scph10000.bin',
    'pcsx2/bios/scph39001.bin',
    'pcsx2/bios/scph70004.bin',
    'pcsx2/resources/GameIndex.yaml',
    'pcsx2/resources/shaders/a.glsl',
  ]);
  assert.equal(fs.readFileSync(path.join(legacy, 'shaders', 'a.glsl'), 'utf8'), 'void main(){}');
});

test('listes de mise à jour officielles : PS3 et PS Vita', () => {
  const ps3 = [
    '# EU',
    'Dest=85;CompatibleSystemSoftwareVersion=4.9300-;',
    'Dest=85;IncrementalUpdateVersion=00010b72-00010b72;ImageVersion=00010b94;SystemSoftwareVersion=4.9300;CDN=http://x/PS3PATCH.PUP;CDN_Timeout=30;',
    'Dest=85;ImageVersion=00010b94;SystemSoftwareVersion=4.9300;CDN=http://x/eu/PS3UPDAT.PUP;CDN_Timeout=30;',
  ].join('\n');
  assert.deepEqual(systemFiles.parsePs3UpdateList(ps3), { version: '4.93', url: 'http://x/eu/PS3UPDAT.PUP' });
  const vita = `<update_data_list><region id="eu">
    <version system_version="03.740.000" label="3.74">
    <update_data update_type="full"><image size="133834240">http://x/rel/PSP2UPDAT.PUP?dest=eu</image></update_data></version>
    <recovery spkg_type="systemdata"><image spkg_version="01.000.010" size="56778752">http://x/sd/PSP2UPDAT.PUP?dest=eu</image></recovery>
    <recovery spkg_type="preinst"><image spkg_version="01.000.000" size="1">http://x/pre/PSP2UPDAT.PUP</image></recovery>
  </region></update_data_list>`;
  assert.deepEqual(systemFiles.parseVitaUpdateList(vita, 'full'), { version: '3.74', url: 'http://x/rel/PSP2UPDAT.PUP?dest=eu', size: 133834240 });
  assert.deepEqual(systemFiles.parseVitaUpdateList(vita, 'systemdata'), { version: '01.000.010', url: 'http://x/sd/PSP2UPDAT.PUP?dest=eu', size: 56778752 });
});

test('clés et firmware de la Switch attendus, sans source Internet', async () => {
  createSystem({ id: 'switch', name: 'Nintendo Switch', players: [] });
  const state = await bios.systemBios('switch', { language: 'fr' });
  const keys = state.expected.find((e) => e.path === 'switch/prod.keys');
  assert.equal(keys.kind, 'keys');
  assert.ok(keys.required && !keys.present && !keys.source);
  assert.match(keys.description, /Switch/);
  assert.ok(!state.expected.find((e) => e.path === 'switch/firmware.zip').folder); // gardé en .zip (Eden pour Android)
  // Envoi : rangé sous le chemin attendu d'après son nom.
  const dir = path.join(dataDir, 'upload-test');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'k'), 'prod_key = 0123');
  assert.deepEqual(await bios.addBiosFiles('switch', [{ tempPath: path.join(dir, 'k'), originalName: 'prod.keys' }]), ['switch/prod.keys']);
  assert.ok((await bios.systemBios('switch')).expected.find((e) => e.path === 'switch/prod.keys').present);
});

test('firmware récupéré sur sa source : dernière version, mise à jour signalée, téléchargement incomplet refusé', async () => {
  createSystem({ id: 'ps3', name: 'PlayStation 3', players: [] });
  systemFiles.clearLatestCache();
  systemFiles.SOURCES['sony-ps3'].latest = async () => ({ version: '4.93', url: filesUrl('PS3UPDAT.PUP') });
  let entry = (await bios.systemBios('ps3')).expected.find((e) => e.path === 'ps3/PS3UPDAT.PUP');
  assert.equal(entry.kind, 'firmware');
  assert.deepEqual([entry.present, entry.source.latest, entry.source.installed, entry.source.updateAvailable], [false, '4.93', null, true]);

  assert.deepEqual(await bios.fetchBiosFromSource('ps3', 'ps3/PS3UPDAT.PUP'), { path: 'ps3/PS3UPDAT.PUP', version: '4.93' });
  assert.equal(fs.readFileSync(path.join(dataDir, 'bios', 'ps3', 'ps3', 'PS3UPDAT.PUP'), 'utf8'), 'FIRMWARE-4.93');
  entry = (await bios.systemBios('ps3')).expected.find((e) => e.path === 'ps3/PS3UPDAT.PUP');
  assert.deepEqual([entry.present, entry.source.installed, entry.source.updateAvailable], [true, '4.93', false]);

  // Nouvelle version publiée : mise à jour proposée ; taille annoncée différente : refusée, ancien fichier gardé.
  systemFiles.clearLatestCache();
  systemFiles.SOURCES['sony-ps3'].latest = async () => ({ version: '4.94', url: filesUrl('PS3UPDAT.PUP'), size: 999 });
  assert.ok((await bios.systemBios('ps3')).expected.find((e) => e.path === 'ps3/PS3UPDAT.PUP').source.updateAvailable);
  await assert.rejects(bios.fetchBiosFromSource('ps3', 'ps3/PS3UPDAT.PUP'), (err) => err.status === 502);
  assert.equal(fs.readFileSync(path.join(dataDir, 'bios', 'ps3', 'ps3', 'PS3UPDAT.PUP'), 'utf8'), 'FIRMWARE-4.93');
  // Fichier sans source : refusé.
  await assert.rejects(bios.fetchBiosFromSource('switch', 'switch/prod.keys'), (err) => err.status === 404);
});
