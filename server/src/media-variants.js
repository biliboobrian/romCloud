// Images allégées pour les applications : jaquettes, captures et images des systèmes réduites et
// converties en WebP (qualité 75, transparence gardée ; petites images aussi essayées sans perte,
// la plus légère gardée : media-optimize.js), à la première demande puis gardées sur le disque à
// côté de l'original (dossier « _variants »). Original déjà plus léger sans réduction (capture d'une
// console 8 bits, jaquette déjà petite) : servi tel quel, marqué par un fichier vide « .same ».
// Les grilles et listes demandent la vignette, la fiche du jeu la version moyenne ; l'original reste servi sans demande de taille. Icône
// (« icon ») : carré PNG de 256 px, image entière sur fond transparent (raccourci d'un jeu sur le
// bureau de Windows, glissé tel quel dans un fichier .ico).
import fs from 'node:fs';
import path from 'node:path';
import { loadSharp, smallestEncoding } from './media-optimize.js';

/** Tailles proposées : largeur (et hauteur) maximales en pixels ; jamais agrandi. */
export const VARIANTS = { thumb: 400, medium: 800, icon: 256 };
export const VARIANTS_DIR = '_variants';
const QUALITY = 75;

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
  const icon = variant === 'icon';
  const stamp = `${prefix}${Math.round(stat.mtimeMs)}-${stat.size}`;
  const target = path.join(dir, `${stamp}.${icon ? 'png' : 'webp'}`);
  const same = path.join(dir, `${stamp}.same`);
  if (fs.existsSync(target)) return target;
  if (fs.existsSync(same)) return file;
  if (!pending.has(target)) {
    pending.set(target, (async () => {
      const lib = await loadSharp();
      if (!lib) return null;
      try {
        fs.mkdirSync(dir, { recursive: true });
        const part = `${target}.${process.pid}.part`;
        let result = target;
        if (icon) {
          await lib(file, { animated: false }).rotate()
            .resize({ width, height: width, fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } }).png().toFile(part);
          fs.renameSync(part, target);
        } else {
          const best = await smallestEncoding(file, { width, height: width * 2, quality: QUALITY, originalSize: stat.size });
          if (!best) return null;
          if (best.original) {
            fs.writeFileSync(same, '');
            result = file;
          } else {
            fs.writeFileSync(part, best.buffer);
            fs.renameSync(part, target);
          }
        }
        // Versions plus anciennes de la même image (original remplacé) : retirées.
        const kept = result === file ? same : target;
        for (const old of fs.readdirSync(dir)) {
          if (old.startsWith(prefix) && path.join(dir, old) !== kept) fs.rmSync(path.join(dir, old), { force: true });
        }
        return result;
      } catch (e) {
        console.warn(`Image réduite impossible (${file}) : ${e.message}`);
        return null;
      }
    })().finally(() => pending.delete(target)));
  }
  return pending.get(target);
}
