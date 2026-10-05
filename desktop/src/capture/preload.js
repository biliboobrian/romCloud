// Pont de la fenêtre cachée de capture (diffusion de l'écran sur un Chromecast).
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('capture', {
  onStart: (fn) => ipcRenderer.on('capture:start', (_e, sourceId) => fn(sourceId)),
  onStop: (fn) => ipcRenderer.on('capture:stop', () => fn()),
  chunk: (data) => ipcRenderer.send('capture:chunk', data),
  error: (message) => ipcRenderer.send('capture:error', message),
});
