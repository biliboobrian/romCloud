// Lancement des jeux sous Windows.
//  - RetroArch : « retroarch.exe -L <cœur> <jeu> », le cœur étant repris des modèles
//    d'émulateurs Daijishou du système (extra LIBRETRO des modèles Android) ;
//  - commande personnalisée par système (« {file} » = chemin du jeu) ;
//  - programme associé au type de fichier dans Windows.
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { shell } = require('electron');
const settings = require('./settings');
const library = require('./library');
const { AppError } = require('./api');
const { tokenize, coreOf, cores } = require('./emulators');

/** Choix proposés pour un système, et choix courant. */
function options(system) {
  const list = [
    ...cores(system).map((core) => ({ id: `retroarch:${core}`, kind: 'retroarch', core })),
    { id: 'custom', kind: 'custom' },
    { id: 'default', kind: 'default' },
  ];
  const saved = settings.load().emulators[system.id];
  // Par défaut : le premier cœur RetroArch du système, sinon le programme Windows associé.
  const fallback = list.find((o) => o.kind === 'retroarch')?.id || 'default';
  const selected = list.some((o) => o.id === saved?.option) ? saved.option : fallback;
  return { options: list, selected, command: saved?.command || '' };
}

function choose(systemId, option, command) {
  const emulators = { ...settings.load().emulators, [systemId]: { option, command: command ?? settings.load().emulators[systemId]?.command ?? '' } };
  settings.save({ emulators });
}

/** Dossier des cœurs de RetroArch (à côté de retroarch.exe). */
function coreDll(retroarchPath, core) {
  return path.join(path.dirname(retroarchPath), 'cores', `${core}_libretro.dll`);
}

/** Vérifications avant lancement (RetroArch installé, cœur présent…) ; lève une AppError. */
function prepare(system, game) {
  const file = library.fileFor(system, game);
  if (!library.isDownloaded(system, game)) throw new AppError('errors.notDownloaded', { file });
  const { options: list, selected, command } = options(system);
  const option = list.find((o) => o.id === selected);
  if (option.kind === 'retroarch') {
    const exe = settings.load().retroarchPath;
    if (!exe || !fs.existsSync(exe)) throw new AppError('errors.retroarchMissing');
    const dll = coreDll(exe, option.core);
    if (!fs.existsSync(dll)) throw new AppError('errors.coreMissing', { core: option.core, dir: path.dirname(dll) });
    return { exe, args: ['-L', dll, file], cwd: path.dirname(exe) };
  }
  if (option.kind === 'custom') {
    if (!command.trim()) throw new AppError('errors.commandMissing');
    const values = { file, dir: path.dirname(file), name: path.basename(file), basename: path.parse(file).name };
    const tokens = tokenize(command).map((t) => t.replace(/\{(file|dir|name|basename)\}/g, (_m, k) => values[k]));
    if (!command.includes('{file}')) tokens.push(file);
    return { exe: tokens[0], args: tokens.slice(1), cwd: path.dirname(tokens[0]) };
  }
  return { open: file };
}

/** État de RetroArch pour le guide : exécutable trouvé, cœur installé. */
function check(system) {
  const { options: list, selected } = options(system);
  const option = list.find((o) => o.id === selected);
  const exe = settings.load().retroarchPath;
  const retroarchOk = Boolean(exe) && fs.existsSync(exe);
  const core = option.kind === 'retroarch' ? option.core : null;
  const dll = core && exe ? coreDll(exe, core) : null;
  return {
    usesRetroArch: option.kind === 'retroarch',
    retroarchPath: exe,
    retroarchOk,
    core,
    coreDir: dll ? path.dirname(dll) : null,
    coreOk: Boolean(dll) && retroarchOk && fs.existsSync(dll),
  };
}

async function play(system, game) {
  const plan = prepare(system, game);
  if (plan.open) {
    const error = await shell.openPath(plan.open);
    if (error) throw new AppError('errors.noDefaultApp', { detail: error });
    return;
  }
  await new Promise((resolve, reject) => {
    const child = spawn(plan.exe, plan.args, { cwd: fs.existsSync(plan.cwd) ? plan.cwd : undefined, detached: true, stdio: 'ignore' });
    child.once('error', (err) => reject(new AppError('errors.launchFailed', { detail: err.message })));
    child.once('spawn', () => {
      child.unref();
      resolve();
    });
  });
}

module.exports = { tokenize, coreOf, cores, options, choose, coreDll, prepare, check, play };
