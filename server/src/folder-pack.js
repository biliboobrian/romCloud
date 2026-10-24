// Compression des jeux PC (jeux en dossiers), demandée dans l'administration : chaque fichier « X »
// du dossier rangé dans sa propre archive « X.xz » (xz -9e, le format qui fait gagner le plus de
// place, voir xz.js), notée dans le manifeste du dossier (folder-games.js) ; les applications
// téléchargent les archives une à une (les plus lourdes d'abord) et décompressent chacune dès sa
// réception. Archives « X.zip » des compressions d'avant le xz : converties en xz, toujours lues.
// Décompression : retour aux fichiers en clair. En tâche de fond (file des conversions) ;
// interrompue, elle reprend où elle en était (manifeste écrit après chaque fichier).
import fs from 'node:fs';
import path from 'node:path';
import { Transform, Writable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import zlib from 'node:zlib';
import { PACK_MANIFEST, fileInFolder, folderFiles, packExtension, readPackManifest } from './folder-games.js';
import { HttpError } from './http-error.js';
import { token } from './i18n.js';
import { enqueueConvert } from './jobs.js';
import { gameFilePath, requireGameRow, scanSystem } from './library.js';
import { xzAvailable, xzCompress, xzDecompressStream } from './xz.js';
import { ZipWriter, readZipEntries, zipEntryStream } from './zip.js';

/** Fichiers compressés ou décompressés en même temps (chaque xz utilise déjà plusieurs fils). */
const CONCURRENCY = 2;

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
 * Contenu d'origine d'un fichier compressé du jeu (« X.xz » ou « X.zip »), en flux ; [archive] :
 * chemin de l'archive.
 */
export function packedStream(archive, format, signal) {
  if (format === 'xz') return xzDecompressStream(archive, signal);
  const entry = readZipEntries(archive)[0];
  if (!entry) throw new Error(`zip: archive illisible (${archive})`);
  return zipEntryStream(archive, entry);
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

async function pack(job, dir, signal) {
  if (!xzAvailable()) throw new HttpError(500, 'errors.xzMissing');
  const manifest = readPackManifest(dir);
  // Fichiers en clair et archives zip, les plus lourds d'abord ; « X.xz » déjà présent sans être
  // une archive du jeu (fichier du jeu lui-même) : « X » reste tel quel.
  const files = folderFiles(dir)
    .filter((f) => f.format !== 'xz' && !(fs.existsSync(fileInFolder(dir, `${f.path}.xz`)) && !manifest[f.path]))
    .sort((a, b) => b.size - a.size);
  job.total = files.reduce((n, f) => n + f.size, 0);
  job.done = 0;
  await eachConcurrently(files, async (f) => {
    const src = fileInFolder(dir, f.path);
    const xz = `${src}.xz`;
    const temp = `${xz}.part`;
    // Archive zip : convertie sans passer par le fichier en clair.
    const source = f.packed ? `${src}${packExtension(f.format)}` : src;
    const stat = fs.statSync(source);
    try {
      const input = f.packed ? packedStream(source, f.format, signal) : fs.createReadStream(src);
      const result = await xzCompress(input, temp, { onBytes: (n) => (job.done += n), signal });
      if (result.size !== f.size || (f.crc32 && result.crc32 !== f.crc32)) throw new Error(`xz: contenu incorrect (${f.path})`);
      fs.renameSync(temp, xz);
      manifest[f.path] = { size: result.size, crc32: result.crc32, mtime: manifest[f.path]?.mtime ?? Math.floor(stat.mtimeMs), format: 'xz' };
      writeManifest(dir, manifest);
      fs.rmSync(source, { force: true });
    } finally {
      fs.rmSync(temp, { force: true });
    }
  });
}

async function unpack(job, dir, signal) {
  const manifest = readPackManifest(dir);
  const files = folderFiles(dir).filter((f) => f.packed).sort((a, b) => b.size - a.size);
  if (files.some((f) => f.format === 'xz') && !xzAvailable()) throw new HttpError(500, 'errors.xzMissing');
  job.total = files.reduce((n, f) => n + f.size, 0);
  job.done = 0;
  await eachConcurrently(files, async (f) => {
    const dest = fileInFolder(dir, f.path);
    const archive = `${dest}${packExtension(f.format)}`;
    const temp = `${dest}.part`;
    try {
      await unpackFile(archive, f.format, temp, f, (n) => (job.done += n), signal);
      const mtime = manifest[f.path]?.mtime;
      if (mtime) fs.utimesSync(temp, new Date(), new Date(mtime));
      fs.renameSync(temp, dest);
      delete manifest[f.path];
      writeManifest(dir, manifest);
      fs.rmSync(archive, { force: true });
    } finally {
      fs.rmSync(temp, { force: true });
    }
  });
  // Plus aucun fichier compressé : manifeste retiré (entrées d'archives disparues comprises).
  if (!folderFiles(dir).some((f) => f.packed)) writeManifest(dir, {});
}

/**
 * Programme la compression en xz ([mode] « zip » : nom gardé pour l'API) ou la décompression
 * (« flat ») d'un jeu PC ; renvoie la tâche (null : déjà programmée). Fiche du jeu mise à jour par
 * un scan du système à la fin.
 */
export function queueFolderPack(gameId, mode) {
  const row = requireGameRow(gameId);
  if (!row.is_folder) throw new HttpError(400, 'errors.notFolderGame');
  if (mode !== 'zip' && mode !== 'xz' && mode !== 'flat') throw new HttpError(400, 'errors.invalidPackMode');
  if (mode !== 'flat' && !xzAvailable()) throw new HttpError(500, 'errors.xzMissing');
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
