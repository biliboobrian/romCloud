const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const catalog = require('../src/main/catalog');

test('catalogue : identifiants uniques, lien de téléchargement, exécutable, variable {file} ou lancement manuel', () => {
  const ids = catalog.EMULATORS.map((e) => e.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const e of catalog.EMULATORS) {
    assert.match(e.url, /^https:\/\//, e.id);
    assert.ok(e.exe.length && e.exe.every((x) => x.endsWith('.exe')), e.id);
    assert.ok(e.systems.length, e.id);
    if (e.args) assert.ok(e.args.some((a) => /\{(file|dir|basename)\}/.test(a)), e.id);
  }
});

test('émulateurs proposés selon le système (nom court Daijishou ou identifiant)', () => {
  const names = (system) => catalog.forSystem(system).map((e) => e.id);
  assert.deepEqual(names({ id: 'psx', shortname: 'psx' }), ['duckstation', 'mednafen']);
  assert.deepEqual(names({ id: 'gamecube', shortname: 'gc' }), ['dolphin']);
  assert.ok(names({ id: 'n64', shortname: 'n64' }).includes('project64'));
  assert.deepEqual(names({ id: 'vita', shortname: 'vita' }), ['vita3k']);
  assert.deepEqual(names({ id: 'custom', shortname: 'custom' }), []);
});

test('arguments et ligne de commande affichée', () => {
  const file = String.raw`D:\Jeux\gc\Super Mario Sunshine (Europe).rvz`;
  assert.deepEqual(catalog.buildArgs(catalog.byId('dolphin').args, file), ['-b', '-e', file]);
  assert.deepEqual(catalog.buildArgs(catalog.byId('mame').args, String.raw`D:\Jeux\mame\sf2.zip`), ['-rompath', String.raw`D:\Jeux\mame`, 'sf2']);
  assert.equal(
    catalog.commandLine(String.raw`C:\Program Files\Dolphin\Dolphin.exe`, ['-b', '-e', file]),
    String.raw`"C:\Program Files\Dolphin\Dolphin.exe" -b -e "D:\Jeux\gc\Super Mario Sunshine (Europe).rvz"`,
  );
  assert.equal(catalog.byId('vita3k').args, null);
});

test('recherche des émulateurs : Program Files, sous-dossiers des Téléchargements, nom insensible à la casse', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'romcloud-emu-'));
  try {
    const env = { ProgramFiles: path.join(root, 'pf'), 'ProgramFiles(x86)': path.join(root, 'pf86'), USERPROFILE: path.join(root, 'home') };
    const put = (...parts) => {
      const file = path.join(root, ...parts);
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, '');
      return file;
    };
    const pcsx2 = put('pf', 'PCSX2', 'pcsx2-qt.exe');
    const supermodel = put('home', 'Downloads', 'Supermodel_0.3a', 'bin', 'SUPERMODEL.EXE');
    put('home', 'Downloads', 'a', 'b', 'c', 'xemu.exe'); // trop profond
    assert.equal(catalog.defaultLocation(catalog.byId('pcsx2'), env), pcsx2);
    const found = catalog.detect(env, [catalog.byId('pcsx2'), catalog.byId('supermodel'), catalog.byId('xemu')]);
    assert.equal(found.pcsx2, pcsx2);
    assert.equal(found.supermodel.toLowerCase(), supermodel.toLowerCase());
    assert.equal(found.xemu, undefined);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
