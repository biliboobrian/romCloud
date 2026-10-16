// Paquets PS4 (fpkg) convertis au scan en .zip du dossier du jeu (shadPS4 ne lit pas les .pkg) : en
// tâche de fond, une conversion à la fois dans un fil d'exécution à part ; archive écrite à côté sous
// un nom caché (« .Jeu.zip.part », ignoré par le scan) puis vérifiée ; la fiche du jeu passe au .zip
// (titre, images et scraping gardés) et le .pkg est supprimé. Mise à jour : dossier CUSAxxxxx-UPDATE.
// Les DLC restent en .pkg (shadPS4 ne les lit pas non plus).
import fs from 'node:fs';
import path from 'node:path';
import { Worker } from 'node:worker_threads';
import { db } from './db.js';
import { groupSystem } from './groups.js';
import { token } from './i18n.js';
import { enqueueConvert } from './jobs.js';
import { getGameRow, titleFromFileName } from './library.js';
import { ps4PkgInfo } from './ps4-pkg.js';
import { requireSystem, systemDir } from './systems.js';

// Échecs : paquet (chemin) -> « taille:date » ; pas de nouvel essai tant que le fichier ne change pas.
const failed = new Map();

const fileKey = (file) => {
  const stat = fs.statSync(file);
  return `${stat.size}:${Math.floor(stat.mtimeMs)}`;
};

/** Programme la conversion des paquets PS4 (jeux et mises à jour) d'un système. */
export function queuePs4Conversions(systemId) {
  const dir = systemDir(requireSystem(systemId));
  const rows = db.prepare("SELECT id, file_name FROM games WHERE system_id = ? AND lower(file_name) LIKE '%.pkg'").all(systemId);
  for (const row of rows) {
    const file = path.join(dir, row.file_name);
    try {
      if (failed.get(file) === fileKey(file)) continue;
    } catch {
      continue;
    }
    const info = ps4PkgInfo(file);
    if (info?.category !== 'gd' && info?.category !== 'gp') continue;
    enqueueConvert({
      key: file,
      label: token('jobs.ps4Convert', { file: row.file_name }),
      systemId,
      run: (job) => convert(job, systemId, row.id, file),
    });
  }
}

/** Extraction et vérification dans le fil d'exécution ; supprime l'archive partielle si elle échoue. */
function extract(job, pkgFile, zipFile) {
  return new Promise((resolve, reject) => {
    let result = null;
    let error = null;
    const worker = new Worker(new URL('./ps4-pkg-worker.js', import.meta.url), { workerData: { pkgFile, zipFile } });
    job.cancel = () => worker.terminate();
    worker.on('message', (m) => {
      if (m.type === 'progress') Object.assign(job, { done: m.done, total: m.total });
      else if (m.type === 'done') result = m.result;
    });
    worker.on('error', (err) => {
      error = err;
    });
    worker.on('exit', (code) => {
      job.cancel = null;
      if (result) return resolve(result);
      fs.rmSync(zipFile, { force: true });
      reject(error || new Error(job.cancelRequested ? 'cancelled' : `exit code ${code}`));
    });
  });
}

async function convert(job, systemId, gameId, pkgFile) {
  const pkgName = path.basename(pkgFile);
  const zipName = pkgName.replace(/\.pkg$/i, '.zip');
  const target = path.join(path.dirname(pkgFile), zipName);
  const temp = path.join(path.dirname(pkgFile), `.${zipName}.part`);
  try {
    if (fs.existsSync(target)) throw new Error(`${zipName} existe déjà`);
    const result = await extract(job, pkgFile, temp);
    // Jeu supprimé ou renommé pendant la conversion : archive abandonnée.
    const row = getGameRow(gameId);
    if (!row || row.file_name !== pkgName || !fs.existsSync(pkgFile)) {
      fs.rmSync(temp, { force: true });
      throw new Error(`${pkgName} a changé pendant la conversion`);
    }
    fs.renameSync(temp, target);
    const stat = fs.statSync(target);
    // Titre tiré du nom du paquet (pas encore scrapé ni modifié) : celui du jeu (param.sfo).
    const title = result.title && row.title === titleFromFileName(pkgName) ? result.title : row.title;
    db.prepare("UPDATE games SET file_name = ?, size = ?, mtime = ?, crc32 = NULL, md5 = NULL, title = ?, updated_at = datetime('now') WHERE id = ?").run(
      zipName,
      stat.size,
      Math.floor(stat.mtimeMs),
      title,
      gameId,
    );
    fs.rmSync(pkgFile, { force: true });
    groupSystem(systemId);
  } catch (err) {
    if (!job.cancelRequested) {
      try {
        failed.set(pkgFile, fileKey(pkgFile));
      } catch {
        /* paquet disparu */
      }
    }
    throw err;
  }
}
