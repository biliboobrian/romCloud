import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-ps4-test-${process.pid}-${Date.now()}`);

const { ZipWriter, readZipEntries, readZipEntry, zipEntryCrc } = await import('../src/zip.js');
const { parseSfo, ps4ZipInfo } = await import('../src/ps4-pkg.js');
const { autoGroups, partInfo } = await import('../src/groups.js');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'romcloud-ps4-'));

test('archive sans compression en ZIP64 : relue, CRC vérifiés, dossiers', () => {
  const file = path.join(tmp, 'Stray.zip');
  const zip = new ZipWriter(file);
  zip.addDirectory('CUSA24899/');
  const eboot = zip.beginFile('CUSA24899/eboot.bin', 6);
  zip.writeData(eboot, Buffer.from('ab'));
  zip.writeData(eboot, Buffer.from('cdef'));
  assert.deepEqual(zip.endFile(eboot), { size: 6, crc32: '4b8e39ef' });
  const sfo = zip.beginFile('CUSA24899/sce_sys/param.sfo', 3);
  zip.writeData(sfo, Buffer.from('sfo'));
  zip.endFile(sfo);
  zip.close();

  const entries = readZipEntries(file);
  assert.deepEqual(entries.map((e) => [e.name, e.size, e.method, e.crc32]), [
    ['CUSA24899/eboot.bin', 6, 0, '4b8e39ef'],
    ['CUSA24899/sce_sys/param.sfo', 3, 0, entries[1].crc32],
  ]);
  assert.equal(readZipEntry(file, entries[0]).toString(), 'abcdef');
  assert.equal(zipEntryCrc(file, entries[0]), entries[0].crc32);
  assert.equal(readZipEntries(file, { directories: true })[0].name, 'CUSA24899/');
  assert.deepEqual(ps4ZipInfo(file), { titleId: 'CUSA24899', category: 'gd' });

  // Taille annoncée non atteinte : erreur, archive abandonnée.
  const bad = new ZipWriter(path.join(tmp, 'bad.zip'));
  const e = bad.beginFile('a', 10);
  bad.writeData(e, Buffer.from('abc'));
  assert.throws(() => bad.endFile(e), /taille incorrecte/);
  assert.throws(() => bad.writeData(e, Buffer.alloc(8)), /taille dépassée/);
  bad.abort();
  assert.equal(fs.existsSync(path.join(tmp, 'bad.zip')), false);
});

test('mise à jour PS4 convertie : dossier CUSAxxxxx-UPDATE', () => {
  const file = path.join(tmp, 'Stray UPD.zip');
  const zip = new ZipWriter(file);
  zip.addDirectory('CUSA24899-UPDATE/');
  zip.close();
  assert.deepEqual(ps4ZipInfo(file), { titleId: 'CUSA24899', category: 'gp' });
  assert.equal(ps4ZipInfo(path.join(tmp, 'absent.zip')), null);
});

test('param.sfo : textes et entiers', () => {
  // En-tête, 2 index, table des clés, données.
  const keys = Buffer.from('CATEGORY\0TITLE_ID\0APP_TYPE\0\0');
  const index = Buffer.alloc(3 * 16);
  const data = Buffer.alloc(32);
  data.write('gp\0', 0);
  data.write('CUSA24899\0', 4);
  data.writeUInt32LE(1, 20);
  const entry = (i, keyOffset, format, length, offset) => {
    index.writeUInt16LE(keyOffset, i * 16);
    index.writeUInt16LE(format, i * 16 + 2);
    index.writeUInt32LE(length, i * 16 + 4);
    index.writeUInt32LE(length, i * 16 + 8);
    index.writeUInt32LE(offset, i * 16 + 12);
  };
  entry(0, 0, 0x0204, 3, 0);
  entry(1, 9, 0x0204, 10, 4);
  entry(2, 18, 0x0404, 4, 20);
  const header = Buffer.alloc(20);
  header.writeUInt32LE(0x46535000, 0);
  header.writeUInt32LE(20 + index.length, 8);
  header.writeUInt32LE(20 + index.length + keys.length, 12);
  header.writeUInt32LE(3, 16);
  assert.deepEqual(parseSfo(Buffer.concat([header, index, keys, data])), { CATEGORY: 'gp', TITLE_ID: 'CUSA24899', APP_TYPE: 1 });
  assert.deepEqual(parseSfo(Buffer.from('rien')), {});
});

test('regroupement PS4 : mise à jour et DLC rattachées au jeu du même identifiant', () => {
  const ps4 = (category, titleId = 'CUSA24899') => ({ type: 'ps4', category, titleId });
  assert.equal(partInfo('STRAY.CUSA24899.1.00.FW9.00.pkg', ps4('gd')), null);
  assert.deepEqual(partInfo('STRAY.CUSA24899.1.00.FW9.00.UPD1.03.pkg', ps4('gp')), { kind: 'update', serial: 'CUSA24899', title: null });
  const groups = autoGroups([
    { id: 1, fileName: 'Stray.zip', pkg: ps4('gd') }, // nom sans identifiant : celui du dossier du jeu
    { id: 2, fileName: 'STRAY.CUSA24899.1.00.FW9.00.UPD1.03.zip', pkg: ps4('gp') },
    { id: 3, fileName: 'Stray Soundtrack.pkg', pkg: ps4('ac') },
    { id: 4, fileName: 'Bloodborne.zip', pkg: ps4('gd', 'CUSA00900') },
  ]);
  assert.deepEqual([groups.get(2), groups.get(3)], [
    { parentId: 1, kind: 'update', index: null },
    { parentId: 1, kind: 'dlc', index: null },
  ]);
  assert.equal(groups.get(4).parentId, null);
});
