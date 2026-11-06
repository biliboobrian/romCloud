// Images gardées par le serveur au plus léger : jaquettes, captures d'écran et images des systèmes.
//  - Originaux (scraping, envoi depuis l'administration) : réduits à MAX_SIZE px au plus, enregistrés
//    dans le plus léger de ces formats : WebP avec perte (qualité 90 : différence invisible), WebP
//    sans perte (essayé pour les petites images : captures des consoles, nettes et souvent plus
//    légères qu'avec perte), ou l'original s'il est déjà plus léger et n'a pas à être réduit ;
//  - versions réduites pour les applications (media-variants.js) : même choix, qualité 75 ;
//  - images déjà gardées : recompressées par une tâche lancée depuis l'administration.
import fs from 'node:fs';
import path from 'node:path';
import { db } from './db.js';
import { config } from './config.js';
import { SCREENSHOT_TYPES } from './library.js';

/** Côté le plus long d'un original, en pixels (jaquettes de ScreenScraper : souvent 1000 à 2000 px). */
export const MAX_SIZE = 1600;
export const ORIGINAL_QUALITY = 90;
/** Images d'au plus ce nombre de pixels : WebP sans perte essayé aussi (captures 8 à 32 bits, PSP). */
const LOSSLESS_PIXELS = 640 * 544;
/** Gain minimal pour remplacer une image déjà gardée (5 %). */
const MIN_GAIN = 0.95;

let sharpLoaded;
/** Bibliothèque d'images (sharp), ou null si elle n'a pas pu être chargée (images gardées telles quelles). */
export async function loadSharp() {
  if (sharpLoaded === undefined) {
    try {
      sharpLoaded = (await import('sharp')).default;
    } catch (e) {
      console.warn(`Images réduites indisponibles (sharp) : ${e.message}`);
      sharpLoaded = null;
    }
  }
  return sharpLoaded;
}

/**
 * Version la plus légère de l'image [input] (Buffer ou chemin) tenant dans [width] × [height] px :
 * { buffer, ext: '.webp', original: false }, ou { original: true } quand l'original (taille
 * [originalSize]) est déjà le plus léger sans avoir à être réduit ni tourné ; null si l'image est
 * illisible, animée ou sharp absent.
 */
export async function smallestEncoding(input, { width, height = width, quality, originalSize }) {
  const lib = await loadSharp();
  if (!lib) return null;
  let meta;
  try {
    // Lue en mode animé : nombre d'images d'un GIF (une seule sinon).
    meta = await lib(input, { animated: true }).metadata();
  } catch {
    return null;
  }
  // GIF animé : gardé tel quel (les applications n'en affichent que la première image, comme avant).
  if ((meta.pages || 1) > 1 || !meta.width || !meta.height) return null;
  const turned = (meta.orientation || 1) >= 5;
  const [w, h] = turned ? [meta.pageHeight || meta.height, meta.width] : [meta.width, meta.pageHeight || meta.height];
  const fits = w <= width && h <= height;
  const box = { width, height, fit: 'inside', withoutEnlargement: true };
  const base = () => lib(input, { animated: false }).rotate().resize(box);
  const lossy = await base().webp({ quality, effort: 6 }).toBuffer({ resolveWithObject: true });
  const candidates = [lossy.data];
  if (lossy.info.width * lossy.info.height <= LOSSLESS_PIXELS) candidates.push(await base().webp({ lossless: true, effort: 6 }).toBuffer());
  const best = candidates.reduce((a, b) => (b.length < a.length ? b : a));
  if (fits && (meta.orientation || 1) === 1 && originalSize != null && originalSize <= best.length) return { original: true };
  return { buffer: best, ext: '.webp', original: false };
}

/** Image de MAX_SIZE px au plus, sans rotation à appliquer. */
async function fitsAsIs(buffer) {
  const lib = await loadSharp();
  const meta = lib && (await lib(buffer).metadata().catch(() => null));
  return Boolean(meta && meta.width <= MAX_SIZE && meta.height <= MAX_SIZE && (meta.orientation || 1) === 1);
}

/**
 * Original à enregistrer pour l'image [buffer] (extension [ext]) : { buffer, ext } la plus légère
 * (WebP, au plus MAX_SIZE px), sinon l'image telle quelle.
 */
export async function optimizeOriginal(buffer, ext) {
  // Déjà en WebP sans avoir à être réduite : gardée (un nouvel encodage avec perte la dégraderait à chaque passe).
  if (String(ext).toLowerCase() === '.webp' && (await fitsAsIs(buffer))) return { buffer, ext };
  const best = await smallestEncoding(buffer, { width: MAX_SIZE, quality: ORIGINAL_QUALITY, originalSize: buffer.length }).catch(() => null);
  return best && !best.original ? { buffer: best.buffer, ext: best.ext } : { buffer, ext };
}

// ---- Images déjà gardées ----

const mediaColumns = () => ['boxart', ...SCREENSHOT_TYPES];
const VARIANTS_DIR = '_variants';

/** Versions réduites d'une image (dossier _variants) : retirées quand l'original change de nom. */
function removeVariants(dir, file) {
  const variants = path.join(dir, VARIANTS_DIR);
  for (const old of fs.existsSync(variants) ? fs.readdirSync(variants) : []) {
    if (old.startsWith(`${file}.`)) fs.rmSync(path.join(variants, old), { force: true });
  }
}

/** Images gardées : [{ dir, file, save(newFile) }] (jaquettes et captures des jeux, images des systèmes). */
function storedImages() {
  const list = [];
  const columns = mediaColumns();
  const games = db.prepare(`SELECT id, ${columns.join(', ')} FROM games WHERE ${columns.map((c) => `${c} IS NOT NULL`).join(' OR ')}`).all();
  for (const row of games) {
    for (const column of columns) {
      if (!row[column]) continue;
      list.push({
        dir: path.join(config.mediaDir, String(row.id)),
        file: row[column],
        name: () => `${column}.webp`,
        save: (file) => db.prepare(`UPDATE games SET ${column} = ? WHERE id = ?`).run(file, row.id),
      });
    }
  }
  for (const row of db.prepare('SELECT id, image FROM systems WHERE image IS NOT NULL').all()) {
    list.push({
      dir: path.join(config.mediaDir, 'systems'),
      file: row.image,
      // Nom changé à chaque envoi : il sert à invalider les caches des applications.
      name: () => `${row.id}-${Date.now()}.webp`,
      save: (file) => db.prepare('UPDATE systems SET image = ? WHERE id = ?').run(file, row.id),
    });
  }
  return list;
}

/**
 * Recompresse une image gardée si sa version la plus légère gagne au moins 5 % : nouveau fichier
 * (.webp) enregistré, ancien et ses versions réduites retirés. Renvoie les octets gagnés.
 */
export async function recompressImage(item) {
  const source = path.join(item.dir, item.file);
  let buffer;
  try {
    buffer = fs.readFileSync(source);
  } catch {
    return 0;
  }
  const best = await optimizeOriginal(buffer, path.extname(item.file));
  if (best.buffer === buffer || best.buffer.length > buffer.length * MIN_GAIN) return 0;
  const file = path.extname(item.file).toLowerCase() === best.ext ? item.file : item.name();
  const part = path.join(item.dir, `${file}.part`);
  fs.writeFileSync(part, best.buffer);
  fs.renameSync(part, path.join(item.dir, file));
  if (file !== item.file) {
    item.save(file);
    fs.rmSync(source, { force: true });
  }
  removeVariants(item.dir, item.file);
  return buffer.length - best.buffer.length;
}

/** Recompression de toutes les images gardées (tâche) : job.done / job.total en images, octets gagnés dans job.saved. */
export async function recompressAll(job) {
  const items = storedImages();
  Object.assign(job, { total: items.length, done: 0, saved: 0 });
  for (const item of items) {
    if (job.cancelRequested) break;
    try {
      job.saved += await recompressImage(item);
    } catch (err) {
      console.warn(`[images] ${item.dir}/${item.file} : ${err.message}`);
    }
    job.done++;
  }
  console.log(`[images] ${job.done} image(s) examinée(s), ${Math.round(job.saved / 1048576)} Mo gagnés`);
}
