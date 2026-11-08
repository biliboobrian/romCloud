// Conversion des jeux sur CD (et des DVD de la PS2) en CHD, demandée dans l'administration : image
// de disque (.cue et ses pistes, .gdi, .toc, .iso), ou archive .zip qui en contient une, compressée
// par l'outil « chdman » de MAME (CHDMAN_PATH pour un autre emplacement) en un seul fichier
// « Jeu.chd », lu directement par les cœurs du moteur intégré (sans extraction) et bien plus petit
// qu'une image brute. La fiche du jeu est gardée (nom du fichier changé : jaquettes, sauvegardes,
// temps de jeu conservés) ; les fichiers d'origine sont supprimés une fois le CHD écrit. Disques
// d'un jeu (parties « Disc 2 »…) convertis avec lui. En tâche de fond (file des conversions).
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { CHD_SOURCE_RE, chdDisc } from './chd-systems.js';
import { db } from './db.js';
import { HttpError } from './http-error.js';
import { token } from './i18n.js';
import { enqueueConvert } from './jobs.js';
import { gameFilePath, getGameRow, requireGameRow, scanSystem } from './library.js';
import { requireSystem } from './systems.js';
import { readZipEntries, zipEntryStream } from './zip.js';

let chdman = process.env.CHDMAN_PATH || 'chdman';
let chdmanPrefix = [];
let available;

/** Outil utilisé (tests : script qui imite chdman, lancé par [bin] avec [prefix] avant ses arguments). */
export function setChdman(bin, prefix = []) {
  chdman = bin;
  chdmanPrefix = prefix;
  available = undefined;
}

/** chdman présent (vérifié une fois). */
export function chdmanAvailable() {
  available ??= !spawnSync(chdman, [...chdmanPrefix, 'help'], { stdio: 'ignore', windowsHide: true }).error;
  return available;
}

/** Images de disque lues par chdman, par ordre de préférence dans une archive. */
const IMAGE_EXTENSIONS = ['.cue', '.gdi', '.toc', '.iso'];

/**
 * Image de disque à convertir parmi les fichiers [names] d'une archive (chemins « a/b.cue ») : la
 * première sorte trouvée (.cue, puis .gdi…) ; null s'il n'y en a pas ; erreur s'il y en a plusieurs
 * (plusieurs disques dans une même archive).
 */
export function discImage(names) {
  for (const ext of IMAGE_EXTENSIONS) {
    const found = names.filter((n) => n.toLowerCase().endsWith(ext) && !path.posix.basename(n).startsWith('.'));
    if (found.length > 1) throw new HttpError(400, 'errors.chdSeveralDiscs', { count: found.length });
    if (found.length) return found[0];
  }
  return null;
}

/** Fichiers des pistes cités par une image .cue, .toc ou .gdi ([text] : son contenu). */
export function trackFiles(text, kind) {
  const re =
    kind === 'gdi'
      ? // « 1 0 4 2352 "Track 01.bin" 0 » (première ligne : nombre de pistes).
        /^[ \t]*\d+[ \t]+\d+[ \t]+\d+[ \t]+\d+[ \t]+(?:"([^"]+)"|(\S+))/gm
      : /^[ \t]*(?:FILE|DATAFILE)[ \t]+(?:"([^"]+)"|(\S+))/gim;
  return [...new Set([...String(text).matchAll(re)].map((m) => m[1] ?? m[2]))];
}

/** Dernières lignes utiles de la sortie de chdman (sans les lignes de progression). */
const lastLines = (output) =>
  output
    .split(/[\r\n]+/)
    .map((l) => l.trim())
    .filter((l) => l && !/% complete/.test(l))
    .slice(-3)
    .join(' — ');

/** Lance chdman ([args]) ; [onProgress] reçoit l'avancement (0 à 1). */
function runChdman(args, onProgress, signal) {
  return new Promise((resolve, reject) => {
    const child = spawn(chdman, [...chdmanPrefix, ...args], { stdio: ['ignore', 'pipe', 'pipe'], signal, windowsHide: true });
    let output = '';
    const onData = (data) => {
      const text = data.toString();
      output = (output + text).slice(-8000);
      const last = [...text.matchAll(/(\d+(?:\.\d+)?)% complete/g)].pop();
      if (last) onProgress(Math.min(1, Number(last[1]) / 100));
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.on('error', reject);
    child.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`chdman : ${lastLines(output) || `code ${code}`}`))));
  });
}

/** Chemin [rel] (« a/b.bin », celui d'une piste ou d'un fichier d'archive) dans [dir], sans en sortir. */
function inside(dir, rel) {
  const file = path.resolve(dir, rel);
  const root = path.resolve(dir);
  if (file !== root && file.startsWith(root + path.sep)) return file;
  throw new HttpError(400, 'errors.invalidFilePath');
}

/**
 * Ce qu'il faut pour convertir le jeu [row] : fichiers d'origine (supprimés après), taille à traiter
 * et archive .zip à extraire d'abord (ses fichiers, et l'image de disque qu'elle contient).
 */
function sourceOf(row) {
  const file = gameFilePath(row);
  const dir = path.dirname(file);
  const ext = path.extname(file).toLowerCase();
  if (!fs.existsSync(file)) throw new HttpError(404, 'errors.fileNotFound');
  if (ext === '.zip') {
    const entries = readZipEntries(file);
    const image = discImage(entries.map((e) => e.name));
    if (!image) throw new HttpError(400, 'errors.chdNoImage', { file: row.file_name });
    return { originals: [file], entries, image, size: entries.reduce((n, e) => n + e.size, 0) };
  }
  // Image .cue, .toc, .gdi : ses pistes, à côté d'elle.
  const tracks = ext === '.iso' ? [] : trackFiles(fs.readFileSync(file, 'utf8'), ext.slice(1)).map((t) => inside(dir, t));
  const missing = tracks.find((t) => !fs.existsSync(t));
  if (missing) throw new HttpError(400, 'errors.chdTrackMissing', { file: path.basename(missing) });
  const originals = [file, ...tracks];
  return { originals, image: file, size: originals.reduce((n, f) => n + fs.statSync(f).size, 0) };
}

/** Archive .zip [zip] extraite dans [work] ; [onBytes] reçoit les octets écrits. */
async function extractZip(zip, entries, work, onBytes, signal) {
  for (const entry of entries) {
    const dest = inside(work, entry.name);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    await pipeline(
      zipEntryStream(zip, entry),
      new Transform({
        transform(chunk, _enc, done) {
          onBytes(chunk.length);
          done(null, chunk);
        },
      }),
      fs.createWriteStream(dest),
      { signal },
    );
  }
}

/**
 * Convertit le jeu [row] (disque [disc] : « cd » ou « dvd ») ; [progress(part)] : avancement du jeu
 * (0 à 1). Fiche gardée, nom du fichier changé ; fichiers d'origine supprimés une fois le CHD écrit.
 */
async function convertGame(row, source, disc, progress, signal) {
  const file = gameFilePath(row);
  const dir = path.dirname(file);
  const name = `${row.file_name.replace(/\.[^.]+$/, '')}.chd`;
  const out = path.join(dir, name);
  // Fichiers cachés : ignorés par le scan du système pendant la conversion.
  const temp = path.join(dir, `.${name}.part`);
  const work = path.join(dir, `.romcloud-chd-${row.id}`);
  const zip = Boolean(source.entries);
  try {
    let image = source.image;
    if (zip) {
      fs.rmSync(work, { recursive: true, force: true });
      let written = 0;
      // Extraction : la moitié de l'avancement du jeu.
      await extractZip(file, source.entries, work, (n) => progress((written += n) / Math.max(1, source.size) / 2), signal);
      image = inside(work, source.image);
    }
    // DVD de la PS2 : images .iso ; ses jeux sur CD (.cue) et les autres consoles : createcd.
    const command = disc === 'dvd' && image.toLowerCase().endsWith('.iso') ? 'createdvd' : 'createcd';
    await runChdman([command, '-i', image, '-o', temp, '-f'], (p) => progress(zip ? 0.5 + p / 2 : p), signal);
    if (fs.existsSync(out)) throw new HttpError(409, 'errors.chdExists', { file: name });
    fs.renameSync(temp, out);
    const stat = fs.statSync(out);
    db.prepare("UPDATE games SET file_name = ?, size = ?, mtime = ?, crc32 = NULL, md5 = NULL, updated_at = datetime('now') WHERE id = ?").run(
      name,
      stat.size,
      Math.floor(stat.mtimeMs),
      row.id,
    );
    for (const original of new Set(source.originals)) if (original !== out) fs.rmSync(original, { force: true });
    progress(1);
  } finally {
    fs.rmSync(temp, { force: true });
    fs.rmSync(work, { recursive: true, force: true });
  }
}

/** Jeux à convertir : le jeu et ses parties (autres disques) dont le fichier est convertible. */
function rowsToConvert(row) {
  const parts = db.prepare('SELECT * FROM games WHERE parent_id = ? ORDER BY id').all(row.id);
  return [row, ...parts].filter((r) => !r.is_folder && CHD_SOURCE_RE.test(r.file_name));
}

/**
 * Programme la conversion en CHD du jeu [gameId] et de ses autres disques ; renvoie la tâche (null :
 * déjà programmée). Vérifie d'abord chaque fichier (pistes présentes, image dans l'archive, CHD du même
 * nom absent). Fiches mises à jour par un scan du système à la fin.
 */
export function queueChdConversion(gameId) {
  const row = requireGameRow(gameId);
  const system = requireSystem(row.system_id);
  const disc = chdDisc(system);
  if (!disc) throw new HttpError(400, 'errors.chdUnsupportedSystem', { system: system.name });
  const rows = rowsToConvert(row);
  if (!rows.length) throw new HttpError(400, 'errors.chdNotConvertible', { file: row.file_name });
  if (!chdmanAvailable()) throw new HttpError(500, 'errors.chdmanMissing');
  for (const r of rows) {
    sourceOf(r);
    const name = `${r.file_name.replace(/\.[^.]+$/, '')}.chd`;
    if (fs.existsSync(path.join(path.dirname(gameFilePath(r)), name))) throw new HttpError(409, 'errors.chdExists', { file: name });
  }
  return enqueueConvert({
    key: `chd:${row.id}`,
    label: token('jobs.chdConvert', { title: row.title || row.file_name }),
    systemId: row.system_id,
    run: async (job) => {
      const controller = new AbortController();
      job.cancel = () => controller.abort();
      try {
        // Fiches relues : fichier peut-être changé depuis la demande.
        const work = rows.map((r) => getGameRow(r.id)).filter((r) => r && CHD_SOURCE_RE.test(r.file_name)).map((r) => ({ row: r, source: sourceOf(r) }));
        // Avancement en octets d'origine (archive : extraction, puis compression).
        const weight = ({ source }) => source.size * (source.entries ? 2 : 1);
        job.total = work.reduce((n, w) => n + weight(w), 0);
        job.done = 0;
        let before = 0;
        for (const w of work) {
          if (controller.signal.aborted) break;
          await convertGame(w.row, w.source, disc, (p) => (job.done = before + Math.round(p * weight(w))), controller.signal);
          before += weight(w);
          job.done = before;
        }
      } finally {
        job.cancel = null;
        scanSystem(row.system_id);
      }
    },
  });
}
