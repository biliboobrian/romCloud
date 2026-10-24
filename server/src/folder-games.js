// Jeux en dossiers (système « jeux en dossiers », PC Windows) : chaque sous-dossier du système est un
// jeu, téléchargé fichier par fichier par les applications (liste : chemin et taille) et lancé par
// son exécutable, choisi dans la fiche du jeu (sinon deviné au scan). Jeu compressé (folder-pack.js) :
// chaque fichier « X » rangé dans sa propre archive, « X.xz » (xz) ou « X.zip » (compressions d'avant
// le xz), listée dans le manifeste du dossier.
import fs from 'node:fs';
import path from 'node:path';
import { HttpError } from './http-error.js';

// Fichiers ignorés dans le dossier d'un jeu : envois en cours et fichiers système de Windows / macOS.
const IGNORED_RE = /(^|\/)(\.DS_Store|Thumbs\.db|desktop\.ini)$|\.(part|tmp|upload)$|^\.romcloud-packed\.json$/i;

/**
 * Manifeste d'un jeu compressé, à la racine de son dossier : { files: { chemin: { size, crc32,
 * mtime, format } } } ; format « xz » ou « zip » (absent : zip).
 */
export const PACK_MANIFEST = '.romcloud-packed.json';

/** Archive d'un fichier compressé : extension selon son format (« .xz », « .zip »). */
export const packExtension = (format) => (format === 'xz' ? '.xz' : '.zip');

/** Fichiers compressés du dossier [dir] (chemin d'origine -> { size, crc32, mtime, format }) ; vide sans manifeste. */
export function readPackManifest(dir) {
  try {
    const files = JSON.parse(fs.readFileSync(path.join(dir, PACK_MANIFEST), 'utf8')).files;
    return files && typeof files === 'object' ? files : {};
  } catch {
    return {};
  }
}

// Exécutables qui ne lancent pas le jeu : installation, désinstallation, prérequis, rapports d'erreur.
const NOT_GAME_RE = new RegExp(
  [
    // Nom qui commence par un de ces mots (« unins000.exe », « vc_redist.x64.exe », « CrashReporter.exe »).
    String.raw`(^|/)(unins|uninst|setup|install|vc_?redist|vcredist|dxsetup|dxwebsetup|directx|dotnet|ue[34]?prereq|crash|bugreport|easyanticheat_setup|physx|oalinst)[^/]*\.exe$`,
    // Dossier de prérequis (« _CommonRedist/… », « Redist/… »).
    String.raw`(^|/)(_?commonredist|redist|redistributables?|directx|prereq[^/]*)/`,
  ].join('|'),
  'i',
);

/**
 * Fichiers du dossier [dir] (sous-dossiers compris) : { path (séparateur « / »), size, mtime }, triés
 * par chemin. Fichier compressé : chemin et taille d'origine, packed: true, format (« xz » ou
 * « zip »), packedSize (taille de son archive), crc32 ; zip : zipSize aussi (applications d'avant le
 * xz). S'il existe aussi en clair (compression interrompue) : le fichier en clair.
 */
export function folderFiles(dir) {
  const manifest = readPackManifest(dir);
  const files = [];
  const walk = (sub) => {
    for (const entry of fs.readdirSync(path.join(dir, sub), { withFileTypes: true })) {
      const rel = sub ? `${sub}/${entry.name}` : entry.name;
      if (entry.isDirectory()) walk(rel);
      else if (entry.isFile() && !IGNORED_RE.test(rel)) {
        const stat = fs.statSync(path.join(dir, rel));
        const ext = /\.(xz|zip)$/i.exec(rel)?.[0];
        const original = ext ? rel.slice(0, -ext.length) : null;
        const packed = original ? manifest[original] : undefined;
        if (packed && packExtension(packed.format) === ext.toLowerCase()) {
          const format = packed.format === 'xz' ? 'xz' : 'zip';
          files.push({
            path: original,
            size: packed.size,
            mtime: Math.floor(stat.mtimeMs),
            packed: true,
            format,
            packedSize: stat.size,
            crc32: packed.crc32,
            ...(format === 'zip' ? { zipSize: stat.size } : {}),
          });
        } else {
          files.push({ path: rel, size: stat.size, mtime: Math.floor(stat.mtimeMs) });
        }
      }
    }
  };
  walk('');
  const flat = new Set(files.filter((f) => !f.packed).map((f) => f.path));
  return files.filter((f) => !f.packed || !flat.has(f.path)).sort((a, b) => a.path.localeCompare(b.path));
}

/**
 * Taille totale (fichiers en clair) et date la plus récente des fichiers d'un jeu en dossier
 * (détection des changements au scan) ; packedSize : taille à télécharger (archives des fichiers
 * compressés, les autres tels quels), packed : nombre de fichiers compressés.
 */
export function folderStats(dir, files = folderFiles(dir)) {
  return {
    size: files.reduce((n, f) => n + f.size, 0),
    mtime: files.reduce((m, f) => Math.max(m, f.mtime), 0),
    count: files.length,
    packedSize: files.reduce((n, f) => n + (f.packed ? f.packedSize : f.size), 0),
    packed: files.filter((f) => f.packed).length,
    // Format des archives : « xz », « zip », « mixed » (les deux) ; null sans fichier compressé.
    format: packFormatOf(files),
  };
}

/**
 * Exécutables du jeu, le plus probable en premier : hors installation, désinstallation et
 * prérequis ; puis le moins profond dans le dossier ; puis le plus gros.
 */
export function exeCandidates(files) {
  const depth = (p) => p.split('/').length;
  return files
    .filter((f) => /\.exe$/i.test(f.path))
    .sort((a, b) => Number(NOT_GAME_RE.test(a.path)) - Number(NOT_GAME_RE.test(b.path)) || depth(a.path) - depth(b.path) || b.size - a.size)
    .map((f) => f.path);
}

// Prérequis à installer avant de jouer (DirectX, Visual C++, .NET, PhysX, OpenAL, XNA…).
const PREREQ_RE = new RegExp(
  [
    String.raw`(^|/)(vc_?redist|vcredist|dxsetup|dxwebsetup|directx|dotnet|ndp\d|netfx|ue[34]?prereq|physx|oalinst|xnafx|easyanticheat_setup)[^/]*\.exe$`,
    String.raw`(^|/)(_?commonredist|redist|redistributables?|directx|prereq[^/]*)/[^/]*\.exe$`,
  ].join('|'),
  'i',
);

/** Prérequis probables du jeu (DirectX, Visual C++…), du moins profond au plus profond, puis par chemin. */
export function prereqsOf(files) {
  return exeCandidates(files)
    .filter((p) => PREREQ_RE.test(p))
    .sort((a, b) => a.split('/').length - b.split('/').length || a.localeCompare(b));
}

/** Désinstalleur probable du jeu (« unins000.exe », « uninstall.exe »…, le moins profond), ou null. */
export function uninstallerOf(files) {
  return exeCandidates(files)
    .filter((p) => /(^|\/)unins[^/]*\.exe$/i.test(p))
    .sort((a, b) => a.split('/').length - b.split('/').length)[0] ?? null;
}

/**
 * Chemin d'un fichier du jeu à partir de son chemin relatif (« Bin/Game.exe ») : refusé s'il sort du
 * dossier du jeu (« .. », chemin absolu).
 */
export function fileInFolder(dir, rel) {
  const clean = String(rel || '').replace(/\\/g, '/');
  if (!clean || clean.startsWith('/') || /^[a-z]:/i.test(clean) || clean.split('/').some((p) => p === '..' || p === '')) {
    throw new HttpError(400, 'errors.invalidFilePath');
  }
  const full = path.resolve(dir, clean);
  if (!full.startsWith(path.resolve(dir) + path.sep)) throw new HttpError(400, 'errors.invalidFilePath');
  return full;
}

function packFormatOf(files) {
  const formats = new Set(files.filter((f) => f.packed).map((f) => f.format));
  return formats.size === 0 ? null : formats.size > 1 ? 'mixed' : [...formats][0];
}
