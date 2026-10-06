// Processus principal : fenêtre, et opérations demandées par l'interface (IPC).
const path = require('node:path');
const { app, BrowserWindow, dialog, ipcMain, shell, Menu } = require('electron');
const settings = require('./settings');
const api = require('./api');
const library = require('./library');
const downloads = require('./downloads');
const launcher = require('./launcher');
const keyboard = require('./keyboard');
const builtin = require('./builtin');
const screencast = require('./screencast');
const account = require('./account');
const connectivity = require('./connectivity');
const { createUpdater } = require('./updater');

const updater = createUpdater({ app });

let win = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1400,
    height: 880,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#111318',
    title: 'RomCloud',
    icon: path.join(__dirname, '..', '..', 'assets', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  Menu.setApplicationMenu(null);
  win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  // Liens externes (ex. site de RetroArch) : navigateur par défaut.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  // La fenêtre cachée de capture (diffusion sur un Chromecast) ne doit pas garder l'application ouverte.
  win.on('closed', () => {
    win = null;
    app.quit();
  });
}

/** Exécute un traitement et renvoie { ok, data } ou { ok: false, error } (erreurs traduisibles). */
function handle(channel, fn) {
  ipcMain.handle(channel, async (_event, ...args) => {
    try {
      return { ok: true, data: await fn(...args) };
    } catch (err) {
      return { ok: false, error: { key: err.key || null, vars: err.vars || {}, message: err.message } };
    }
  });
}

const settingsView = (s) => ({ ...s, effectiveLanguage: settings.language(), effectiveBiosDir: settings.biosDir() });
handle('settings:get', () => settingsView(settings.load()));
handle('settings:save', (patch) => settingsView(settings.save(patch)));
handle('api:test', (url, key) => api.test(url, key));
handle('api:systems', () => api.systems());
handle('api:games', (systemId) => api.games(systemId));
handle('api:search', (query) => api.search(query));
handle('library:downloaded', (systems, games) => library.downloadedIds(systems, games));
handle('library:path', (system, game) => library.fileFor(system, game));
handle('library:remove', (system, game) => library.remove(system, game));
handle('library:systemsWithGames', (systems) => library.systemsWithGames(systems));
// BIOS du système absents du PC (téléchargés avec le jeu si l'utilisateur le demande).
handle('bios:missing', async (system) => library.missingBios(await api.bios(system)));
handle('downloads:start', (system, game, options) => {
  downloads.start(system, game, options); // en arrière-plan : la progression arrive par « downloads:update »
});
handle('downloads:cancel', (gameId) => downloads.cancel(gameId));
handle('downloads:dismiss', (gameId) => downloads.dismissError(gameId));
handle('downloads:states', () => downloads.states());
handle('launcher:options', (system) => launcher.options(system));
handle('launcher:choose', (systemId, option, command) => launcher.choose(systemId, option, command));
handle('launcher:play', (system, game, options) => launcher.play(system, game, options));
handle('launcher:resumable', (system, game) => launcher.resumable(system, game));
handle('launcher:resumableIds', (system, games) => launcher.resumableIds(system, games));
handle('launcher:check', (system) => launcher.check(system));
handle('launcher:resumableOnline', (system, game) => launcher.resumableOnline(system, game));
// Moteur intégré arrêté sur une erreur : l'interface propose de réinitialiser le cœur.
handle('player:resetCore', (systemId, core) => builtin.resetCore(systemId, core));
builtin.onCrash((crash) => win?.webContents.send('player:crashed', crash));
// Profil du joueur : connexion, temps de jeu, erreurs signalées à l'administration.
handle('account:state', () => account.state());
handle('account:register', (username, password) => account.register(username, password));
handle('account:login', (username, password) => account.login(username, password));
handle('account:logout', () => account.logout());
handle('account:playtime', () => account.playtime());
handle('account:reportError', (report) => account.reportError(report || {}));
account.onChange(() => win?.webContents.send('account:update'));
// Connexion au serveur : hors ligne, seuls les jeux téléchargés sont proposés ; à son retour, les
// sauvegardes et durées de jeu en attente sont envoyées.
const connectivityState = () => ({ online: connectivity.isOnline(), pending: account.pendingCount() });
handle('connectivity:state', () => connectivityState());
handle('connectivity:check', async () => {
  await connectivity.probe();
  return connectivityState();
});
connectivity.onChange(async (online) => {
  win?.webContents.send('connectivity:update', connectivityState());
  if (!online) return;
  const synced = await account.flush().catch(() => 0);
  win?.webContents.send('connectivity:update', { ...connectivityState(), synced });
});
process.on('uncaughtException', (err) => {
  console.error(err);
  account.reportError({ context: 'main', message: err.message, details: err.stack });
});
handle('launcher:describe', (system, game) => launcher.describe(system, game));
handle('emulators:list', () => launcher.listEmulators());
handle('emulators:detect', () => launcher.detectEmulators());
handle('emulators:setPath', (id, file) => launcher.setEmulatorPath(id, file));
handle('emulators:setArgs', (id, args) => launcher.setEmulatorArgs(id, args));
handle('emulators:launch', (id) => launcher.launchEmulator(id));
handle('dialog:pickFile', async (filters) => {
  const r = await dialog.showOpenDialog(win, { properties: ['openFile'], filters });
  return r.canceled ? null : r.filePaths[0];
});
handle('dialog:pickFolder', async () => {
  const r = await dialog.showOpenDialog(win, { properties: ['openDirectory', 'createDirectory'] });
  return r.canceled ? null : r.filePaths[0];
});
handle('shell:showItem', (file) => shell.showItemInFolder(file));
handle('shell:openExternal', (url) => shell.openExternal(url));
handle('keyboard:layout', () => ({
  buttons: keyboard.BUTTONS.map((b) => b.name),
  defaults: keyboard.DEFAULTS,
  reserved: keyboard.RESERVED,
  codes: Object.keys(keyboard.SCANCODES),
  keys: builtin.loadKeys(),
}));
handle('keyboard:save', (keys) => builtin.saveKeys(keys));
handle('app:version', () => app.getVersion());
// Diffusion de l'écran sur un Chromecast.
handle('cast:discover', () => screencast.discover());
handle('cast:start', (device) => screencast.start(device));
handle('cast:stop', () => screencast.stop());
handle('cast:state', () => screencast.state());
screencast.init({ mainWindow: () => win });
screencast.onChange((state) => win?.webContents.send('cast:update', state));
// Mise à jour : vérifiée au chargement de l'interface ; installation sur confirmation.
handle('update:check', () => updater.check());
handle('update:install', () => updater.install((bytes, total) => win?.webContents.send('update:progress', { bytes, total })));

downloads.onUpdate((gameId, state, event) => {
  win?.webContents.send('downloads:update', { gameId, state, event });
});

// Une seule fenêtre : un second lancement ramène la première au premier plan.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });
  app.whenReady().then(() => {
    updater.cleanup();
    createWindow();
    account.flush().catch(() => {}); // travail laissé en attente à la dernière fermeture
  });
  app.on('window-all-closed', () => app.quit());
  app.on('before-quit', () => screencast.stop());
}
