// Diffusion de l'écran du PC sur un Chromecast : l'écran (et le son du PC) est capturé dans une
// fenêtre cachée, encodé en WebM (VP8 + Opus) par MediaRecorder, servi en HTTP sur le réseau local
// et lu par le lecteur multimédia par défaut du Chromecast.
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');
const { BrowserWindow, desktopCapturer, ipcMain, screen } = require('electron');
const { discover, CastSession } = require('./cast');

let state = { status: 'idle', device: null, error: null }; // idle | connecting | casting
const listeners = [];
let session = null;
let server = null;
let captureWin = null;
let response = null; // requête HTTP du Chromecast en cours
let getMainWindow = () => null;

function setState(patch) {
  state = { ...state, ...patch };
  for (const fn of listeners) fn(state);
}

const castError = (key, detail) => Object.assign(new Error(detail || key), { key, vars: { detail: detail || '' } });

// ---------------------------------------------------------------------------
// Capture (fenêtre cachée, non ralentie en arrière-plan : le jeu est au premier plan)
// ---------------------------------------------------------------------------

function ensureCaptureWindow() {
  if (captureWin && !captureWin.isDestroyed()) return captureWin;
  captureWin = new BrowserWindow({
    show: false,
    width: 320,
    height: 240,
    webPreferences: {
      preload: path.join(__dirname, '..', 'capture', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      backgroundThrottling: false,
    },
  });
  captureWin.loadFile(path.join(__dirname, '..', 'capture', 'capture.html'));
  return captureWin;
}

/** Écran à capturer : celui où se trouve la fenêtre de RomCloud (le moteur intégré s'y ouvre aussi). */
async function screenSourceId() {
  const sources = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: 0, height: 0 } });
  if (!sources.length) throw castError('cast.errors.capture');
  const win = getMainWindow();
  const display = win ? screen.getDisplayMatching(win.getBounds()) : screen.getPrimaryDisplay();
  return (sources.find((s) => s.display_id === String(display.id)) || sources[0]).id;
}

async function startRecorder() {
  const win = ensureCaptureWindow();
  if (win.webContents.isLoading()) await new Promise((resolve) => win.webContents.once('did-finish-load', resolve));
  win.webContents.send('capture:start', await screenSourceId());
}

function stopRecorder() {
  if (captureWin && !captureWin.isDestroyed()) captureWin.webContents.send('capture:stop');
}

ipcMain.on('capture:chunk', (_e, chunk) => {
  if (response && !response.writableEnded) response.write(Buffer.from(chunk));
});
ipcMain.on('capture:error', (_e, message) => {
  stop();
  setState({ error: { key: 'cast.errors.capture', vars: { detail: message } } });
});

// ---------------------------------------------------------------------------
// Serveur HTTP : un flux par connexion du Chromecast, enregistreur relancé à chaque fois
// pour que le flux commence par l'en-tête WebM.
// ---------------------------------------------------------------------------

async function startServer(token) {
  server = http.createServer((req, res) => {
    if (req.url !== `/${token}.webm`) {
      res.writeHead(404).end();
      return;
    }
    res.writeHead(200, {
      'Content-Type': 'video/webm',
      'Cache-Control': 'no-cache, no-store',
      'Access-Control-Allow-Origin': '*',
    });
    if (req.method === 'HEAD') return res.end();
    if (response) response.end();
    response = res;
    res.socket.setNoDelay(true);
    res.on('close', () => {
      if (response === res) {
        response = null;
        stopRecorder();
      }
    });
    startRecorder().catch((err) => {
      res.end();
      setState({ error: { key: 'cast.errors.capture', vars: { detail: err.message } } });
    });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '0.0.0.0', resolve);
  });
  return server.address().port;
}

function closeServer() {
  response?.end();
  response = null;
  server?.close();
  server = null;
}

// ---------------------------------------------------------------------------
// API
// ---------------------------------------------------------------------------

async function start(device) {
  stop();
  setState({ status: 'connecting', device, error: null });
  const token = crypto.randomBytes(12).toString('hex');
  const current = new CastSession(device);
  session = current;
  try {
    const port = await startServer(token);
    current.on('ended', () => session === current && stop());
    current.on('failed', (err) => {
      if (session !== current) return;
      stop();
      setState({ error: { key: 'cast.errors.lost', vars: { detail: err?.message || '' } } });
    });
    await current.load((localAddress) => ({
      url: `http://${localAddress}:${port}/${token}.webm`,
      contentType: 'video/webm',
      title: 'RomCloud',
    }));
    if (session !== current) return state;
    setState({ status: 'casting' });
    return state;
  } catch (err) {
    if (session === current) stop();
    // Pas de lecture : le plus souvent le pare-feu de Windows bloque le Chromecast.
    throw castError(err.message === 'timeout' || err.message === 'MEDIA_ERROR' ? 'cast.errors.timeout' : 'cast.errors.connect', err.message);
  }
}

function stop() {
  const current = session;
  session = null;
  current?.stop();
  stopRecorder();
  closeServer();
  if (captureWin && !captureWin.isDestroyed()) captureWin.destroy();
  captureWin = null;
  if (state.status !== 'idle') setState({ status: 'idle', device: null });
}

module.exports = {
  init({ mainWindow }) {
    getMainWindow = mainWindow;
  },
  discover: () => discover(),
  start,
  stop,
  state: () => state,
  onChange(fn) {
    listeners.push(fn);
  },
};
