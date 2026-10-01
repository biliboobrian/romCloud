// Lecture d'archives ZIP (fonctions pures, sans dépendance) : cœurs du buildbot libretro et ROMs
// compressées. Méthodes « stockée » et « deflate », sans chiffrement ni ZIP64.
const zlib = require('node:zlib');

/** Entrées de l'archive : [{ name, method, compressedSize, size, offset }]. */
function entries(buffer) {
  // Fin du répertoire central : signature 0x06054b50, dans les 64 Ko de la fin (commentaire).
  let eocd = -1;
  for (let i = buffer.length - 22; i >= Math.max(0, buffer.length - 65557); i--) {
    if (buffer.readUInt32LE(i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error('archive ZIP invalide');
  const count = buffer.readUInt16LE(eocd + 10);
  let p = buffer.readUInt32LE(eocd + 16);
  const list = [];
  for (let i = 0; i < count; i++) {
    if (buffer.readUInt32LE(p) !== 0x02014b50) throw new Error('répertoire ZIP invalide');
    const method = buffer.readUInt16LE(p + 10);
    const compressedSize = buffer.readUInt32LE(p + 20);
    const size = buffer.readUInt32LE(p + 24);
    const nameLength = buffer.readUInt16LE(p + 28);
    const extraLength = buffer.readUInt16LE(p + 30);
    const commentLength = buffer.readUInt16LE(p + 32);
    const offset = buffer.readUInt32LE(p + 42);
    const name = buffer.toString('utf8', p + 46, p + 46 + nameLength);
    list.push({ name, method, compressedSize, size, offset });
    p += 46 + nameLength + extraLength + commentLength;
  }
  return list;
}

/** Contenu décompressé d'une entrée. */
function extract(buffer, entry) {
  const p = entry.offset;
  if (buffer.readUInt32LE(p) !== 0x04034b50) throw new Error(`entrée ZIP invalide : ${entry.name}`);
  const start = p + 30 + buffer.readUInt16LE(p + 26) + buffer.readUInt16LE(p + 28);
  const data = buffer.subarray(start, start + entry.compressedSize);
  if (entry.method === 0) return Buffer.from(data);
  if (entry.method === 8) return zlib.inflateRawSync(data);
  throw new Error(`compression ZIP non prise en charge (${entry.method}) : ${entry.name}`);
}

module.exports = { entries, extract };
