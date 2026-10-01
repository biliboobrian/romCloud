// Fonctions pures (sans Electron) du moteur d'émulation intégré : cœurs du buildbot libretro,
// ROMs compressées, arguments du moteur (romcloud-player.exe).
const path = require('node:path');

const BUILDBOT = 'https://buildbot.libretro.com/nightly/windows/x86_64/latest';

/** Nom de cœur utilisable dans une URL ou un nom de fichier (« mupen64plus_next »). */
function validCore(core) {
  return /^[A-Za-z0-9_-]+$/.test(String(core || ''));
}

const coreDllName = (core) => `${core}_libretro.dll`;
const coreUrl = (core) => `${BUILDBOT}/${coreDllName(core)}.zip`;

/** Cœurs qui lisent eux-mêmes les archives (arcade : jeux = ensembles de fichiers .zip, DOS). */
const ARCHIVE_CORES = ['fbneo', 'fbalpha', 'mame', 'dosbox', 'neocd'];

/** RetroArch décompresse les .zip avant de les passer au cœur, sauf pour ces cœurs : idem ici. */
function needsExtraction(core, file) {
  return path.extname(file).toLowerCase() === '.zip' && !ARCHIVE_CORES.some((prefix) => core.startsWith(prefix));
}

/**
 * Options du cœur imposées par le jeu, appliquées sans valeur choisie par l'utilisateur :
 * cartouche Amstrad GX4000 / CPC+ (.cpr) avec cap32 -> modèle CPC 6128+, seul à les lire.
 */
function gameOptionDefaults(core, file) {
  if (core === 'cap32' && path.extname(file).toLowerCase() === '.cpr') return { cap32_model: '6128+ (experimental)' };
  return {};
}

/** Fichier principal d'un jeu à plusieurs fichiers (liste de disques, image CD), sinon le plus gros. */
const MAIN_EXTENSIONS = ['.m3u', '.cue', '.gdi', '.ccd', '.chd', '.iso'];

function mainEntry(entries) {
  const files = entries.filter((e) => !e.name.endsWith('/'));
  for (const ext of MAIN_EXTENSIONS) {
    const found = files.find((e) => e.name.toLowerCase().endsWith(ext));
    if (found) return found;
  }
  return files.reduce((best, e) => (!best || e.size > best.size ? e : best), null);
}

/** Chemin d'extraction sûr (pas de « .. » ni de chemin absolu dans l'archive). */
function safeEntryPath(dir, name) {
  const parts = name.split(/[\\/]+/).filter((p) => p && p !== '.' && p !== '..' && !/^[A-Za-z]:$/.test(p));
  return path.join(dir, ...parts);
}

/**
 * Arguments de romcloud-player.exe. `stateName` : nom de l'état de sauvegarde (celui du jeu, même
 * si la ROM passée est extraite d'un .zip) ; `resume` : reprend la partie à cet état.
 */
function playerArgs({ dll, rom, systemDir, saveDir, stateDir, stateName, optionsFile, title, language, windowed, resume, optionDefaults = {} }) {
  const args = [
    '--core', dll,
    '--rom', rom,
    '--system-dir', systemDir,
    '--save-dir', saveDir,
    '--state-dir', stateDir,
    '--options', optionsFile,
    '--title', title,
    '--lang', language,
  ];
  if (stateName) args.push('--state-name', stateName);
  for (const [key, value] of Object.entries(optionDefaults)) args.push('--option-default', `${key}=${value}`);
  if (windowed) args.push('--windowed');
  if (resume) args.push('--resume');
  return args;
}

module.exports = { BUILDBOT, validCore, coreDllName, coreUrl, needsExtraction, mainEntry, safeEntryPath, gameOptionDefaults, playerArgs };
