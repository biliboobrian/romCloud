// Images allégées pour les applications : jaquettes, captures et images des systèmes réduites et
// converties en WebP (qualité 80, transparence gardée), à la première demande puis gardées sur le
// disque à côté de l'original (dossier « _variants »). Les grilles et listes demandent la vignette,
// la fiche du jeu la version moyenne ; l'original reste servi sans demande de taille.
import fs from 'node:fs';
import path from 'node:path';

/** Tailles proposées : largeur (et hauteur) maximales en pixels ; jamais agrandi. */
export const VARIANTS = { thumb: 400, medium: 800 };
export const VARIANTS_DIR = '_variants';
const QUALITY = 80;

let sharpLoaded;
/** Bibliothèque d'images (sharp), ou null si elle n'a pas pu être chargée (original servi). */
async function sharp() {
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

const pending = new Map(); // fichier à produire -> promesse (une seule conversion à la fois par image)

/**
 * Version [variant] (« thumb », « medium ») de l'image [file], rangée dans [dir] : chemin du WebP,
 * produit s'il n'existe pas encore ; null pour une taille inconnue, un fichier absent ou illisible
 * (l'original est alors servi). Le nom reprend la date et la taille de l'original : une image
 * remplacée (nouveau scraping, captures renumérotées) ne réutilise jamais une ancienne version.
 */
export async function mediaVariant(file, variant, dir) {
  const width = VARIANTS[variant];
  if (!width) return null;
  let stat;
  try {
    stat = fs.statSync(file);
  } catch {
    return null;
  }
  const prefix = `${path.basename(file)}.${variant}.`;
  const target = path.join(dir, `${prefix}${Math.round(stat.mtimeMs)}-${stat.size}.webp`);
  if (fs.existsSync(target)) return target;
  if (!pending.has(target)) {
    pending.set(target, (async () => {
      const lib = await sharp();
      if (!lib) return null;
      try {
        fs.mkdirSync(dir, { recursive: true });
        const part = `${target}.${process.pid}.part`;
        await lib(file, { animated: false })
          .rotate()
          .resize({ width, height: width * 2, fit: 'inside', withoutEnlargement: true })
          .webp({ quality: QUALITY })
          .toFile(part);
        fs.renameSync(part, target);
        // Versions plus anciennes de la même image (original remplacé) : retirées.
        for (const old of fs.readdirSync(dir)) {
          if (old.startsWith(prefix) && path.join(dir, old) !== target) fs.rmSync(path.join(dir, old), { force: true });
        }
        return target;
      } catch (e) {
        console.warn(`Image réduite impossible (${file}) : ${e.message}`);
        return null;
      }
    })().finally(() => pending.delete(target)));
  }
  return pending.get(target);
}
