import fs from 'node:fs';
import zlib from 'node:zlib';

// Archives .zip sans dépendance : lecture du répertoire central (nom, tailles, CRC32 de chaque
// fichier, ZIP64 compris), extraction des fichiers stockés ou compressés (« deflate ») ; écriture
// d'archives sans compression (ZIP64 : fichiers et archives de plus de 4 Go).

const EOCD = 0x06054b50;
const EOCD64 = 0x06064b50;
const EOCD64_LOCATOR = 0x07064b50;
const CENTRAL = 0x02014b50;
const LOCAL = 0x04034b50;
const ZIP64_EXTRA = 0x0001;
const MAX32 = 0xffffffff;

/**
 * Fichiers d'un .zip : { name, size, compressedSize, crc32, method, offset } (dossiers exclus, sauf
 * avec [directories]) ; [] si l'archive est illisible.
 */
export function readZipEntries(file, { directories = false } = {}) {
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
    let count = tail.readUInt16LE(eocd + 10);
    let dirSize = tail.readUInt32LE(eocd + 12);
    let dirOffset = tail.readUInt32LE(eocd + 16);
    // ZIP64 : nombre de fichiers et emplacement du répertoire central dans l'enregistrement ZIP64.
    if (eocd >= 20 && tail.readUInt32LE(eocd - 20) === EOCD64_LOCATOR) {
      const record = Buffer.alloc(56);
      fs.readSync(fd, record, 0, 56, Number(tail.readBigUInt64LE(eocd - 20 + 8)));
      if (record.readUInt32LE(0) !== EOCD64) return [];
      count = Number(record.readBigUInt64LE(32));
      dirSize = Number(record.readBigUInt64LE(40));
      dirOffset = Number(record.readBigUInt64LE(48));
    }
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
      if (directories || !name.endsWith('/')) {
        const entry = {
          name,
          method: dir.readUInt16LE(p + 10),
          crc32: dir.readUInt32LE(p + 16).toString(16).padStart(8, '0'),
          compressedSize: dir.readUInt32LE(p + 20),
          size: dir.readUInt32LE(p + 24),
          offset: dir.readUInt32LE(p + 42),
        };
        // ZIP64 : valeurs à 0xFFFFFFFF données dans le champ supplémentaire, dans cet ordre.
        const extraEnd = p + 46 + nameLength + extraLength;
        for (let x = p + 46 + nameLength; x + 4 <= extraEnd; x += 4 + dir.readUInt16LE(x + 2)) {
          if (dir.readUInt16LE(x) !== ZIP64_EXTRA) continue;
          let v = x + 4;
          for (const key of ['size', 'compressedSize', 'offset']) {
            if (entry[key] !== MAX32) continue;
            entry[key] = Number(dir.readBigUInt64LE(v));
            v += 8;
          }
        }
        entries.push(entry);
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

/** Début des données d'un fichier de l'archive (après son en-tête local). */
function dataStart(fd, entry) {
  const header = Buffer.alloc(30);
  fs.readSync(fd, header, 0, 30, entry.offset);
  if (header.readUInt32LE(0) !== LOCAL) throw new Error(`zip: en-tête invalide (${entry.name})`);
  return entry.offset + 30 + header.readUInt16LE(26) + header.readUInt16LE(28);
}

/** Contenu d'un fichier de l'archive (vérifié par son CRC32) ; lève une erreur sinon. */
export function readZipEntry(file, entry) {
  const fd = fs.openSync(file, 'r');
  try {
    const start = dataStart(fd, entry);
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

/**
 * CRC32 d'un fichier stocké sans compression dans l'archive, relu depuis le disque par morceaux
 * ([onChunk] reçoit chacun).
 */
export function zipEntryCrc(file, entry, onChunk = () => {}) {
  if (entry.method !== 0) throw new Error(`zip: fichier compressé (${entry.name})`);
  const fd = fs.openSync(file, 'r');
  try {
    let position = dataStart(fd, entry);
    let left = entry.size;
    let crc = 0;
    const buf = Buffer.alloc(1 << 20);
    while (left > 0) {
      const n = fs.readSync(fd, buf, 0, Math.min(buf.length, left), position);
      if (n <= 0) throw new Error(`zip: fichier tronqué (${entry.name})`);
      const chunk = buf.subarray(0, n);
      crc = zlib.crc32(chunk, crc);
      onChunk(chunk);
      position += n;
      left -= n;
    }
    return (crc >>> 0).toString(16).padStart(8, '0');
  } finally {
    fs.closeSync(fd);
  }
}

/** Date et heure au format MS-DOS : [heure, date]. */
function dosTime(date) {
  return [
    (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1),
    ((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate(),
  ];
}

/**
 * Écriture d'une archive sans compression, en ZIP64 (tailles et emplacements sur 64 bits) : dossiers
 * (« nom/ ») et fichiers de taille connue d'avance, dont le CRC32 est écrit une fois les données écrites.
 */
export class ZipWriter {
  constructor(file) {
    this.file = file;
    this.fd = fs.openSync(file, 'w');
    this.position = 0;
    this.entries = [];
    [this.time, this.date] = dosTime(new Date());
  }

  write(buf) {
    fs.writeSync(this.fd, buf, 0, buf.length, this.position);
    this.position += buf.length;
  }

  /** En-tête local (tailles dans le champ ZIP64) ; CRC32 complété par endFile. */
  begin(name, size) {
    const nameBuf = Buffer.from(name, 'utf8');
    const header = Buffer.alloc(30 + nameBuf.length + 20);
    header.writeUInt32LE(LOCAL, 0);
    header.writeUInt16LE(45, 4); // version 4.5 : ZIP64
    header.writeUInt16LE(0x0800, 6); // noms en UTF-8
    header.writeUInt16LE(this.time, 10);
    header.writeUInt16LE(this.date, 12);
    header.writeUInt32LE(MAX32, 18);
    header.writeUInt32LE(MAX32, 22);
    header.writeUInt16LE(nameBuf.length, 26);
    header.writeUInt16LE(20, 28);
    nameBuf.copy(header, 30);
    const extra = 30 + nameBuf.length;
    header.writeUInt16LE(ZIP64_EXTRA, extra);
    header.writeUInt16LE(16, extra + 2);
    header.writeBigUInt64LE(BigInt(size), extra + 4);
    header.writeBigUInt64LE(BigInt(size), extra + 12);
    const entry = { name, nameBuf, size, offset: this.position, crc: 0, written: 0 };
    this.write(header);
    return entry;
  }

  addDirectory(name) {
    this.entries.push(this.begin(name, 0));
  }

  beginFile(name, size) {
    return this.begin(name, size);
  }

  writeData(entry, chunk) {
    entry.crc = zlib.crc32(chunk, entry.crc);
    entry.written += chunk.length;
    if (entry.written > entry.size) throw new Error(`zip: taille dépassée (${entry.name})`);
    this.write(chunk);
  }

  /** Fin du fichier : CRC32 écrit dans l'en-tête local ; renvoie { size, crc32 }. */
  endFile(entry) {
    if (entry.written !== entry.size) throw new Error(`zip: taille incorrecte (${entry.name})`);
    const crc = Buffer.alloc(4);
    crc.writeUInt32LE(entry.crc >>> 0);
    fs.writeSync(this.fd, crc, 0, 4, entry.offset + 14);
    this.entries.push(entry);
    return { size: entry.size, crc32: (entry.crc >>> 0).toString(16).padStart(8, '0') };
  }

  /** Répertoire central, enregistrements de fin (ZIP64 et classique), puis fermeture. */
  close() {
    const dirOffset = this.position;
    for (const e of this.entries) {
      const header = Buffer.alloc(46 + e.nameBuf.length + 28);
      header.writeUInt32LE(CENTRAL, 0);
      header.writeUInt16LE(45, 4);
      header.writeUInt16LE(45, 6);
      header.writeUInt16LE(0x0800, 8);
      header.writeUInt16LE(this.time, 12);
      header.writeUInt16LE(this.date, 14);
      header.writeUInt32LE(e.crc >>> 0, 16);
      header.writeUInt32LE(MAX32, 20);
      header.writeUInt32LE(MAX32, 24);
      header.writeUInt16LE(e.nameBuf.length, 28);
      header.writeUInt16LE(28, 30);
      header.writeUInt32LE(e.name.endsWith('/') ? 0x10 : 0, 38); // attribut « dossier » (MS-DOS)
      header.writeUInt32LE(MAX32, 42);
      e.nameBuf.copy(header, 46);
      const extra = 46 + e.nameBuf.length;
      header.writeUInt16LE(ZIP64_EXTRA, extra);
      header.writeUInt16LE(24, extra + 2);
      header.writeBigUInt64LE(BigInt(e.size), extra + 4);
      header.writeBigUInt64LE(BigInt(e.size), extra + 12);
      header.writeBigUInt64LE(BigInt(e.offset), extra + 20);
      this.write(header);
    }
    const dirSize = this.position - dirOffset;
    const end = Buffer.alloc(56 + 20 + 22);
    end.writeUInt32LE(EOCD64, 0);
    end.writeBigUInt64LE(44n, 4);
    end.writeUInt16LE(45, 12);
    end.writeUInt16LE(45, 14);
    end.writeBigUInt64LE(BigInt(this.entries.length), 24);
    end.writeBigUInt64LE(BigInt(this.entries.length), 32);
    end.writeBigUInt64LE(BigInt(dirSize), 40);
    end.writeBigUInt64LE(BigInt(dirOffset), 48);
    end.writeUInt32LE(EOCD64_LOCATOR, 56);
    end.writeBigUInt64LE(BigInt(this.position), 64);
    end.writeUInt32LE(1, 72);
    end.writeUInt32LE(EOCD, 76);
    end.writeUInt16LE(0xffff, 84);
    end.writeUInt16LE(0xffff, 86);
    end.writeUInt32LE(MAX32, 88);
    end.writeUInt32LE(MAX32, 92);
    this.write(end);
    fs.closeSync(this.fd);
    this.fd = undefined;
  }

  /** Abandon : archive incomplète supprimée. */
  abort() {
    if (this.fd !== undefined) fs.closeSync(this.fd);
    this.fd = undefined;
    fs.rmSync(this.file, { force: true });
  }
}
