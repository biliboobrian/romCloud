// Paquets PS4 « fpkg » (.pkg, magie « \x7FCNT ») : informations (param.sfo) et extraction du jeu dans
// un .zip sans compression (dossier CUSAxxxxx, ou CUSAxxxxx-UPDATE pour une mise à jour), seul format
// que lit shadPS4. Le contenu du paquet est une image PFS chiffrée (AES-XTS, secteurs de 4 Ko) qui
// contient une image compressée (PFSC : blocs de 64 Ko compressés par zlib ou non) :
//  - clé de l'image (EKPFS) : clé DK3 (entrée 0x10) déchiffrée par RSA, puis clé de l'image (entrée
//    0x20) déchiffrée par AES-CBC et RSA ; clés RSA des paquets de développement, publiques ;
//  - clés de données et de tweak : HMAC-SHA256 de la graine de l'image PFS par l'EKPFS ;
//  - système de fichiers de l'image PFSC : superbloc, inodes de 0xA8 octets, dossiers ; les fichiers
//    du jeu sont sous « uroot ».
// Les fichiers de sce_sys (param.sfo, icon0.png, npbind.dat…) sont des entrées du paquet.
// Format d'après l'ancienne extraction des paquets de shadPS4 et LibOrbisPkg.
import fs from 'node:fs';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { ZipWriter, readZipEntries, zipEntryCrc } from './zip.js';

export const PS4_PKG_MAGIC = 0x7f434e54;
const SECTOR = 0x1000;
const BLOCK = 0x10000;
const INODE_SIZE = 0xa8;
const READ_WINDOW = 16 << 20;
const PFSC_MAGIC = 0x43534650; // « PFSC »
const PFS_MAGIC = 20130315n;
const DIRENT_FILE = 2;
const DIRENT_DIR = 3;
// Entrées du paquet.
const ENTRY_KEYS = 0x10;
const IMAGE_KEY = 0x20;
const PARAM_SFO = 0x1000;
// eboot.bin d'un fpkg : SELF non chiffré (« 4F 15 3D 1D ») ou ELF.
const SELF_MAGIC = 0x1d3d154f;
const ELF_MAGIC = 0x464c457f;

// Clés RSA-2048 (module, exposant privé) des paquets de développement : DK3 et clé « fake » des fpkg.
const DK3_KEY = {
  n:
    'd212fc335f6ddb831609628b0356273782d477853529392d526b8c4c8cfb06c1845be7d4f7bcd24e6245cd2abbd77776453655273fb3f5f98eda4befaa59aeb39bea5498d206326a' +
    '58312ae0d44f90b50a7decf43a9c52672d99318e0c43e682fe0746e12e50d41f2d2f7ed908ba06b3bf2e203f4e3ffe44ffaa50435791699449158282e40f4c8d9d2cc95b1d64bf888b' +
    'd4c594e76547841ee57910fb989347b97d8512a640982cf792bc951932ede890560d65c1aa78c62e54fd5f54a1f67ee5e05f61c120b4b9b4330870e4df8956ed012946775f8cb8a9f5' +
    '1e2eb3b9bfe009b78d28d4a6c3b81e1f07ebb4120b95b88530fddc3913d07cdc8fedf9c9a3c1',
  d:
    '32d903908fbdb08f572b285e0b8db3ea5cd17ea890888cdd6a80bbb1dfc1f70daa32f0b77ccb88800e8b64b0be4cd60e9b8c1e2a64e1f35cd77601415e935c94fedd4662c31b5ae2a0' +
    'bc2debc3980aa7b7856970682b644ab31fcc7ddc7c26f477f65cf2ae5a442dd3ab16620419bafb90ffe23050896ecb56b2ebc09116925e308eaec7945dfd35e120f8ad3ebc08bfc036' +
    '749fd5bb5208fd0666f37ab304f475295de95faa1030b20f5a1ac12ab3fecb21ad80ec8f20091cdbc55894c29cc6ce82653e5790bca98b06b4f072f677df9864f1ecfe372dbcae8c08' +
    '811fc3c9891ac742824b2edc8e8d73ceb1cc01d90870873c4408ec498f815ae240ff77fc0d',
};
const FAKE_KEY = {
  n:
    'c6cf71e7e59af0d12a2c458bf92a0ec143058bc37117801dcd497dde359d259ba0d7a0f27d6c087eaa5502682b23c644b84418eb56cf16a24803c9e74f87eb3d30c31588bf20e79dff' +
    '770cde1d241e63a94f8abf5bbe601968333bfced9f474e5ff8eacb3d00bd6701f92c6dc6ac1364e76714f3dc52696ab9832c4230131bb2d8a5020d79ed96b10df8cc0cdf81954f0358' +
    '09570e80692efeff5277ea7528a8fbc9bebf9fbbb7798e1805e180bd50349481d353c269a2d24ccf6cf4572c104a3ffb22fd8b97e2c95ba62bcdd61b6bdb687f4bc2a05034c005e58d' +
    'ef2467ff9340cf2d62a2a050b1f13aa83dfd80d1f9b80522afc8354590588ee33a7cbd3e27',
  d:
    '7f76cd0ee2d4de051cc6d9a80e8dfa7bca1eaa271a40f8f1228735dddbfdeef8c2bcbd01fb8be23e63b2b1225c56496e11be07440b9a2666d1492c8fd31bcfa4a1b8d1fba49ed22128' +
    '83098af6a00ba3d60f9b6368ccbc0c4e145b27a4a9f42bb9b87bc0e651ad1d77d46bb9ce20d126667e5e9ea2e96b90f373b8528f4411030c1397393d132258d5438249da6e7ca1c58c' +
    'a5b009e0ce3ddff49d3c9715e26ac72b3c509323dbba4a226644ac78bb0e1a2743b57167aff4ab48469373d042ab9363e56c9ade5024c0237d99793f2207e0c148561bdf830912b42d' +
    '456bc9c06885999079961ad7f54d1f3783404aec3937a680927dc580c7d66ffe8a7989c6b1',
};

/** Noms des fichiers de sce_sys selon l'identifiant de l'entrée du paquet. */
const SCE_SYS = (() => {
  const names = new Map(
    Object.entries({
      0x400: 'license.dat', 0x401: 'license.info', 0x402: 'nptitle.dat', 0x403: 'npbind.dat', 0x404: 'selfinfo.dat',
      0x406: 'imageinfo.dat', 0x407: 'target-deltainfo.dat', 0x408: 'origin-deltainfo.dat', 0x409: 'psreserved.dat',
      0x1000: 'param.sfo', 0x1001: 'playgo-chunk.dat', 0x1002: 'playgo-chunk.sha', 0x1003: 'playgo-manifest.xml',
      0x1004: 'pronunciation.xml', 0x1005: 'pronunciation.sig', 0x1006: 'pic1.png', 0x1007: 'pubtoolinfo.dat',
      0x1008: 'app/playgo-chunk.dat', 0x1009: 'app/playgo-chunk.sha', 0x100a: 'app/playgo-manifest.xml',
      0x100b: 'shareparam.json', 0x100c: 'shareoverlayimage.png', 0x100d: 'save_data.png', 0x100e: 'shareprivacyguardimage.png',
      0x1200: 'icon0.png', 0x1220: 'pic0.png', 0x1240: 'snd0.at9', 0x1260: 'changeinfo/changeinfo.xml',
      0x1280: 'icon0.dds', 0x12a0: 'pic0.dds', 0x12c0: 'pic1.dds',
    }).map(([id, name]) => [Number(id), name]),
  );
  const nn = (i) => String(i).padStart(2, '0');
  for (let i = 0; i <= 30; i++) {
    names.set(0x1201 + i, `icon0_${nn(i)}.png`);
    names.set(0x1241 + i, `pic1_${nn(i)}.png`);
    names.set(0x1261 + i, `changeinfo/changeinfo_${nn(i)}.xml`);
    names.set(0x1281 + i, `icon0_${nn(i)}.dds`);
    names.set(0x12c1 + i, `pic1_${nn(i)}.dds`);
  }
  for (let i = 0; i <= 99; i++) names.set(0x1400 + i, `trophy/trophy${nn(i)}.trp`);
  return names;
})();

/** Déchiffrement RSA (PKCS#1 v1.5) de [cipher] (256 octets) par la clé [key] : message. */
export function rsaDecrypt(cipher, key) {
  const n = BigInt(`0x${key.n}`);
  let e = BigInt(`0x${key.d}`);
  let base = BigInt(`0x${cipher.toString('hex')}`) % n;
  let m = 1n;
  while (e > 0n) {
    if (e & 1n) m = (m * base) % n;
    base = (base * base) % n;
    e >>= 1n;
  }
  const out = Buffer.from(m.toString(16).padStart(512, '0'), 'hex');
  const end = out.indexOf(0, 2);
  if (out[0] !== 0 || out[1] !== 2 || end < 10) throw new Error('ps4pkg: clé du paquet illisible (paquet chiffré par Sony ?)');
  return out.subarray(end + 1);
}

/** param.sfo : { CLÉ: valeur } (textes et entiers). */
export function parseSfo(buf) {
  if (buf.length < 20 || buf.readUInt32LE(0) !== 0x46535000) return {};
  const keys = buf.readUInt32LE(8);
  const data = buf.readUInt32LE(12);
  const count = buf.readUInt32LE(16);
  const result = {};
  for (let i = 0; i < count && 20 + i * 16 + 16 <= buf.length; i++) {
    const p = 20 + i * 16;
    const keyStart = keys + buf.readUInt16LE(p);
    const key = buf.toString('utf8', keyStart, buf.indexOf(0, keyStart));
    const format = buf.readUInt16LE(p + 2);
    const length = buf.readUInt32LE(p + 4);
    const start = data + buf.readUInt32LE(p + 12);
    if (start + length > buf.length) continue;
    result[key] = format === 0x0404 ? buf.readUInt32LE(start) : buf.toString('utf8', start, start + length).replace(/\0+$/, '');
  }
  return result;
}

/** Paquet PS4 ouvert en lecture. */
export class Ps4Pkg {
  constructor(file) {
    this.fd = fs.openSync(file, 'r');
    try {
      const header = this.read(0, 0x1000);
      if (header.readUInt32BE(0) !== PS4_PKG_MAGIC) throw new Error('ps4pkg: pas un paquet PS4');
      this.contentId = header.toString('latin1', 0x40, 0x64).replace(/\0+$/, '');
      this.pfsOffset = Number(header.readBigUInt64BE(0x410));
      this.pfsSize = Number(header.readBigUInt64BE(0x418));
      const count = header.readUInt32BE(0x10);
      const table = this.read(header.readUInt32BE(0x18), count * 32);
      this.entries = Array.from({ length: count }, (_, i) => {
        const raw = table.subarray(i * 32, i * 32 + 32);
        return { id: raw.readUInt32BE(0), flags1: raw.readUInt32BE(8), flags2: raw.readUInt32BE(12), offset: raw.readUInt32BE(16), size: raw.readUInt32BE(20), raw };
      });
      const sfo = this.entry(PARAM_SFO);
      this.sfo = sfo ? parseSfo(this.read(sfo.offset, sfo.size)) : {};
    } catch (err) {
      this.close();
      throw err;
    }
  }

  close() {
    if (this.fd !== undefined) fs.closeSync(this.fd);
    this.fd = undefined;
  }

  read(position, length) {
    const buf = Buffer.alloc(length);
    if (fs.readSync(this.fd, buf, 0, length, position) < length) throw new Error('ps4pkg: paquet incomplet');
    return buf;
  }

  /**
   * Copie de [length] octets lus par une fenêtre de 16 Mo : les blocs d'un fichier se suivent dans le
   * paquet, une grande lecture est bien plus rapide que des milliers de lectures de 64 Ko.
   */
  readAhead(position, length) {
    const w = this.window;
    if (!w || position < w.position || position + length > w.position + w.length) {
      const size = Math.max(length, READ_WINDOW);
      const buf = this.windowBuffer?.length >= size ? this.windowBuffer : Buffer.allocUnsafe(size);
      this.windowBuffer = buf;
      const n = fs.readSync(this.fd, buf, 0, size, position);
      if (n < length) throw new Error('ps4pkg: paquet incomplet');
      this.window = { position, length: n };
    }
    const start = position - this.window.position;
    return Buffer.from(this.windowBuffer.subarray(start, start + length));
  }

  entry(id) {
    return this.entries.find((e) => e.id === id);
  }

  /** { titleId, category ('gd' jeu, 'gp' mise à jour, 'ac' DLC), title, version }. */
  info() {
    return {
      contentId: this.contentId,
      titleId: this.sfo.TITLE_ID || this.contentId.slice(7, 16),
      category: this.sfo.CATEGORY || null,
      title: this.sfo.TITLE || null,
      version: this.sfo.APP_VER || null,
    };
  }

  /** Clé AES (IV et clé) d'une entrée : SHA-256 de l'entrée de la table et de la clé DK3. */
  entryIvKey(entry) {
    return crypto.createHash('sha256').update(Buffer.concat([entry.raw, this.dk3])).digest();
  }

  aesCbc(ivKey, data) {
    const decipher = crypto.createDecipheriv('aes-128-cbc', ivKey.subarray(16, 32), ivKey.subarray(0, 16)).setAutoPadding(false);
    return Buffer.concat([decipher.update(data), decipher.final()]);
  }

  /** Clés de l'image PFS, et image PFSC : table de ses blocs, superbloc. */
  openImage() {
    const keys = this.entry(ENTRY_KEYS);
    const image = this.entry(IMAGE_KEY);
    if (!keys || !image) throw new Error('ps4pkg: clés du paquet absentes');
    this.dk3 = rsaDecrypt(this.read(keys.offset + 32 + 7 * 32 + 3 * 256, 256), DK3_KEY).subarray(0, 32);
    const imageKey = this.aesCbc(this.entryIvKey(image), this.read(image.offset, 256));
    const ekpfs = rsaDecrypt(imageKey, FAKE_KEY).subarray(0, 32);
    const pfsHeader = this.read(this.pfsOffset, 0x380);
    this.encrypted = (pfsHeader.readUInt16LE(0x1c) & 0x4) !== 0;
    const seed = pfsHeader.subarray(0x370, 0x380);
    const keyMaterial = crypto.createHmac('sha256', ekpfs).update(Buffer.concat([Buffer.from([1, 0, 0, 0]), seed])).digest();
    // XTS : clé de données puis clé de tweak.
    this.xtsKey = Buffer.concat([keyMaterial.subarray(16, 32), keyMaterial.subarray(0, 16)]);

    // Image PFSC : au début d'un bloc de 64 Ko de l'image PFS, après les métadonnées.
    this.pfscOffset = -1;
    for (let p = 0x20000; p < Math.min(this.pfsSize, 0x10000000); p += BLOCK) {
      if (this.pfsRead(p, 4).readUInt32LE(0) === PFSC_MAGIC) {
        this.pfscOffset = p;
        break;
      }
    }
    if (this.pfscOffset < 0) throw new Error('ps4pkg: image du jeu introuvable (paquet chiffré par Sony ?)');
    const pfsc = this.pfsRead(this.pfscOffset, 0x30);
    if (Number(pfsc.readBigUInt64LE(16)) !== BLOCK) throw new Error('ps4pkg: taille de bloc inattendue');
    const blocks = Number(pfsc.readBigUInt64LE(40)) / BLOCK;
    const map = this.pfsRead(this.pfscOffset + Number(pfsc.readBigUInt64LE(24)), (blocks + 1) * 8);
    this.blockMap = Array.from({ length: blocks + 1 }, (_, i) => Number(map.readBigUInt64LE(i * 8)));

    const superblock = this.block(0);
    if (superblock.readBigUInt64LE(8) !== PFS_MAGIC) throw new Error('ps4pkg: système de fichiers illisible (déchiffrement incorrect)');
    if (superblock.readUInt16LE(0x1c) & 0x3) throw new Error('ps4pkg: format du système de fichiers non pris en charge');
    this.superroot = Number(superblock.readBigUInt64LE(0x48));
    this.inodeBlocks = new Map();
  }

  /** [length] octets déchiffrés de l'image PFS à partir de [position]. */
  pfsRead(position, length) {
    const start = Math.floor(position / SECTOR) * SECTOR;
    const end = Math.ceil((position + length) / SECTOR) * SECTOR;
    const raw = this.readAhead(this.pfsOffset + start, end - start);
    if (this.encrypted) {
      const iv = Buffer.alloc(16);
      for (let s = 0; s < raw.length; s += SECTOR) {
        iv.writeBigUInt64LE(BigInt((start + s) / SECTOR));
        crypto.createDecipheriv('aes-128-xts', this.xtsKey, iv).update(raw.subarray(s, s + SECTOR)).copy(raw, s);
      }
    }
    return raw.subarray(position - start, position - start + length);
  }

  /** Bloc [i] de l'image PFSC, décompressé (64 Ko). */
  block(i) {
    const offset = this.blockMap[i];
    const size = this.blockMap[i + 1] - offset;
    if (size === BLOCK) return this.pfsRead(this.pfscOffset + offset, size);
    const out = Buffer.alloc(BLOCK);
    if (size > 0) zlib.inflateSync(this.pfsRead(this.pfscOffset + offset, size)).copy(out);
    return out;
  }

  /** Inode [ino] : { size, blocks, loc }. */
  inode(ino) {
    const perBlock = Math.floor(BLOCK / INODE_SIZE);
    const blockIndex = 1 + Math.floor(ino / perBlock);
    let buf = this.inodeBlocks.get(blockIndex);
    if (!buf) this.inodeBlocks.set(blockIndex, (buf = this.block(blockIndex)));
    const p = (ino % perBlock) * INODE_SIZE;
    return { size: Number(buf.readBigUInt64LE(p + 8)), blocks: buf.readUInt32LE(p + 0x60), loc: buf.readUInt32LE(p + 0x64) };
  }

  /** Entrées du dossier [ino] : [{ ino, type, name }] (sans « . » et « .. »). */
  dirents(ino) {
    const dir = this.inode(ino);
    const list = [];
    for (let b = 0; b < dir.blocks; b++) {
      const buf = this.block(dir.loc + b);
      for (let p = 0; p + 16 <= BLOCK; ) {
        const entryIno = buf.readInt32LE(p);
        const size = buf.readInt32LE(p + 12);
        if (entryIno === 0 || size <= 0) break;
        const type = buf.readInt32LE(p + 4);
        const name = buf.toString('utf8', p + 16, p + 16 + buf.readInt32LE(p + 8));
        if (type === DIRENT_FILE || type === DIRENT_DIR) list.push({ ino: entryIno, type, name });
        p += size;
      }
    }
    return list;
  }

  /** Fichiers et dossiers du jeu (sous « uroot ») : [{ path, dir, ino, size }], dossiers avant leur contenu. */
  files() {
    const uroot = this.dirents(this.superroot).find((d) => d.name === 'uroot');
    if (!uroot) throw new Error('ps4pkg: dossier du jeu introuvable');
    const result = [];
    const walk = (ino, prefix, depth) => {
      if (depth > 64) throw new Error('ps4pkg: dossiers trop profonds');
      for (const d of this.dirents(ino)) {
        if (/[\\/]|^\.\.?$/.test(d.name)) throw new Error(`ps4pkg: nom de fichier invalide (${d.name})`);
        const path = `${prefix}${d.name}`;
        if (d.type === DIRENT_DIR) {
          result.push({ path, dir: true, ino: d.ino, size: 0 });
          walk(d.ino, `${path}/`, depth + 1);
        } else {
          result.push({ path, dir: false, ino: d.ino, size: this.inode(d.ino).size });
        }
      }
    };
    walk(uroot.ino, '', 0);
    return result;
  }

  /** Contenu du fichier [ino], par morceaux de 64 Ko au plus. */
  *fileChunks(ino) {
    const { size, blocks, loc } = this.inode(ino);
    let left = size;
    for (let j = 0; j < blocks && left > 0; j++) {
      const data = this.block(loc + j);
      const chunk = left < data.length ? data.subarray(0, left) : data;
      left -= chunk.length;
      yield chunk;
    }
    if (left > 0) throw new Error('ps4pkg: fichier incomplet dans le paquet');
  }

  /** Fichiers de sce_sys venant des entrées du paquet : [{ name, data }] (entrées chiffrées déchiffrées). */
  sceSysEntries() {
    const result = [];
    for (const entry of this.entries) {
      const name = SCE_SYS.get(entry.id);
      if (!name || !entry.size) continue;
      const encrypted = (entry.flags1 & 0x80000000) !== 0;
      if (!encrypted) {
        result.push({ name, data: this.read(entry.offset, entry.size) });
      } else if (((entry.flags2 & 0xf000) >> 12) === 3) {
        // Chiffrée avec la clé DK3 : AES-CBC sur des blocs de 16 octets.
        const padded = Math.ceil(entry.size / 16) * 16;
        result.push({ name, data: this.aesCbc(this.entryIvKey(entry), this.read(entry.offset, padded)).subarray(0, entry.size) });
      }
    }
    return result;
  }
}

/** Informations d'un paquet PS4 ({ titleId, category, title, version }), null si ce n'en est pas un. */
export function ps4PkgInfo(file) {
  let pkg;
  try {
    pkg = new Ps4Pkg(file);
    return pkg.info();
  } catch {
    return null;
  } finally {
    pkg?.close();
  }
}

/** Dossier du jeu dans le .zip : CUSAxxxxx, ou CUSAxxxxx-UPDATE pour une mise à jour. */
export function ps4Folder(info) {
  return info.category === 'gp' ? `${info.titleId}-UPDATE` : info.titleId;
}

/**
 * Extrait le jeu ou la mise à jour du paquet [pkgFile] dans [zipFile] (.zip sans compression), puis
 * relit l'archive pour la vérifier (contenu de chaque fichier, eboot.bin, param.sfo).
 * [onProgress(done, total)] : octets traités (extraction puis vérification). Renvoie les informations
 * du paquet et le nombre de fichiers.
 */
export function extractPs4Pkg(pkgFile, zipFile, onProgress = () => {}) {
  const pkg = new Ps4Pkg(pkgFile);
  try {
    const info = pkg.info();
    if (info.category !== 'gd' && info.category !== 'gp') throw new Error(`ps4pkg: type de contenu non pris en charge (${info.category || '?'})`);
    if (!/^[A-Z]{4}\d{5}$/.test(info.titleId)) throw new Error('ps4pkg: identifiant du jeu illisible');
    pkg.openImage();
    const folder = ps4Folder(info);
    const files = pkg.files();
    const present = new Set(files.map((f) => f.path.toLowerCase()));
    const sceSys = pkg.sceSysEntries().filter((e) => !present.has(`sce_sys/${e.name}`.toLowerCase()));
    if (!files.some((f) => !f.dir && f.path.toLowerCase() === 'eboot.bin')) throw new Error('ps4pkg: eboot.bin absent du paquet');

    const total = 2 * (files.reduce((n, f) => n + f.size, 0) + sceSys.reduce((n, e) => n + e.data.length, 0));
    let done = 0;
    let reported = 0;
    const progress = (n) => {
      done += n;
      if (done - reported >= total / 200 || done === total) {
        reported = done;
        onProgress(done, total);
      }
    };

    const expected = new Map();
    const zip = new ZipWriter(zipFile);
    try {
      zip.addDirectory(`${folder}/`);
      for (const f of files) {
        if (f.dir) {
          zip.addDirectory(`${folder}/${f.path}/`);
          continue;
        }
        const entry = zip.beginFile(`${folder}/${f.path}`, f.size);
        for (const chunk of pkg.fileChunks(f.ino)) {
          zip.writeData(entry, chunk);
          progress(chunk.length);
        }
        expected.set(entry.name, zip.endFile(entry));
      }
      const dirs = new Set(files.filter((f) => f.dir).map((f) => f.path.toLowerCase()));
      for (const e of sceSys) {
        // Dossiers de sce_sys (« sce_sys/trophy/ ») ajoutés s'ils manquent.
        const parts = `sce_sys/${e.name}`.split('/').slice(0, -1);
        for (let i = 1; i <= parts.length; i++) {
          const dir = parts.slice(0, i).join('/');
          if (!dirs.has(dir.toLowerCase())) {
            dirs.add(dir.toLowerCase());
            zip.addDirectory(`${folder}/${dir}/`);
          }
        }
        const entry = zip.beginFile(`${folder}/sce_sys/${e.name}`, e.data.length);
        zip.writeData(entry, e.data);
        expected.set(entry.name, zip.endFile(entry));
        progress(e.data.length);
      }
      zip.close();
    } catch (err) {
      zip.abort();
      throw err;
    }

    // Vérification : chaque fichier relu depuis le disque (taille et CRC32 identiques à l'écriture).
    const entries = readZipEntries(zipFile);
    if (entries.length !== expected.size) throw new Error('ps4pkg: archive incomplète');
    for (const e of entries) {
      const want = expected.get(e.name);
      if (!want || want.size !== e.size || want.crc32 !== e.crc32) throw new Error(`ps4pkg: archive incorrecte (${e.name})`);
      let magic = null;
      const crc = zipEntryCrc(zipFile, e, (chunk) => {
        if (magic === null) magic = chunk.length >= 4 ? chunk.readUInt32LE(0) : 0;
        progress(chunk.length);
      });
      if (crc !== e.crc32) throw new Error(`ps4pkg: archive illisible (${e.name})`);
      if (e.name === `${folder}/eboot.bin` && ![SELF_MAGIC, ELF_MAGIC].includes(magic)) {
        throw new Error('ps4pkg: eboot.bin illisible (déchiffrement incorrect)');
      }
    }
    if (!expected.has(`${folder}/sce_sys/param.sfo`)) throw new Error('ps4pkg: param.sfo absent');
    onProgress(total, total);
    return { ...info, folder, files: expected.size };
  } finally {
    pkg.close();
  }
}

/** Dossier PS4 d'un .zip (« CUSA24899 », « CUSA24899-UPDATE ») : { titleId, category }, sinon null. */
export function ps4ZipInfo(file) {
  const first = readZipEntries(file, { directories: true })[0];
  const m = first && /^([A-Z]{4}\d{5})(-UPDATE|-patch)?\//i.exec(first.name);
  return m ? { titleId: m[1].toUpperCase(), category: m[2] ? 'gp' : 'gd' } : null;
}
