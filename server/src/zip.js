import fs from 'node:fs';
import zlib from 'node:zlib';

// Lecture d'archives .zip sans dépendance : répertoire central (nom, tailles, CRC32 de chaque
// fichier) et extraction des fichiers stockés ou compressés (« deflate »).

const EOCD = 0x06054b50;
const CENTRAL = 0x02014b50;
const LOCAL = 0x04034b50;

/**
 * Fichiers d'un .zip : { name, size, compressedSize, crc32, method, offset } (dossiers exclus) ;
 * [] si l'archive est illisible.
 */
export function readZipEntries(file) {
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const { size } = fs.fstatSync(fd);
    // Fin de répertoire central : dans les 22 derniers octets, plus un commentaire de 64 Ko au plus.
    const tailSize = Math.min(size, 22 + 0xffff);
    const tail = Buffer.alloc(tailSize);
    fs.readSync(fd, tail, 0, tailSize, size - tailSize);
    let eocd = -1;
    for (let i = tailSize - 22; i >= 0; i--) {
      if (tail.readUInt32LE(i) === EOCD) {
        eocd = i;
        break;
      }
    }
    if (eocd < 0) return [];
    const count = tail.readUInt16LE(eocd + 10);
    const dirSize = tail.readUInt32LE(eocd + 12);
    const dirOffset = tail.readUInt32LE(eocd + 16);
    if (dirOffset + dirSize > size) return [];
    const dir = Buffer.alloc(dirSize);
    fs.readSync(fd, dir, 0, dirSize, dirOffset);
    const entries = [];
    let p = 0;
    for (let n = 0; n < count && p + 46 <= dir.length && dir.readUInt32LE(p) === CENTRAL; n++) {
      const nameLength = dir.readUInt16LE(p + 28);
      const extraLength = dir.readUInt16LE(p + 30);
      const commentLength = dir.readUInt16LE(p + 32);
      // Barres obliques inverses : archives créées par Compress-Archive (Windows PowerShell 5).
      const name = dir.toString('utf8', p + 46, p + 46 + nameLength).replace(/\\/g, '/');
      if (!name.endsWith('/')) {
        entries.push({
          name,
          method: dir.readUInt16LE(p + 10),
          crc32: dir.readUInt32LE(p + 16).toString(16).padStart(8, '0'),
          compressedSize: dir.readUInt32LE(p + 20),
          size: dir.readUInt32LE(p + 24),
          offset: dir.readUInt32LE(p + 42),
        });
      }
      p += 46 + nameLength + extraLength + commentLength;
    }
    return entries;
  } catch {
    return [];
  } finally {
    if (fd !== undefined) fs.closeSync(fd);
  }
}

/** Contenu d'un fichier de l'archive (vérifié par son CRC32) ; lève une erreur sinon. */
export function readZipEntry(file, entry) {
  const fd = fs.openSync(file, 'r');
  try {
    const header = Buffer.alloc(30);
    fs.readSync(fd, header, 0, 30, entry.offset);
    if (header.readUInt32LE(0) !== LOCAL) throw new Error(`zip: en-tête invalide (${entry.name})`);
    const start = entry.offset + 30 + header.readUInt16LE(26) + header.readUInt16LE(28);
    const raw = Buffer.alloc(entry.compressedSize);
    fs.readSync(fd, raw, 0, raw.length, start);
    let data;
    if (entry.method === 0) data = raw;
    else if (entry.method === 8) data = zlib.inflateRawSync(raw);
    else throw new Error(`zip: compression ${entry.method} non prise en charge (${entry.name})`);
    if (zlib.crc32(data).toString(16).padStart(8, '0') !== entry.crc32) throw new Error(`zip: CRC incorrect (${entry.name})`);
    return data;
  } finally {
    fs.closeSync(fd);
  }
}
