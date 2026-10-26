// Compression des jeux PC par un outil en ligne de commande (xz, zstd) : contenu envoyé sur son
// entrée, archive lue sur sa sortie ; décompression d'une archive en flux.
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import zlib from 'node:zlib';

/**
 * Outil [bin] : [compressArgs] (entrée -> sortie), [decompressArgs] (suivis du chemin de l'archive,
 * vers la sortie) ; [name] dans les messages d'erreur.
 */
export function cliCodec(name, bin, compressArgs, decompressArgs) {
  let available;

  function run(args, signal) {
    const child = spawn(bin, args, { stdio: ['pipe', 'pipe', 'pipe'], signal, windowsHide: true });
    let stderr = '';
    child.stderr.on('data', (d) => (stderr = (stderr + d).slice(-2000)));
    const done = new Promise((resolve, reject) => {
      child.on('error', reject);
      child.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`${name} : ${stderr.trim() || `code ${code}`}`))));
    });
    // Erreurs d'écriture vers l'outil arrêté (annulation, échec) : rapportées par « done ».
    child.stdin.on('error', () => {});
    return { child, done };
  }

  return {
    /** Outil présent (vérifié une fois). */
    available() {
      available ??= spawnSync(bin, ['--version'], { stdio: 'ignore' }).status === 0;
      return available;
    },

    /**
     * Compresse le flux [input] dans le fichier [dest] ; [onBytes] reçoit les octets d'origine lus.
     * Renvoie { size, crc32 } du contenu d'origine.
     */
    async compress(input, dest, { onBytes = () => {}, signal } = {}) {
      const { child, done } = run(compressArgs, signal);
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
    },

    /** Contenu décompressé de l'archive [file], en flux (erreur de l'outil : flux détruit avec l'erreur). */
    decompressStream(file, signal) {
      const { child, done } = run([...decompressArgs, file], signal);
      child.stdin.end();
      done.catch((err) => child.stdout.destroy(err));
      return child.stdout;
    },
  };
}
