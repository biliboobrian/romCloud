// Moteur d'émulation intégré (romcloud-player.exe, frontend libretro natif) : téléchargement des
// cœurs à la demande depuis le buildbot libretro, préparation de la ROM et lancement du jeu.
// Données dans le dossier de l'application : libretro\cores, saves\<cœur>, states\<cœur>,
// options\<système>.cfg (options du cœur mémorisées par système), player.log (journal).
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { app } = require('electron');
const settings = require('./settings');
const zip = require('./zip');
const libretro = require('./libretro');
const { AppError } = require('./api');

const root = () => path.join(app.getPath('userData'), 'libretro');
const safe = (s) => String(s).replace(/[^A-Za-z0-9._-]/g, '_');

/** Exécutable du moteur : à côté de l'application installée, sinon build local (développement). */
function playerPath() {
  return app.isPackaged
    ? path.join(process.resourcesPath, 'player', 'romcloud-player.exe')
    : path.join(__dirname, '..', '..', 'native', 'player', 'build', 'romcloud-player.exe');
}

function available() {
  return fs.existsSync(playerPath());
}

const coreFile = (core) => path.join(root(), 'cores', libretro.coreDllName(core));

function coreInstalled(core) {
  return libretro.validCore(core) && fs.existsSync(coreFile(core));
}

// Téléchargements de cœurs en cours (un seul par cœur, même si l'utilisateur relance).
const pending = new Map();

/** Cœur installé ou téléchargé (et décompressé) depuis le buildbot libretro ; chemin de la DLL. */
function ensureCore(core) {
  if (!libretro.validCore(core)) throw new AppError('errors.coreInvalid', { core });
  if (coreInstalled(core)) return Promise.resolve(coreFile(core));
  if (!pending.has(core)) {
    const job = (async () => {
      let res;
      try {
        res = await fetch(libretro.coreUrl(core));
      } catch (err) {
        throw new AppError('errors.coreDownload', { core, detail: err.message });
      }
      if (res.status === 404) throw new AppError('errors.coreNotOnBuildbot', { core });
      if (!res.ok) throw new AppError('errors.coreDownload', { core, detail: `HTTP ${res.status}` });
      const archive = Buffer.from(await res.arrayBuffer());
      const entry = zip.entries(archive).find((e) => e.name.toLowerCase().endsWith('.dll'));
      if (!entry) throw new AppError('errors.coreDownload', { core, detail: 'archive sans .dll' });
      const target = coreFile(core);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.writeFileSync(`${target}.part`, zip.extract(archive, entry));
      fs.renameSync(`${target}.part`, target);
      return target;
    })().finally(() => pending.delete(core));
    pending.set(core, job);
  }
  return pending.get(core);
}

/** ROM passée au moteur : .zip décompressé pour les cœurs qui ne lisent pas les archives. */
function prepareRom(core, file) {
  if (!libretro.needsExtraction(core, file)) return file;
  const archive = fs.readFileSync(file);
  const list = zip.entries(archive);
  const dir = path.join(root(), 'rom-cache');
  fs.rmSync(dir, { recursive: true, force: true });
  let main = null;
  const chosen = libretro.mainEntry(list);
  for (const entry of list) {
    if (entry.name.endsWith('/')) continue;
    const target = libretro.safeEntryPath(dir, entry.name);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, zip.extract(archive, entry));
    if (entry === chosen) main = target;
  }
  return main || file;
}

/** État de sauvegarde du jeu dans le moteur (« Sauvegarder et quitter », F2…). */
const statePath = (core, file) => path.join(root(), 'states', core, `${path.parse(file).name}.state`);

/**
 * Lance le jeu dans le moteur intégré (le cœur est téléchargé avant si besoin) ; `resume` :
 * reprend la partie à son état de sauvegarde.
 */
async function launch(system, game, file, core, { resume = false } = {}) {
  if (!available()) throw new AppError('errors.playerMissing', { path: playerPath() });
  const dll = await ensureCore(core);
  const rom = prepareRom(core, file);
  const base = root();
  const args = libretro.playerArgs({
    dll,
    rom,
    systemDir: settings.biosDir(),
    saveDir: path.join(base, 'saves', core),
    stateDir: path.join(base, 'states', core),
    stateName: path.parse(file).name,
    optionsFile: path.join(base, 'options', `${safe(system.id)}.cfg`),
    title: game.title || path.parse(file).name,
    language: settings.language(),
    windowed: false,
    resume,
    optionDefaults: libretro.gameOptionDefaults(core, rom),
  });
  fs.mkdirSync(base, { recursive: true });
  const log = fs.openSync(path.join(base, 'player.log'), 'w');
  return new Promise((resolve, reject) => {
    let child;
    try {
      child = spawn(playerPath(), args, { stdio: ['ignore', log, log] });
    } finally {
      fs.closeSync(log); // le moteur a sa propre copie du fichier journal
    }
    child.once('error', (err) => reject(new AppError('errors.launchFailed', { detail: err.message })));
    child.once('spawn', () => resolve());
  });
}

module.exports = { playerPath, available, coreInstalled, ensureCore, prepareRom, statePath, launch };
