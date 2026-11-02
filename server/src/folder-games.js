// Jeux en dossiers (système « jeux en dossiers », PC Windows) : chaque sous-dossier du système est un
// jeu, téléchargé fichier par fichier par les applications (liste : chemin et taille) et lancé par
// son exécutable, choisi dans la fiche du jeu (sinon deviné au scan). Jeu compressé (folder-pack.js) :
// chaque fichier de plus de 100 Mo rangé dans sa propre archive, « X.zst » (zstd), ou « X.xz » et
// « X.zip » (compressions d'avant le zstd) ; les plus petits regroupés dans des archives zstd de
// 100 Mo au plus (avant compression), rangées dans « .romcloud-bundles » ; le tout noté dans le
// manifeste du dossier.
import fs from 'node:fs';
import path from 'node:path';
import { HttpError } from './http-error.js';

// Fichiers ignorés dans le dossier d'un jeu : envois en cours et fichiers système de Windows / macOS.
const IGNORED_RE = /(^|\/)(\.DS_Store|Thumbs\.db|desktop\.ini)$|\.(part|tmp|upload)$|^\.romcloud-packed\.json$/i;

/**
 * Manifeste d'un jeu compressé, à la racine de son dossier : { files: { chemin: { size, crc32,
 * mtime, format, bundle, offset } } } ; format « zst », « xz » ou « zip » (absent : zip) ; fichier
 * regroupé avec d'autres : [bundle], son archive (zstd), et [offset], sa place dans le contenu
 * décompressé de l'archive (fichiers mis bout à bout).
 */
export const PACK_MANIFEST = '.romcloud-packed.json';

/** Dossier des archives regroupant plusieurs fichiers, à la racine du dossier du jeu. */
export const PACK_BUNDLES = '.romcloud-bundles';

/** Identifiant d'archive regroupée valide (chiffres et lettres minuscules). */
export const BUNDLE_ID_RE = /^[0-9a-z]{1,16}$/;

/** Archive regroupée [id] du dossier [dir]. */
export const bundlePath = (dir, id) => path.join(dir, PACK_BUNDLES, `${id}.zst`);

/** Format d'une archive : « zst », « xz » ou « zip » (absent ou inconnu : zip, les premières compressions). */
export const packFormat = (format) => (format === 'zst' || format === 'xz' ? format : 'zip');

/** Archive d'un fichier compressé : extension selon son format (« .zst », « .xz », « .zip »). */
export const packExtension = (format) => `.${packFormat(format)}`;

/** Fichiers compressés du dossier [dir] (chemin d'origine -> { size, crc32, mtime, format }) ; vide sans manifeste. */
export function readPackManifest(dir) {
  try {
    const files = JSON.parse(fs.readFileSync(path.join(dir, PACK_MANIFEST), 'utf8')).files;
    return files && typeof files === 'object' ? files : {};
  } catch {
    return {};
  }
}

// Manifestes lus pour les téléchargements (dossier -> { stamp, files }) : un jeu en compte parfois
// des dizaines de milliers d'entrées, à ne pas relire à chaque fichier demandé.
const manifestCache = new Map();
const MANIFEST_CACHE_MAX = 8;

/**
 * Entrée du fichier [rel] dans le manifeste du dossier [dir] ({ size, crc32, mtime, format }), ou
 * undefined ; manifeste gardé en mémoire tant que son fichier ne change pas (lecture seule).
 */
export function packManifestEntry(dir, rel) {
  const stat = fs.statSync(path.join(dir, PACK_MANIFEST), { throwIfNoEntry: false });
  if (!stat) {
    manifestCache.delete(dir);
    return undefined;
  }
  const stamp = `${stat.mtimeMs}:${stat.size}`;
  let cached = manifestCache.get(dir);
  if (cached?.stamp !== stamp) {
    cached = { stamp, files: readPackManifest(dir) };
    manifestCache.delete(dir);
    if (manifestCache.size >= MANIFEST_CACHE_MAX) manifestCache.delete(manifestCache.keys().next().value);
  } else {
    manifestCache.delete(dir); // remis en dernier : le plus récemment utilisé
  }
  manifestCache.set(dir, cached);
  return Object.hasOwn(cached.files, rel) ? cached.files[rel] : undefined;
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
 * par chemin. Fichier compressé : chemin et taille d'origine, packed: true, format (« zst », « xz »
 * ou « zip »), packedSize (taille de son archive), crc32 ; zip : zipSize aussi (applications d'avant le
 * xz) ; regroupé : bundle, offset et bundleSize (taille de l'archive regroupée) au lieu de
 * packedSize. S'il existe aussi en clair (compression interrompue) : le fichier en clair.
 */
export function folderFiles(dir) {
  const manifest = readPackManifest(dir);
  const files = [];
  const walk = (sub) => {
    for (const entry of fs.readdirSync(path.join(dir, sub), { withFileTypes: true })) {
      const rel = sub ? `${sub}/${entry.name}` : entry.name;
      if (entry.isDirectory()) {
        if (rel !== PACK_BUNDLES) walk(rel);
      } else if (entry.isFile() && !IGNORED_RE.test(rel)) {
        const stat = fs.statSync(path.join(dir, rel));
        const ext = /\.(zst|xz|zip)$/i.exec(rel)?.[0];
        const original = ext ? rel.slice(0, -ext.length) : null;
        const packed = original ? manifest[original] : undefined;
        // Archive d'un fichier depuis regroupé (compression interrompue avant sa suppression) : ignorée.
        if (packed?.bundle) continue;
        if (packed && packExtension(packed.format) === ext.toLowerCase()) {
          const format = packFormat(packed.format);
          files.push({
            path: original,
            size: packed.size,
            // Date du fichier d'origine : la compression ne change pas le jeu (mises à jour des applications).
            mtime: packed.mtime ?? Math.floor(stat.mtimeMs),
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
  // Fichiers regroupés : listés si leur archive est là.
  const bundles = new Map();
  for (const [rel, packed] of Object.entries(manifest)) {
    if (!packed?.bundle || !BUNDLE_ID_RE.test(packed.bundle)) continue;
    if (!bundles.has(packed.bundle)) bundles.set(packed.bundle, fs.statSync(bundlePath(dir, packed.bundle), { throwIfNoEntry: false }));
    const stat = bundles.get(packed.bundle);
    if (!stat) continue;
    files.push({
      path: rel,
      size: packed.size,
      mtime: packed.mtime ?? Math.floor(stat.mtimeMs),
      packed: true,
      format: 'zst',
      bundle: packed.bundle,
      offset: packed.offset ?? 0,
      bundleSize: stat.size,
      crc32: packed.crc32,
    });
  }
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
    packedSize: files.reduce((n, f) => n + (f.bundle ? 0 : f.packed ? f.packedSize : f.size), 0) + bundleSizes(files),
    packed: files.filter((f) => f.packed).length,
    // Format des archives : « zst », « xz », « zip », « mixed » (plusieurs) ; null sans fichier compressé.
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

// Émulateurs Steam des jeux sur moteur Source (Valve), à côté de l'exécutable : REVEmu, SmartSteamEmu…
const SOURCE_STEAM_EMU_RE = /^(rev\.ini|revapi\.ini|steam_emu\.ini|smartsteamemu\.ini|bin\/steam\.dll|bin\/steamclient\.dll)$/i;

/**
 * Jeu sur moteur Source lancé par [exe] (avec les arguments [args]) : dossier de son jeu, qui a un
 * « gameinfo.txt » (« hl2 », « ep2 »…, chemin dans le dossier du jeu), ou null. Jeu choisi par
 * « -game X », sinon celui qui porte le nom de l'exécutable (« left4dead2.exe »), sinon « hl2 ».
 */
export function sourceGameDir(files, exe, args = '') {
  if (!exe) return null;
  const base = path.posix.dirname(exe) === '.' ? '' : `${path.posix.dirname(exe)}/`;
  const infos = new Map(files.map((f) => [f.path.toLowerCase(), f.path]));
  const game = /(?:^|\s)-game\s+("[^"]+"|\S+)/i.exec(args || '')?.[1]?.replace(/"/g, '');
  const names = game ? [game] : [path.posix.basename(exe).replace(/\.exe$/i, ''), 'hl2'];
  for (const name of names) {
    const info = infos.get(`${base}${name}/gameinfo.txt`.toLowerCase());
    if (info) return path.posix.dirname(info);
  }
  return null;
}

/**
 * Arguments de lancement devinés : jeu sur moteur Source livré avec un émulateur Steam, lancé sans
 * son petit lanceur : « -steam » (sinon « Steam startup failed: missing interface ») ; null sinon.
 */
export function steamLaunchArgs(files, exe) {
  if (!sourceGameDir(files, exe)) return null;
  const base = path.posix.dirname(exe) === '.' ? '' : `${path.posix.dirname(exe)}/`;
  const emulated = files.some((f) => f.path.toLowerCase().startsWith(base.toLowerCase()) && SOURCE_STEAM_EMU_RE.test(f.path.slice(base.length)));
  return emulated ? '-steam' : null;
}

/** AppID Steam du « gameinfo.txt » d'un jeu Source (« SteamAppId 220 ») ; null sinon. */
export function sourceAppId(text) {
  return /^\s*SteamAppId\s+"?(\d{2,10})"?/im.exec(String(text || ''))?.[1] ?? null;
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

/** Taille des archives regroupées des fichiers [files] (chacune comptée une fois). */
export function bundleSizes(files) {
  const sizes = new Map(files.filter((f) => f.bundle).map((f) => [f.bundle, f.bundleSize]));
  return [...sizes.values()].reduce((n, s) => n + s, 0);
}

function packFormatOf(files) {
  const formats = new Set(files.filter((f) => f.packed).map((f) => f.format));
  return formats.size === 0 ? null : formats.size > 1 ? 'mixed' : [...formats][0];
}
