import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';

const dataDir = path.join(os.tmpdir(), `romcloud-apk-test-${process.pid}`);
process.env.DATA_DIR = dataDir;

const { parseBinaryManifest, readApkInfo, readZipEntry } = await import('../src/apk-manifest.js');
const apks = await import('../src/apks.js');
const { createSystem } = await import('../src/systems.js');

after(() => {
  try {
    fs.rmSync(dataDir, { recursive: true, force: true });
  } catch {
    // Base SQLite encore ouverte sous Windows.
  }
});

// ---- Construction d'un AndroidManifest.xml binaire minimal ----
const u16 = (n) => { const b = Buffer.alloc(2); b.writeUInt16LE(n); return b; };
const u32 = (n) => { const b = Buffer.alloc(4); b.writeUInt32LE(n >>> 0); return b; };
const pad4 = (buf) => Buffer.concat([buf, Buffer.alloc((4 - (buf.length % 4)) % 4)]);

function stringPool(strings, utf8) {
  const datas = strings.map((s) => {
    if (utf8) {
      const bytes = Buffer.from(s, 'utf8');
      return Buffer.concat([Buffer.from([s.length, bytes.length]), bytes, Buffer.from([0])]);
    }
    return Buffer.concat([u16(s.length), Buffer.from(s, 'utf16le'), u16(0)]);
  });
  const offsets = [];
  let o = 0;
  for (const d of datas) {
    offsets.push(o);
    o += d.length;
  }
  const data = pad4(Buffer.concat(datas));
  const headerSize = 28;
  const stringsStart = headerSize + strings.length * 4;
  const size = stringsStart + data.length;
  return Buffer.concat([
    u16(0x0001), u16(headerSize), u32(size), u32(strings.length), u32(0), u32(utf8 ? 0x100 : 0),
    u32(stringsStart), u32(0), ...offsets.map(u32), data,
  ]);
}

/** attrs : [nomIndex, rawIndex|null, type, data] */
function manifestXml({ strings, utf8 = false, resourceIds = [], attrs }) {
  const pool = stringPool(strings, utf8);
  const resMap = Buffer.concat([u16(0x0180), u16(8), u32(8 + resourceIds.length * 4), ...resourceIds.map(u32)]);
  const attrBufs = attrs.map(([name, raw, type, data]) => Buffer.concat([
    u32(0xffffffff), u32(name), u32(raw ?? 0xffffffff), u16(8), Buffer.from([0, type]), u32(data),
  ]));
  const element = Buffer.concat([
    u16(0x0102), u16(16), u32(16 + 20 + attrBufs.length * 20), u32(1), u32(0xffffffff),
    u32(0xffffffff), u32(strings.indexOf('manifest')), u16(20), u16(20), u16(attrBufs.length), u16(0), u16(0), u16(0),
    ...attrBufs,
  ]);
  const body = Buffer.concat([pool, resMap, element]);
  return Buffer.concat([u16(0x0003), u16(8), u32(8 + body.length), body]);
}

/** Archive ZIP (entrées stockées ou compressées). */
function zip(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const { name, data, deflate } of entries) {
    const stored = deflate ? zlib.deflateRawSync(data) : data;
    const nameBuf = Buffer.from(name);
    const crc = zlib.crc32(data);
    const common = Buffer.concat([u16(20), u16(0), u16(deflate ? 8 : 0), u16(0), u16(0), u32(crc), u32(stored.length), u32(data.length), u16(nameBuf.length)]);
    const local = Buffer.concat([u32(0x04034b50), common, u16(0), nameBuf, stored]);
    centrals.push(Buffer.concat([u32(0x02014b50), u16(20), common, u16(0), u16(0), u16(0), u16(0), u32(0), u32(offset), nameBuf]));
    locals.push(local);
    offset += local.length;
  }
  const cd = Buffer.concat(centrals);
  const eocd = Buffer.concat([u32(0x06054b50), u16(0), u16(0), u16(entries.length), u16(entries.length), u32(cd.length), u32(offset), u16(0)]);
  return Buffer.concat([...locals, cd, eocd]);
}

const S = ['manifest', 'package', 'versionCode', 'versionName', 'org.ppsspp.ppsspp', '1.19.3'];
const ppssppManifest = manifestXml({
  strings: S,
  attrs: [[2, null, 0x10, 119030000], [3, 5, 0x03, 5], [1, 4, 0x03, 4]],
});

function writeApk(name, manifest, deflate = true) {
  const file = path.join(dataDir, name);
  fs.mkdirSync(dataDir, { recursive: true });
  fs.writeFileSync(file, zip([
    { name: 'classes.dex', data: Buffer.alloc(3000, 7), deflate: true },
    { name: 'AndroidManifest.xml', data: manifest, deflate },
  ]));
  return file;
}

test('manifeste binaire : paquet, versionCode, versionName (UTF-16 et UTF-8)', () => {
  assert.deepEqual(parseBinaryManifest(ppssppManifest), { packageName: 'org.ppsspp.ppsspp', versionCode: 119030000, versionName: '1.19.3' });
  const utf8 = manifestXml({ strings: S, utf8: true, attrs: [[1, 4, 0x03, 4], [3, 5, 0x03, 5], [2, null, 0x11, 42]] });
  assert.deepEqual(parseBinaryManifest(utf8), { packageName: 'org.ppsspp.ppsspp', versionCode: 42, versionName: '1.19.3' });
  // Noms d'attributs retirés : reconnus par leur identifiant de ressource Android.
  const stripped = manifestXml({
    strings: ['', '', 'manifest', 'package', 'com.retroarch', '1.21.0'],
    resourceIds: [0x0101021b, 0x0101021c],
    attrs: [[0, null, 0x10, 1741], [1, 5, 0x03, 5], [3, 4, 0x03, 4]],
  });
  assert.deepEqual(parseBinaryManifest(stripped), { packageName: 'com.retroarch', versionCode: 1741, versionName: '1.21.0' });
  assert.throws(() => parseBinaryManifest(Buffer.from('<manifest/>')));
});

test('lecture dans l’archive (entrée stockée ou compressée)', () => {
  assert.deepEqual(readApkInfo(writeApk('a.apk', ppssppManifest, false)).packageName, 'org.ppsspp.ppsspp');
  assert.deepEqual(readApkInfo(writeApk('b.apk', ppssppManifest, true)).versionCode, 119030000);
  assert.equal(readZipEntry(writeApk('c.apk', ppssppManifest), 'absent.xml'), null);
});

test('nom lisible d’un modèle Daijishou', () => {
  assert.equal(apks.emulatorName('psx - RetroArch 64 - swanstation'), 'RetroArch 64');
  assert.equal(apks.emulatorName('psp - PPSSPP'), 'PPSSPP');
  assert.equal(apks.emulatorName('dreamcast - Flycast (dev build)'), 'Flycast');
  assert.equal(apks.emulatorName('DuckStation'), 'DuckStation');
  assert.equal(apks.emulatorName('Mupen64Plus FZ - Pro'), 'Mupen64Plus FZ');
});

test('paquet d’un modèle d’émulateur', () => {
  assert.equal(apks.packageOfPlayer({ amStartArguments: '-n org.ppsspp.ppsspp/.PpssppActivity\n-d {file.uri}' }), 'org.ppsspp.ppsspp');
  assert.equal(apks.packageOfPlayer({ amStartArguments: '-a android.intent.action.VIEW -p com.github.stenzek.duckstation' }), 'com.github.stenzek.duckstation');
  assert.equal(apks.packageOfPlayer({ amStartArguments: '-a android.intent.action.VIEW' }), null);
});

test('envoi, nom par défaut, remplacement, émulateurs des systèmes, suppression', async () => {
  createSystem({
    id: 'psp', name: 'Sony PSP',
    players: [
      { name: 'psp - PPSSPP (Standalone)', uniqueId: 'psp.ppsspp', amStartArguments: '-n org.ppsspp.ppsspp/.PpssppActivity -d {file.uri}' },
      { name: 'psp - RetroArch 64 - ppsspp', uniqueId: 'psp.ra', amStartArguments: '-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture' },
    ],
  });
  const first = await apks.addApk(writeApk('upload1', ppssppManifest), 'ppsspp-release.apk');
  assert.equal(first.label, 'PPSSPP');
  assert.equal(first.versionName, '1.19.3');
  assert.ok(fs.existsSync(path.join(dataDir, 'apks', 'org.ppsspp.ppsspp.apk')));

  apks.updateApk(first.id, { label: 'PPSSPP Gold' });
  const newer = manifestXml({ strings: [...S.slice(0, 5), '1.20.0'], attrs: [[1, 4, 0x03, 4], [2, null, 0x10, 120000000], [3, 5, 0x03, 5]] });
  const second = await apks.addApk(writeApk('upload2', newer), 'ppsspp-1.20.apk');
  assert.equal(second.id, first.id);
  assert.equal(second.label, 'PPSSPP Gold'); // nom modifié conservé
  assert.equal(second.versionName, '1.20.0');
  assert.equal(apks.listApks().length, 1);

  const emus = apks.emulatorPackages();
  assert.deepEqual(emus.map((e) => [e.packageName, e.apkId]), [['org.ppsspp.ppsspp', first.id], ['com.retroarch.aarch64', null]]);
  assert.deepEqual(emus[0].systems, [{ id: 'psp', name: 'Sony PSP' }]);
  assert.deepEqual(emus.map((e) => e.names), [['PPSSPP'], ['RetroArch 64']]);

  await assert.rejects(apks.addApk(writeApk('upload3', Buffer.from('nope')), 'nope.apk'), /nope\.apk/);
  apks.deleteApk(first.id);
  assert.equal(apks.listApks().length, 0);
  assert.equal(fs.existsSync(path.join(dataDir, 'apks', 'org.ppsspp.ppsspp.apk')), false);
});
