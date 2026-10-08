// Lancement des jeux sous Windows.
//  - moteur intégré (romcloud-player.exe) : mêmes cœurs que RetroArch, téléchargés à la demande ;
//  - RetroArch : « retroarch.exe -L <cœur> <jeu> », le cœur étant repris des modèles
//    d'émulateurs Daijishou du système (extra LIBRETRO des modèles Android) ;
//  - émulateurs du catalogue (Dolphin, PCSX2, DuckStation…) : ligne de commande connue, ou
//    ouverture de l'émulateur seul quand il ne sait pas lancer un jeu directement ;
//  - commande personnalisée par système (« {file} » = chemin du jeu) ;
//  - programme associé au type de fichier dans Windows.
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { shell } = require('electron');
const settings = require('./settings');
const library = require('./library');
const catalog = require('./catalog');
const { AppError } = require('./api');
const { tokenize, coreOf, cores } = require('./emulators');
const builtin = require('./builtin');
const account = require('./account');

// ---------------------------------------------------------------------------
// Émulateurs du catalogue : emplacement sur le PC et ligne de commande
// ---------------------------------------------------------------------------

/** Chemin de l'exécutable : choisi par l'utilisateur ou trouvé par la dernière recherche. */
function emulatorPath(id) {
  const file = settings.load().emulatorPaths[id];
  return file && fs.existsSync(file) ? file : null;
}

/** Arguments du lancement : ceux saisis par l'utilisateur, sinon ceux du catalogue. */
function emulatorArgs(emu) {
  const custom = settings.load().emulatorArgs[emu.id];
  return custom ? tokenize(custom) : emu.args;
}

/** Cherche les émulateurs sur le PC ; les chemins déjà choisis par l'utilisateur sont conservés. */
function detectEmulators() {
  const found = catalog.detect();
  const paths = { ...settings.load().emulatorPaths };
  for (const [id, file] of Object.entries(found)) {
    if (!paths[id] || !fs.existsSync(paths[id])) paths[id] = file;
  }
  settings.save({ emulatorPaths: paths, emulatorsDetectedAt: new Date().toISOString() });
  return listEmulators();
}

/** Recherche automatique au premier affichage (puis à la demande). */
function ensureDetected() {
  if (!settings.load().emulatorsDetectedAt) detectEmulators();
}

/** Catalogue avec l'état de chaque émulateur sur ce PC. */
function listEmulators() {
  const s = settings.load();
  return catalog.EMULATORS.map((emu) => {
    const exe = emulatorPath(emu.id);
    const args = emulatorArgs(emu);
    return {
      id: emu.id,
      name: emu.name,
      systems: emu.systems,
      url: emu.url,
      path: exe,
      defaultLocation: catalog.defaultLocation(emu),
      direct: Boolean(args),
      defaultArgs: catalog.argsLine(emu.args),
      systemNames: emu.systems.map(catalog.systemName),
      customArgs: s.emulatorArgs[emu.id] || '',
      command: args ? catalog.commandLine(exe || emu.exe[0], args) : null,
    };
  });
}

function setEmulatorPath(id, file) {
  if (!catalog.byId(id)) throw new AppError('errors.unknownEmulator', { id });
  settings.save({ emulatorPaths: { ...settings.load().emulatorPaths, [id]: file || '' } });
  return listEmulators();
}

function setEmulatorArgs(id, args) {
  if (!catalog.byId(id)) throw new AppError('errors.unknownEmulator', { id });
  settings.save({ emulatorArgs: { ...settings.load().emulatorArgs, [id]: String(args || '').trim() } });
  return listEmulators();
}

/** Lance un programme ; [game] : jeu lancé, dont le temps de jeu est compté jusqu'à sa fermeture. */
function spawnDetached(exe, args, game = null) {
  const cwd = path.dirname(exe);
  return new Promise((resolve, reject) => {
    const child = spawn(exe, args, { cwd: fs.existsSync(cwd) ? cwd : undefined, detached: true, stdio: 'ignore' });
    child.once('error', (err) => reject(new AppError('errors.launchFailed', { detail: err.message })));
    child.once('spawn', () => {
      const started = Date.now();
      if (game) child.once('exit', () => account.addPlaytime(game.id, (Date.now() - started) / 1000));
      child.unref();
      resolve();
    });
  });
}

/** Ouvre l'émulateur seul (sans jeu). */
async function launchEmulator(id) {
  const emu = catalog.byId(id);
  if (!emu) throw new AppError('errors.unknownEmulator', { id });
  const exe = emulatorPath(id);
  if (!exe) throw new AppError('errors.emulatorMissing', { name: emu.name, id });
  await spawnDetached(exe, []);
}

// ---------------------------------------------------------------------------
// Choix de l'émulateur d'un système
// ---------------------------------------------------------------------------

/** Choix proposés pour un système, et choix courant. */
function options(system) {
  ensureDetected();
  const builtinOk = builtin.available();
  const list = [
    // Moteur intégré en premier : mêmes cœurs que RetroArch, rien à installer.
    ...(builtinOk ? cores(system).map((core) => ({ id: `builtin:${core}`, kind: 'builtin', core, installed: builtin.coreInstalled(core) })) : []),
    ...cores(system).map((core) => ({ id: `retroarch:${core}`, kind: 'retroarch', core })),
    ...catalog.forSystem(system).map((emu) => ({
      id: `emu:${emu.id}`,
      kind: 'emulator',
      emuId: emu.id,
      name: emu.name,
      installed: Boolean(emulatorPath(emu.id)),
    })),
    { id: 'custom', kind: 'custom' },
    { id: 'default', kind: 'default' },
  ];
  const saved = settings.load().emulators[system.id];
  // Par défaut : moteur intégré, sinon RetroArch s'il est installé, sinon un émulateur du catalogue
  // présent sur le PC, sinon le premier cœur RetroArch ou émulateur proposé, sinon le programme
  // Windows associé.
  const retroarchOk = Boolean(settings.load().retroarchPath) && fs.existsSync(settings.load().retroarchPath);
  const fallback = list.find((o) => o.kind === 'builtin')
    || (retroarchOk && list.find((o) => o.kind === 'retroarch'))
    || list.find((o) => o.kind === 'emulator' && o.installed)
    || list.find((o) => o.kind === 'retroarch' || o.kind === 'emulator')
    || list.find((o) => o.kind === 'default');
  const selected = list.some((o) => o.id === saved?.option) ? saved.option : fallback.id;
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

/**
 * Vérifications avant lancement (émulateur installé, cœur présent…) ; lève une AppError.
 * Renvoie { exe, args } ; { exe, manual: true } pour un émulateur à ouvrir seul ; { open } pour
 * le programme Windows associé.
 */
function prepare(system, game) {
  const file = library.fileFor(system, game);
  if (!library.isDownloaded(system, game)) throw new AppError('errors.notDownloaded', { file });
  const { options: list, selected, command } = options(system);
  const option = list.find((o) => o.id === selected);
  if (option.kind === 'builtin') {
    // Le cœur est téléchargé au lancement s'il est absent (voir play).
    return { builtin: true, core: option.core, file, exe: builtin.playerPath(), args: ['--core', `${option.core}_libretro.dll`, '--rom', file] };
  }
  if (option.kind === 'retroarch') {
    const exe = settings.load().retroarchPath;
    if (!exe || !fs.existsSync(exe)) throw new AppError('errors.retroarchMissing');
    const dll = coreDll(exe, option.core);
    if (!fs.existsSync(dll)) throw new AppError('errors.coreMissing', { core: option.core, dir: path.dirname(dll) });
    return { exe, args: ['-L', dll, file] };
  }
  if (option.kind === 'emulator') {
    const emu = catalog.byId(option.emuId);
    const exe = emulatorPath(emu.id);
    if (!exe) throw new AppError('errors.emulatorMissing', { name: emu.name, id: emu.id });
    const args = emulatorArgs(emu);
    if (!args) return { exe, manual: true, emulator: emu.name, file };
    return { exe, args: catalog.buildArgs(args, file) };
  }
  if (option.kind === 'custom') {
    if (!command.trim()) throw new AppError('errors.commandMissing');
    const values = { file, dir: path.dirname(file), name: path.basename(file), basename: path.parse(file).name };
    const tokens = tokenize(command).map((t) => t.replace(/\{(file|dir|name|basename)\}/g, (_m, k) => values[k]));
    if (!command.includes('{file}')) tokens.push(file);
    return { exe: tokens[0], args: tokens.slice(1) };
  }
  return { open: file };
}

/** Ligne de commande qui sera utilisée pour ce jeu (affichée dans la fiche), ou null. */
function describe(system, game) {
  try {
    const plan = prepare(system, game);
    return plan.args ? catalog.commandLine(plan.exe, plan.args) : null;
  } catch {
    return null;
  }
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

/** Jeux de [games] (même système) avec une partie à reprendre : identifiants. */
function resumableIds(system, games) {
  return games.filter((game) => resumable(system, game)).map((game) => game.id);
}

/** Partie à reprendre : moteur intégré choisi et état sauvegardé pour ce jeu et ce cœur. */
function resumable(system, game) {
  try {
    const plan = prepare(system, game);
    return Boolean(plan.builtin) && fs.existsSync(builtin.statePath(plan.core, plan.file));
  } catch {
    return false;
  }
}

/** Partie à reprendre depuis une sauvegarde en ligne (moteur intégré, profil connecté). */
async function resumableOnline(system, game) {
  let plan;
  try {
    plan = prepare(system, game);
  } catch {
    return null;
  }
  if (!plan.builtin) return null;
  const save = (await account.saves(game.id)).find((s) => account.sameSaveCore(s.core, plan.core) && s.kind === 'state');
  return save ? { device: save.device, savedAt: save.savedAt } : null;
}

/**
 * Lance le jeu (`resume` : reprend la partie sauvegardée, moteur intégré). Renvoie
 * { manual: true, emulator, file } quand l'émulateur a été ouvert seul (l'interface indique
 * alors comment ouvrir le jeu depuis son menu).
 */
async function play(system, game, { resume = false } = {}) {
  const plan = prepare(system, game);
  if (plan.builtin) {
    await builtin.launch(system, game, plan.file, plan.core, { resume });
    return { manual: false };
  }
  if (plan.open) {
    const error = await shell.openPath(plan.open);
    if (error) throw new AppError('errors.noDefaultApp', { detail: error });
    return { manual: false };
  }
  await spawnDetached(plan.exe, plan.manual ? [] : plan.args, plan.manual ? null : game);
  return plan.manual ? { manual: true, emulator: plan.emulator, file: plan.file } : { manual: false };
}

module.exports = {
  tokenize, coreOf, cores, options, choose, coreDll, prepare, describe, check, resumable, resumableIds, resumableOnline, play,
  listEmulators, detectEmulators, setEmulatorPath, setEmulatorArgs, launchEmulator,
};
