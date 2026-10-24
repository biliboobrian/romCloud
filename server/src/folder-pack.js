// Compression des jeux PC (jeux en dossiers), demandée dans l'administration : chaque fichier « X »
// du dossier rangé dans sa propre archive « X.zip » (deflate), notée dans le manifeste du dossier
// (folder-games.js) ; les applications téléchargent les archives en parallèle (les plus lourdes
// d'abord) et décompressent chacune dès sa réception. Décompression : retour aux fichiers en clair.
// En tâche de fond (file des conversions), quelques fichiers à la fois ; interrompue, elle reprend
// où elle en était (manifeste écrit après chaque fichier).
import fs from 'node:fs';
import path from 'node:path';
import { Transform, Writable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import zlib from 'node:zlib';
import { PACK_MANIFEST, fileInFolder, folderFiles, readPackManifest } from './folder-games.js';
import { HttpError } from './http-error.js';
import { token } from './i18n.js';
import { enqueueConvert } from './jobs.js';
import { gameFilePath, requireGameRow, scanSystem } from './library.js';
import { ZipWriter, readZipEntries, zipEntryStream } from './zip.js';

/** Fichiers compressés ou décompressés en même temps (zlib travaille dans ses propres fils). */
const CONCURRENCY = 3;

function writeManifest(dir, files) {
  const file = path.join(dir, PACK_MANIFEST);
  if (!Object.keys(files).length) return fs.rmSync(file, { force: true });
  fs.writeFileSync(`${file}.tmp`, JSON.stringify({ version: 1, files }));
  fs.renameSync(`${file}.tmp`, file);
}

/** Archive [zip] contenant le seul fichier [src] (compressé) ; renvoie { size, compressedSize, crc32 }. */
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

/** Fichier [dest] tiré de l'archive [zip] (un seul fichier), vérifié par sa taille et son CRC32. */
export async function unpackFile(zip, dest, { size, crc32 }, onBytes = () => {}, signal) {
  const entry = readZipEntries(zip)[0];
  if (!entry) throw new Error(`zip: archive illisible (${zip})`);
  let crc = 0;
  let written = 0;
  await pipeline(
    zipEntryStream(zip, entry),
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
  if (written !== size || got !== crc32) {
    fs.rmSync(dest, { force: true });
    throw new Error(`zip: contenu incorrect (${path.basename(dest)})`);
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
  const manifest = readPackManifest(dir);
  // Fichiers en clair, les plus lourds d'abord ; « X.zip » déjà présent sans être une archive du
  // jeu (fichier du jeu lui-même) : « X » reste en clair.
  const files = folderFiles(dir)
    .filter((f) => !f.packed && !(fs.existsSync(fileInFolder(dir, `${f.path}.zip`)) && !manifest[f.path]))
    .sort((a, b) => b.size - a.size);
  job.total = files.reduce((n, f) => n + f.size, 0);
  job.done = 0;
  await eachConcurrently(files, async (f) => {
    const src = fileInFolder(dir, f.path);
    const zip = `${src}.zip`;
    const temp = `${zip}.part`;
    const stat = fs.statSync(src);
    try {
      const result = await packFile(src, temp, (n) => (job.done += n), signal);
      fs.renameSync(temp, zip);
      manifest[f.path] = { size: result.size, crc32: result.crc32, mtime: Math.floor(stat.mtimeMs) };
      writeManifest(dir, manifest);
      fs.rmSync(src, { force: true });
    } finally {
      fs.rmSync(temp, { force: true });
    }
  });
}

async function unpack(job, dir, signal) {
  const manifest = readPackManifest(dir);
  const files = folderFiles(dir).filter((f) => f.packed).sort((a, b) => b.size - a.size);
  job.total = files.reduce((n, f) => n + f.size, 0);
  job.done = 0;
  await eachConcurrently(files, async (f) => {
    const dest = fileInFolder(dir, f.path);
    const zip = `${dest}.zip`;
    const temp = `${dest}.part`;
    try {
      await unpackFile(zip, temp, f, (n) => (job.done += n), signal);
      const mtime = manifest[f.path]?.mtime;
      if (mtime) fs.utimesSync(temp, new Date(), new Date(mtime));
      fs.renameSync(temp, dest);
      delete manifest[f.path];
      writeManifest(dir, manifest);
      fs.rmSync(zip, { force: true });
    } finally {
      fs.rmSync(temp, { force: true });
    }
  });
  // Plus aucun fichier compressé : manifeste retiré (entrées d'archives disparues comprises).
  if (!folderFiles(dir).some((f) => f.packed)) writeManifest(dir, {});
}

/**
 * Programme la compression ([mode] « zip ») ou la décompression (« flat ») d'un jeu PC ; renvoie la
 * tâche (null : déjà programmée). Fiche du jeu mise à jour par un scan du système à la fin.
 */
export function queueFolderPack(gameId, mode) {
  const row = requireGameRow(gameId);
  if (!row.is_folder) throw new HttpError(400, 'errors.notFolderGame');
  if (mode !== 'zip' && mode !== 'flat') throw new HttpError(400, 'errors.invalidPackMode');
  const dir = gameFilePath(row);
  return enqueueConvert({
    key: `pack:${row.id}`,
    label: token(mode === 'zip' ? 'jobs.folderPack' : 'jobs.folderUnpack', { title: row.title || row.file_name }),
    systemId: row.system_id,
    run: async (job) => {
      const controller = new AbortController();
      job.cancel = () => controller.abort();
      try {
        await (mode === 'zip' ? pack : unpack)(job, dir, controller.signal);
      } finally {
        job.cancel = null;
        scanSystem(row.system_id);
      }
    },
  });
}
