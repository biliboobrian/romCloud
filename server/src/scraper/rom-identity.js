import { readZipEntries } from '../zip.js';

// ROM contenue dans une archive .zip : les bases (ScreenScraper, DAT libretro) référencent le
// fichier de la ROM (« Jeu.cpr », son CRC), pas celui de l'archive. Le nom, la taille et le CRC32
// de chaque fichier sont lus dans le répertoire central du .zip, sans rien décompresser.

// Fichiers d'accompagnement ignorés pour choisir la ROM de l'archive.
const JUNK = /\.(txt|nfo|diz|jpe?g|png|gif|bmp|pdf|htm|html|url|xml|md)$/i;

/** Fichiers d'un .zip ({ name, size, crc32 }) ; [] si l'archive est illisible. */
export function zipEntries(file) {
  return readZipEntries(file).map(({ name, size, crc32 }) => ({ name, size, crc32 }));
}

/**
 * ROM d'une archive .zip : le plus gros fichier qui n'est pas un document d'accompagnement ;
 * null si ce n'est pas un .zip ou s'il est vide / illisible.
 */
export function zipMainEntry(file) {
  if (!/\.zip$/i.test(file)) return null;
  const entries = zipEntries(file);
  const roms = entries.filter((e) => !JUNK.test(e.name));
  const list = roms.length ? roms : entries;
  if (!list.length) return null;
  const main = list.reduce((a, b) => (b.size > a.size ? b : a));
  return { ...main, name: main.name.split('/').pop() };
}
