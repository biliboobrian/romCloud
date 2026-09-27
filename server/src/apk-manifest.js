// Lecture du paquet et de la version d'un APK, sans dépendance : l'APK est une archive ZIP
// dont le fichier AndroidManifest.xml est compilé au format XML binaire d'Android (AXML).
import fs from 'node:fs';
import zlib from 'node:zlib';

/** Lit `length` octets à la position `position` du fichier ouvert `fd`. */
function readAt(fd, position, length) {
  const buf = Buffer.alloc(length);
  const n = fs.readSync(fd, buf, 0, length, position);
  return buf.subarray(0, n);
}

/** Extrait un fichier d'une archive ZIP (répertoire central, méthodes « stocké » et « deflate »). */
export function readZipEntry(file, entryName) {
  const fd = fs.openSync(file, 'r');
  try {
    const size = fs.fstatSync(fd).size;
    // Fin du répertoire central : dans les 64 Ko (+ 22 octets) de fin d'archive.
    const tailLength = Math.min(size, 65557);
    const tail = readAt(fd, size - tailLength, tailLength);
    let eocd = -1;
    for (let i = tail.length - 22; i >= 0; i--) {
      if (tail.readUInt32LE(i) === 0x06054b50) {
        eocd = i;
        break;
      }
    }
    if (eocd < 0) throw new Error('not a zip archive');
    const count = tail.readUInt16LE(eocd + 10);
    const cdSize = tail.readUInt32LE(eocd + 12);
    const cdOffset = tail.readUInt32LE(eocd + 16);
    const cd = readAt(fd, cdOffset, cdSize);
    let p = 0;
    for (let i = 0; i < count && p + 46 <= cd.length; i++) {
      if (cd.readUInt32LE(p) !== 0x02014b50) break;
      const method = cd.readUInt16LE(p + 10);
      const compressedSize = cd.readUInt32LE(p + 20);
      const nameLength = cd.readUInt16LE(p + 28);
      const extraLength = cd.readUInt16LE(p + 30);
      const commentLength = cd.readUInt16LE(p + 32);
      const localOffset = cd.readUInt32LE(p + 42);
      const name = cd.toString('utf8', p + 46, p + 46 + nameLength);
      if (name === entryName) {
        const local = readAt(fd, localOffset, 30);
        const dataOffset = localOffset + 30 + local.readUInt16LE(26) + local.readUInt16LE(28);
        const data = readAt(fd, dataOffset, compressedSize);
        if (method === 0) return data;
        if (method === 8) return zlib.inflateRawSync(data);
        throw new Error(`unsupported zip method ${method}`);
      }
      p += 46 + nameLength + extraLength + commentLength;
    }
    return null;
  } finally {
    fs.closeSync(fd);
  }
}

// Identifiants de ressources Android des attributs (quand le nom a été retiré du manifeste).
const ATTR_IDS = { 0x0101021b: 'versionCode', 0x0101021c: 'versionName' };

/** Lit le pool de chaînes d'un fichier AXML (UTF-8 ou UTF-16). */
function readStringPool(buf, start) {
  const headerSize = buf.readUInt16LE(start + 2);
  const count = buf.readUInt32LE(start + 8);
  const utf8 = (buf.readUInt32LE(start + 16) & 0x100) !== 0;
  const stringsStart = start + buf.readUInt32LE(start + 20);
  const strings = [];
  for (let i = 0; i < count; i++) {
    let p = stringsStart + buf.readUInt32LE(start + headerSize + i * 4);
    if (utf8) {
      // Longueur en caractères puis en octets, chacune sur 1 ou 2 octets.
      p += buf[p] & 0x80 ? 2 : 1;
      let length = buf[p];
      if (length & 0x80) {
        length = ((length & 0x7f) << 8) | buf[p + 1];
        p += 2;
      } else {
        p += 1;
      }
      strings.push(buf.toString('utf8', p, p + length));
    } else {
      let length = buf.readUInt16LE(p);
      if (length & 0x8000) {
        length = ((length & 0x7fff) << 16) | buf.readUInt16LE(p + 2);
        p += 4;
      } else {
        p += 2;
      }
      strings.push(buf.toString('utf16le', p, p + length * 2));
    }
  }
  return strings;
}

/** Analyse un AndroidManifest.xml binaire : paquet, versionCode et versionName de <manifest>. */
export function parseBinaryManifest(buf) {
  if (buf.length < 8 || buf.readUInt16LE(0) !== 0x0003) throw new Error('not a binary XML file');
  let strings = [];
  let resourceIds = [];
  let p = buf.readUInt16LE(2);
  while (p + 8 <= buf.length) {
    const type = buf.readUInt16LE(p);
    const headerSize = buf.readUInt16LE(p + 2);
    const size = buf.readUInt32LE(p + 4);
    if (size < 8) break;
    if (type === 0x0001) {
      strings = readStringPool(buf, p);
    } else if (type === 0x0180) {
      resourceIds = [];
      for (let q = p + headerSize; q + 4 <= p + size; q += 4) resourceIds.push(buf.readUInt32LE(q));
    } else if (type === 0x0102) {
      const ext = p + headerSize;
      const name = strings[buf.readUInt32LE(ext + 4)];
      if (name === 'manifest') {
        const attrStart = buf.readUInt16LE(ext + 8);
        const attrSize = buf.readUInt16LE(ext + 10);
        const attrCount = buf.readUInt16LE(ext + 12);
        const result = { packageName: null, versionCode: null, versionName: null };
        for (let i = 0; i < attrCount; i++) {
          const a = ext + attrStart + i * attrSize;
          const nameIndex = buf.readUInt32LE(a + 4);
          const attrName = strings[nameIndex] || ATTR_IDS[resourceIds[nameIndex]];
          const raw = buf.readUInt32LE(a + 8);
          const dataType = buf[a + 15];
          const data = buf.readUInt32LE(a + 16);
          let value;
          if (raw !== 0xffffffff) value = strings[raw];
          else if (dataType === 0x03) value = strings[data];
          else if (dataType === 0x10 || dataType === 0x11) value = data;
          if (attrName === 'package') result.packageName = String(value);
          else if (attrName === 'versionCode') result.versionCode = Number(value);
          else if (attrName === 'versionName' && value != null) result.versionName = String(value);
        }
        return result;
      }
    }
    p += size;
  }
  throw new Error('<manifest> not found');
}

/** Paquet et version d'un fichier APK (erreur si ce n'est pas un APK lisible). */
export function readApkInfo(file) {
  const manifest = readZipEntry(file, 'AndroidManifest.xml');
  if (!manifest) throw new Error('AndroidManifest.xml not found');
  const info = parseBinaryManifest(manifest);
  if (!info.packageName) throw new Error('package name not found');
  return info;
}
