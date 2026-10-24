// Compression xz (LZMA2) des jeux PC, par l'outil « xz » (XZ Utils, XZ_PATH pour un autre
// emplacement) : le format qui fait gagner le plus de place sur les fichiers des jeux (exécutables,
// données), devant zstd, brotli et deflate. Réglage le plus fort (-9e) ; plusieurs fils d'exécution
// (-T0, mémoire limitée par xz), d'où des blocs indépendants que les applications décompressent en parallèle.
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import zlib from 'node:zlib';

const XZ = process.env.XZ_PATH || 'xz';

let available;

/** Outil xz présent (vérifié une fois). */
export function xzAvailable() {
  available ??= spawnSync(XZ, ['--version'], { stdio: 'ignore' }).status === 0;
  return available;
}

function run(args, signal) {
  const child = spawn(XZ, args, { stdio: ['pipe', 'pipe', 'pipe'], signal, windowsHide: true });
  let stderr = '';
  child.stderr.on('data', (d) => (stderr = (stderr + d).slice(-2000)));
  const done = new Promise((resolve, reject) => {
    child.on('error', reject);
    child.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`xz : ${stderr.trim() || `code ${code}`}`))));
  });
  // Erreurs d'écriture vers xz arrêté (annulation, échec) : rapportées par « done ».
  child.stdin.on('error', () => {});
  return { child, done };
}

/**
 * Compresse le flux [input] dans le fichier [dest] (.xz) ; [onBytes] reçoit les octets d'origine
 * lus. Renvoie { size, crc32 } du contenu d'origine.
 */
export async function xzCompress(input, dest, { onBytes = () => {}, signal } = {}) {
  const { child, done } = run(['-9e', '-T0', '-c', '-q'], signal);
  let crc = 0;
  let size = 0;
  const count = new Transform({
    transform(chunk, _enc, cb) {
      crc = zlib.crc32(chunk, crc);
      size += chunk.length;
      onBytes(chunk.length);
      cb(null, chunk);
    },
  });
  try {
    await Promise.all([pipeline(input, count, child.stdin), pipeline(child.stdout, fs.createWriteStream(dest)), done]);
  } catch (err) {
    child.kill();
    fs.rmSync(dest, { force: true });
    throw err;
  }
  return { size, crc32: (crc >>> 0).toString(16).padStart(8, '0') };
}

/** Contenu décompressé du fichier .xz [file], en flux (erreur de xz : flux détruit avec l'erreur). */
export function xzDecompressStream(file, signal) {
  const { child, done } = run(['-dc', '-T0', '-q', file], signal);
  child.stdin.end();
  done.catch((err) => child.stdout.destroy(err));
  return child.stdout;
}
