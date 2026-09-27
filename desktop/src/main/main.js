// Processus principal : fenêtre, et opérations demandées par l'interface (IPC).
const path = require('node:path');
const { app, BrowserWindow, dialog, ipcMain, shell, Menu } = require('electron');
const settings = require('./settings');
const api = require('./api');
const library = require('./library');
const downloads = require('./downloads');
const launcher = require('./launcher');

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
handle('launcher:play', (system, game) => launcher.play(system, game));
handle('launcher:check', (system) => launcher.check(system));
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
handle('app:version', () => app.getVersion());

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
  app.whenReady().then(createWindow);
  app.on('window-all-closed', () => app.quit());
}
