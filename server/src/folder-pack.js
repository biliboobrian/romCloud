// Compression des jeux PC (jeux en dossiers), demandée dans l'administration, en zstd (-19, meilleur
// rapport place / temps de décompression, voir zstd.js) : fichier « X » de plus de [BUNDLE_MAX]
// rangé dans sa propre archive « X.zst » ; les plus petits regroupés dans des archives de
// [BUNDLE_MAX] au plus (avant compression ; fichiers mis bout à bout, puis compressés), rangées dans
// « .romcloud-bundles » (fichier qu'aucun autre ne peut compléter : son archive à lui) ; le tout
// noté dans le manifeste du dossier (folder-games.js). Les applications téléchargent les archives
// une à une (les plus lourdes d'abord) et décompressent chacune dès sa réception. Archives « X.xz »
// et « X.zip » des compressions d'avant le zstd, et « X.zst » de [BUNDLE_MAX] au plus (une archive
// par fichier, d'avant les regroupements) : converties, toujours lues.
// Décompression : retour aux fichiers en clair. En tâche de fond (file des conversions) ;
// interrompue, elle reprend où elle en était (manifeste écrit après chaque archive).
import fs from 'node:fs';
import path from 'node:path';
import { Readable, Transform, Writable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import zlib from 'node:zlib';
import { BUNDLE_ID_RE, BUNDLE_MAX, PACK_BUNDLES, bundleGroups, PACK_MANIFEST, bundlePath, fileInFolder, folderFiles, packExtension, packFormat, readPackManifest } from './folder-games.js';
import { HttpError } from './http-error.js';
import { token } from './i18n.js';
import { enqueueConvert } from './jobs.js';
import { gameFilePath, requireGameRow, scanSystem } from './library.js';
import { xzAvailable, xzDecompressStream } from './xz.js';
import { ZipWriter, readZipEntries, zipEntryStream } from './zip.js';
import { zstdAvailable, zstdCompress, zstdDecompressStream } from './zstd.js';

/** Fichiers compressés ou décompressés en même temps (chaque zstd utilise déjà plusieurs fils). */
const CONCURRENCY = 2;

export { BUNDLE_MAX, bundleGroups };

function writeManifest(dir, files) {
  const file = path.join(dir, PACK_MANIFEST);
  if (!Object.keys(files).length) return fs.rmSync(file, { force: true });
  fs.writeFileSync(`${file}.tmp`, JSON.stringify({ version: 2, files }));
  fs.renameSync(`${file}.tmp`, file);
}

/** Archive zip [zip] contenant le seul fichier [src] (compressé) ; renvoie { size, compressedSize, crc32 }. */
export async function packFile(src, zip, onBytes = () => {}, signal) {
  const size = fs.statSync(src).size;
  const writer = new ZipWriter(zip);
  try {
    const entry = writer.beginFile(path.basename(src), size, { deflate: true });
    await pipeline(
      fs.createReadStream(src),
      new Transform({
        transform(chunk, _enc, done) {
          writer.addRaw(entry, chunk);
          onBytes(chunk.length);
          done(null, chunk);
        },
      }),
      zlib.createDeflateRaw({ level: 6 }),
      new Writable({
        write(chunk, _enc, done) {
          writer.writeCompressed(entry, chunk);
          done();
        },
      }),
      { signal },
    );
    const result = writer.endFile(entry);
    writer.close();
    return result;
  } catch (err) {
    writer.abort();
    throw err;
  }
}

/**
 * Contenu d'origine d'un fichier compressé du jeu dans sa propre archive (« X.zst », « X.xz » ou
 * « X.zip »), en flux ; [archive] : chemin de l'archive.
 */
export function packedStream(archive, format, signal) {
  if (format === 'zst') return zstdDecompressStream(archive, signal);
  if (format === 'xz') return xzDecompressStream(archive, signal);
  const entry = readZipEntries(archive)[0];
  if (!entry) throw new Error(`zip: archive illisible (${archive})`);
  return zipEntryStream(archive, entry);
}

/** Octets [start] à [start + length] du flux [source] (arrêté ensuite). */
async function* sliceOf(source, start, length) {
  const end = start + length;
  let pos = 0;
  try {
    if (length === 0) return;
    for await (const chunk of source) {
      const from = Math.max(0, start - pos);
      const to = Math.min(chunk.length, end - pos);
      pos += chunk.length;
      if (to > from) yield chunk.subarray(from, to);
      if (pos >= end) return;
    }
    throw new Error('zstd: archive regroupée tronquée');
  } finally {
    source.destroy();
  }
}

/**
 * Contenu d'origine du fichier compressé [rel] du jeu en [dir] ([entry] : son entrée du manifeste),
 * en flux : tiré de son archive regroupée, ou de sa propre archive.
 */
export function packedFileStream(dir, rel, entry, signal) {
  if (entry.bundle) {
    if (!BUNDLE_ID_RE.test(entry.bundle)) throw new Error(`archive regroupée invalide (${rel})`);
    return Readable.from(sliceOf(zstdDecompressStream(bundlePath(dir, entry.bundle), signal), entry.offset ?? 0, entry.size), { objectMode: false });
  }
  const format = packFormat(entry.format);
  return packedStream(`${fileInFolder(dir, rel)}${packExtension(format)}`, format, signal);
}

/**
 * Écrit les fichiers [members] ({ offset, size, crc32, dest }, triés par place ; dest null : sauté)
 * tirés du contenu décompressé [source] d'une archive regroupée, chacun vérifié par sa taille et
 * son CRC32 ; [onMember] appelé après chaque fichier écrit, [onBytes] avec les octets écrits.
 */
async function splitBundle(source, members, { onMember = () => {}, onBytes = () => {} } = {}) {
  let pos = 0;
  let index = 0;
  let handle = null;
  let crc = 0;
  let written = 0;
  // Fichiers terminés à la position [at] (vides compris) : fermés et vérifiés.
  const finish = async (at) => {
    while (index < members.length && members[index].offset + members[index].size <= at) {
      const m = members[index];
      if (m.dest) {
        handle ??= await fs.promises.open(m.dest, 'w');
        await handle.close();
        handle = null;
        const got = (crc >>> 0).toString(16).padStart(8, '0');
        if (written !== m.size || (m.crc32 && got !== m.crc32)) throw new Error(`zstd: contenu incorrect (${m.rel})`);
        onMember(m);
      }
      crc = 0;
      written = 0;
      index++;
    }
  };
  try {
    for await (const chunk of source) {
      let at = 0;
      // Reste du contenu après le dernier fichier : lu quand même (empreinte de l'archive vérifiée).
      while (at < chunk.length) {
        await finish(pos + at);
        if (index >= members.length) break;
        const m = members[index];
        const here = pos + at;
        // Avant le fichier suivant (octets d'un fichier sauté) : passés.
        if (here < m.offset) {
          at += Math.min(chunk.length - at, m.offset - here);
          continue;
        }
        const take = Math.min(chunk.length - at, m.offset + m.size - here);
        if (m.dest) {
          const part = chunk.subarray(at, at + take);
          handle ??= await fs.promises.open(m.dest, 'w');
          await handle.write(part);
          crc = zlib.crc32(part, crc);
          written += take;
          onBytes(take);
        }
        at += take;
      }
      pos += chunk.length;
    }
    await finish(pos);
    if (index < members.length) throw new Error('zstd: archive regroupée tronquée');
  } finally {
    await handle?.close();
  }
}

/** Fichier [dest] tiré de l'[archive], vérifié par sa taille et son CRC32. */
async function unpackFile(archive, format, dest, { size, crc32 }, onBytes, signal) {
  let crc = 0;
  let written = 0;
  await pipeline(
    packedStream(archive, format, signal),
    new Transform({
      transform(chunk, _enc, done) {
        crc = zlib.crc32(chunk, crc);
        written += chunk.length;
        onBytes(chunk.length);
        done(null, chunk);
      },
    }),
    fs.createWriteStream(dest),
    { signal },
  );
  const got = (crc >>> 0).toString(16).padStart(8, '0');
  if (written !== size || (crc32 && got !== crc32)) {
    fs.rmSync(dest, { force: true });
    throw new Error(`${format}: contenu incorrect (${path.basename(dest)})`);
  }
}

/** Exécute [work] sur chaque élément, [CONCURRENCY] à la fois ; arrêt à la première erreur. */
async function eachConcurrently(items, work) {
  let next = 0;
  let failed = null;
  const worker = async () => {
    while (!failed && next < items.length) {
      const item = items[next++];
      try {
        await work(item);
      } catch (err) {
        failed ??= err;
      }
    }
  };
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  if (failed) throw failed;
}

/** Archives regroupées du dossier [dir] que le manifeste ne cite plus (compression interrompue) : supprimées. */
function removeUnusedBundles(dir, manifest) {
  const bundles = path.join(dir, PACK_BUNDLES);
  if (!fs.existsSync(bundles)) return;
  const used = new Set(Object.values(manifest).map((e) => e?.bundle).filter(Boolean));
  for (const name of fs.readdirSync(bundles)) {
    if (!used.has(name.replace(/\.zst$/, '')) || !name.endsWith('.zst')) fs.rmSync(path.join(bundles, name), { force: true, recursive: true });
  }
  if (!fs.readdirSync(bundles).length) fs.rmdirSync(bundles);
}

async function pack(job, dir, signal) {
  if (!zstdAvailable()) throw new HttpError(500, 'errors.zstdMissing');
  const manifest = readPackManifest(dir);
  removeUnusedBundles(dir, manifest);
  // Fichiers en clair, archives xz ou zip, et archives zstd d'un seul fichier de [BUNDLE_MAX] au
  // plus (à regrouper) ; « X.zst » déjà présent sans être une archive du jeu (fichier du jeu
  // lui-même) : « X » reste tel quel.
  const files = folderFiles(dir).filter(
    (f) => !f.bundle && (f.format !== 'zst' || f.size <= BUNDLE_MAX) && (f.packed || manifest[f.path] || !fs.existsSync(fileInFolder(dir, `${f.path}.zst`))),
  );
  // Archives xz à convertir : outil xz nécessaire pour les lire.
  if (files.some((f) => f.format === 'xz') && !xzAvailable()) throw new HttpError(500, 'errors.xzMissing');
  // Plus gros que [BUNDLE_MAX] : une archive chacun ; les autres regroupés (seul dans son groupe :
  // son archive à lui, laissée telle quelle si c'est déjà la sienne en zstd). Les plus lourds d'abord.
  const sizeOf = (group) => group.reduce((n, f) => n + f.size, 0);
  const units = [...files.filter((f) => f.size > BUNDLE_MAX).map((f) => [f]), ...bundleGroups(files.filter((f) => f.size <= BUNDLE_MAX))]
    .filter((group) => group.length > 1 || group[0].format !== 'zst')
    .sort((a, b) => sizeOf(b) - sizeOf(a));
  job.total = units.reduce((n, group) => n + sizeOf(group), 0);
  job.done = 0;
  // Fichier en clair, ou son archive à lui : lu, puis supprimé une fois compressé.
  const sourceOf = (f) => `${fileInFolder(dir, f.path)}${f.packed ? packExtension(f.format) : ''}`;
  const contentOf = (f) => (f.packed ? packedStream(sourceOf(f), f.format, signal) : fs.createReadStream(sourceOf(f)));
  const mtimeOf = (f) => manifest[f.path]?.mtime ?? Math.floor(fs.statSync(sourceOf(f)).mtimeMs);
  const check = (f, result) => {
    if (result.size !== f.size || (f.crc32 && result.crc32 !== f.crc32)) throw new Error(`zstd: contenu incorrect (${f.path})`);
  };
  // Nouvelles archives regroupées : numérotées après celles déjà là.
  const taken = Object.values(manifest).filter((e) => e?.bundle).map((e) => parseInt(e.bundle, 36)).filter(Number.isFinite);
  let nextBundle = taken.length ? Math.max(...taken) + 1 : 1;

  await eachConcurrently(units, async (group) => {
    if (group.length === 1) {
      const [f] = group;
      const zst = `${fileInFolder(dir, f.path)}.zst`;
      const temp = `${zst}.part`;
      try {
        const mtime = mtimeOf(f);
        const result = await zstdCompress(contentOf(f), temp, { onBytes: (n) => (job.done += n), signal });
        check(f, result);
        fs.renameSync(temp, zst);
        manifest[f.path] = { size: result.size, crc32: result.crc32, mtime, format: 'zst' };
        writeManifest(dir, manifest);
        fs.rmSync(sourceOf(f), { force: true });
      } finally {
        fs.rmSync(temp, { force: true });
      }
      return;
    }
    // Fichiers regroupés, rangés par chemin (ceux d'un même dossier se ressemblent : meilleure
    // compression) et mis bout à bout ; chacun vérifié par sa taille et son CRC32.
    group.sort((a, b) => a.path.localeCompare(b.path));
    const id = (nextBundle++).toString(36);
    const archive = bundlePath(dir, id);
    const temp = `${archive}.part`;
    const entries = [];
    async function* content() {
      let offset = 0;
      for (const f of group) {
        const mtime = mtimeOf(f);
        let crc = 0;
        let size = 0;
        for await (const chunk of contentOf(f)) {
          crc = zlib.crc32(chunk, crc);
          size += chunk.length;
          yield chunk;
        }
        const result = { size, crc32: (crc >>> 0).toString(16).padStart(8, '0') };
        check(f, result);
        entries.push([f.path, { ...result, mtime, format: 'zst', bundle: id, offset }]);
        offset += size;
      }
    }
    fs.mkdirSync(path.dirname(archive), { recursive: true });
    try {
      await zstdCompress(Readable.from(content()), temp, { onBytes: (n) => (job.done += n), signal });
      fs.renameSync(temp, archive);
      for (const [rel, entry] of entries) manifest[rel] = entry;
      writeManifest(dir, manifest);
      for (const f of group) fs.rmSync(sourceOf(f), { force: true });
    } finally {
      fs.rmSync(temp, { force: true });
    }
  });
  removeUnusedBundles(dir, manifest);
}

async function unpack(job, dir, signal) {
  const manifest = readPackManifest(dir);
  const files = folderFiles(dir).filter((f) => f.packed).sort((a, b) => b.size - a.size);
  if (files.some((f) => f.format === 'xz') && !xzAvailable()) throw new HttpError(500, 'errors.xzMissing');
  if (files.some((f) => f.format === 'zst') && !zstdAvailable()) throw new HttpError(500, 'errors.zstdMissing');
  job.total = files.reduce((n, f) => n + f.size, 0);
  job.done = 0;
  // Fichiers d'une même archive regroupée : tirés ensemble.
  const bundles = new Map();
  for (const f of files.filter((f) => f.bundle)) bundles.set(f.bundle, [...(bundles.get(f.bundle) ?? []), f]);
  const units = [...files.filter((f) => !f.bundle).map((f) => [f]), ...bundles.values()];
  // Fichier [rel] décompressé dans [temp] : mis à sa place, avec sa date d'origine.
  const restore = (rel, temp) => {
    const mtime = manifest[rel]?.mtime;
    if (mtime) fs.utimesSync(temp, new Date(), new Date(mtime));
    fs.renameSync(temp, fileInFolder(dir, rel));
    delete manifest[rel];
  };
  await eachConcurrently(units, async (group) => {
    const id = group[0].bundle;
    if (!id) {
      const [f] = group;
      const dest = fileInFolder(dir, f.path);
      const archive = `${dest}${packExtension(f.format)}`;
      const temp = `${dest}.part`;
      try {
        await unpackFile(archive, f.format, temp, f, (n) => (job.done += n), signal);
        restore(f.path, temp);
        writeManifest(dir, manifest);
        fs.rmSync(archive, { force: true });
      } finally {
        fs.rmSync(temp, { force: true });
      }
      return;
    }
    // Archive regroupée : ses fichiers encore compressés écrits, les autres (déjà en clair) sautés.
    const wanted = new Set(group.map((f) => f.path));
    const members = Object.entries(manifest)
      .filter(([, e]) => e?.bundle === id)
      .map(([rel, e]) => ({ rel, offset: e.offset ?? 0, size: e.size, crc32: e.crc32, dest: wanted.has(rel) ? `${fileInFolder(dir, rel)}.part` : null }))
      .sort((a, b) => a.offset - b.offset);
    try {
      await splitBundle(zstdDecompressStream(bundlePath(dir, id), signal), members, {
        onMember: (m) => restore(m.rel, m.dest),
        onBytes: (n) => (job.done += n),
      });
      for (const m of members) delete manifest[m.rel];
      writeManifest(dir, manifest);
      fs.rmSync(bundlePath(dir, id), { force: true });
    } finally {
      for (const m of members) if (m.dest) fs.rmSync(m.dest, { force: true });
    }
  });
  removeUnusedBundles(dir, manifest);
  // Plus aucun fichier compressé : manifeste retiré (entrées d'archives disparues comprises).
  if (!folderFiles(dir).some((f) => f.packed)) writeManifest(dir, {});
}

/**
 * Programme la compression en zstd ([mode] « zstd » ; « xz » et « zip » : noms d'avant, même
 * effet) ou la décompression (« flat ») d'un jeu PC ; renvoie la tâche (null : déjà programmée). Fiche du jeu mise à jour par
 * un scan du système à la fin.
 */
export function queueFolderPack(gameId, mode) {
  const row = requireGameRow(gameId);
  if (!row.is_folder) throw new HttpError(400, 'errors.notFolderGame');
  if (!['zstd', 'xz', 'zip', 'flat'].includes(mode)) throw new HttpError(400, 'errors.invalidPackMode');
  if (mode !== 'flat' && !zstdAvailable()) throw new HttpError(500, 'errors.zstdMissing');
  const dir = gameFilePath(row);
  return enqueueConvert({
    key: `pack:${row.id}`,
    label: token(mode === 'flat' ? 'jobs.folderUnpack' : 'jobs.folderPack', { title: row.title || row.file_name }),
    systemId: row.system_id,
    run: async (job) => {
      const controller = new AbortController();
      job.cancel = () => controller.abort();
      try {
        await (mode === 'flat' ? unpack : pack)(job, dir, controller.signal);
      } finally {
        job.cancel = null;
        scanSystem(row.system_id);
      }
    },
  });
}
