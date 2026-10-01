const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const zlib = require('node:zlib');
const libretro = require('../src/main/libretro');
const zip = require('../src/main/zip');

/** Archive ZIP minimale : [{ name, data, deflate }]. */
function makeZip(files) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const f of files) {
    const name = Buffer.from(f.name);
    const body = f.deflate ? zlib.deflateRawSync(f.data) : f.data;
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(f.deflate ? 8 : 0, 8);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(f.data.length, 22);
    local.writeUInt16LE(name.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(f.deflate ? 8 : 0, 10);
    central.writeUInt32LE(body.length, 20);
    central.writeUInt32LE(f.data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(offset, 42);
    locals.push(local, name, body);
    centrals.push(central, name);
    offset += local.length + name.length + body.length;
  }
  const dir = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(dir.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, dir, end]);
}

test('lecture ZIP : entrées stockées et compressées', () => {
  const big = Buffer.alloc(5000, 7);
  const archive = makeZip([
    { name: 'readme.txt', data: Buffer.from('bonjour') },
    { name: 'fceumm_libretro.dll', data: big, deflate: true },
  ]);
  const list = zip.entries(archive);
  assert.deepEqual(list.map((e) => [e.name, e.size]), [['readme.txt', 7], ['fceumm_libretro.dll', 5000]]);
  assert.equal(zip.extract(archive, list[0]).toString(), 'bonjour');
  assert.deepEqual(zip.extract(archive, list[1]), big);
  assert.throws(() => zip.entries(Buffer.from('pas une archive')));
});

test('cœurs du buildbot : nom valide, URL et DLL', () => {
  assert.ok(libretro.validCore('mupen64plus_next'));
  assert.ok(!libretro.validCore('../evil'));
  assert.ok(!libretro.validCore(''));
  assert.equal(libretro.coreUrl('snes9x'), 'https://buildbot.libretro.com/nightly/windows/x86_64/latest/snes9x_libretro.dll.zip');
});

test('ROM .zip décompressée sauf pour les cœurs arcade et DOS', () => {
  assert.ok(libretro.needsExtraction('snes9x', 'D:\Jeux\Mario.ZIP'));
  assert.ok(!libretro.needsExtraction('fbneo', 'D:\Jeux\sf2.zip'));
  assert.ok(!libretro.needsExtraction('mame2003_plus', 'D:\Jeux\pacman.zip'));
  assert.ok(!libretro.needsExtraction('snes9x', 'D:\Jeux\Mario.sfc'));
});

test('fichier principal d\'une archive : liste de disques ou image CD, sinon le plus gros', () => {
  const pick = (entries) => libretro.mainEntry(entries).name;
  assert.equal(pick([{ name: 'Jeu (Track 1).bin', size: 900 }, { name: 'Jeu.cue', size: 1 }]), 'Jeu.cue');
  assert.equal(pick([{ name: 'a.txt', size: 3 }, { name: 'jeu.sfc', size: 900 }, { name: 'dir/', size: 0 }]), 'jeu.sfc');
});

test('chemin d\'extraction sûr', () => {
  const dir = path.join('C:', 'cache');
  assert.equal(libretro.safeEntryPath(dir, '../../Windows/evil.dll'), path.join(dir, 'Windows', 'evil.dll'));
  assert.equal(libretro.safeEntryPath(dir, 'C:/abs/jeu.bin'), path.join(dir, 'abs', 'jeu.bin'));
});

test('arguments du moteur intégré', () => {
  const args = libretro.playerArgs({
    dll: 'C:\c\snes9x_libretro.dll', rom: 'D:\Jeux\Mario.sfc', systemDir: 'B', saveDir: 'S', stateDir: 'T',
    optionsFile: 'O.cfg', title: 'Super Mario World', language: 'fr', windowed: false,
  });
  assert.deepEqual(args.slice(0, 4), ['--core', 'C:\c\snes9x_libretro.dll', '--rom', 'D:\Jeux\Mario.sfc']);
  assert.equal(args[args.indexOf('--title') + 1], 'Super Mario World');
  assert.ok(!args.includes('--windowed'));
});

test('arguments du moteur intégré : reprise de la partie', () => {
  const base = { dll: 'C.dll', rom: 'R:\\cache\\jeu.bin', systemDir: 'B', saveDir: 'S', stateDir: 'T', optionsFile: 'O', title: 'Jeu', language: 'fr' };
  const args = libretro.playerArgs({ ...base, stateName: 'Jeu (Europe)', resume: true });
  assert.equal(args[args.indexOf('--state-name') + 1], 'Jeu (Europe)');
  assert.ok(args.includes('--resume'));
  assert.ok(!libretro.playerArgs(base).includes('--resume'));
});
