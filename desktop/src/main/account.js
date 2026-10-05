// Profil du joueur sur le serveur : connexion, temps de jeu par jeu, sauvegardes en ligne du moteur
// intégré (reprise sur un autre appareil) et erreurs signalées à l'administration. La session (jeton)
// est gardée dans les réglages ; les durées de jeu non envoyées (serveur injoignable) attendent dans
// les réglages et partent à la connexion suivante.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { app } = require('electron');
const settings = require('./settings');
const { AppError, headers } = require('./api');

/** Parties plus courtes ignorées (jeu quitté aussitôt, lancement raté). */
const MIN_PLAY_SECONDS = 10;
const KINDS = ['state', 'sram'];

let listener = () => {};
/** Prévient l'interface (profil, temps de jeu) après un changement. */
function onChange(fn) {
  listener = fn;
}

const session = () => settings.load().session || null;

function clientHeaders() {
  const h = {
    ...headers(settings.load().apiKey),
    'X-RomCloud-Device': os.hostname(),
    'X-RomCloud-Platform': 'windows',
    'X-RomCloud-Version': app.getVersion(),
  };
  if (session()) h['X-RomCloud-Session'] = session().token;
  return h;
}

/** Requête au serveur ; `raw` : corps binaire (Buffer) envoyé ou reçu. */
async function call(apiPath, { method = 'GET', body, raw, extraHeaders = {}, timeout = 15000 } = {}) {
  const { serverUrl } = settings.load();
  if (!serverUrl) throw new AppError('errors.noServer');
  const h = { ...clientHeaders(), ...extraHeaders };
  let payload;
  if (body !== undefined && !Buffer.isBuffer(body)) {
    h['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  } else if (body) {
    h['Content-Type'] = 'application/octet-stream';
    payload = body;
  }
  let res;
  try {
    res = await fetch(serverUrl + apiPath, { method, headers: h, body: payload, signal: AbortSignal.timeout(timeout) });
  } catch (err) {
    throw new AppError('errors.unreachable', { detail: err.cause?.code || err.message });
  }
  if (res.status === 204) return null;
  if (!res.ok) {
    let data = {};
    try {
      data = await res.json();
    } catch {
      /* réponse non JSON */
    }
    // Session refusée (déconnectée depuis l'administration, mot de passe changé) : oubliée ici aussi.
    if (data.code === 'errors.signedOut' && session()) {
      settings.save({ session: null });
      listener();
    }
    const err = data.error ? new AppError('errors.server', { message: data.error }, data.error) : new AppError('errors.http', { status: res.status });
    err.status = res.status;
    throw err;
  }
  if (raw) return { data: Buffer.from(await res.arrayBuffer()), headers: res.headers };
  return res.json();
}

// ---------------------------------------------------------------------------
// Connexion
// ---------------------------------------------------------------------------

function remember({ token, user }) {
  settings.save({ session: { token, username: user.username, userId: user.id } });
  flushPlaytime().catch(() => {});
  listener();
  return state();
}

const register = async (username, password) => remember(await call('/api/account/register', { method: 'POST', body: { username, password } }));
const login = async (username, password) => remember(await call('/api/account/login', { method: 'POST', body: { username, password } }));

async function logout() {
  if (session()) await call('/api/account/logout', { method: 'POST' }).catch(() => {});
  settings.save({ session: null });
  listener();
  return state();
}

/** Profil affiché : { signedIn, username, playSeconds, gamesPlayed, offline }. */
async function state() {
  const s = session();
  if (!s) return { signedIn: false };
  try {
    const me = await call('/api/account/me');
    return { signedIn: true, username: me.user.username, playSeconds: me.playSeconds, gamesPlayed: me.gamesPlayed };
  } catch (err) {
    if (!session()) return { signedIn: false };
    return { signedIn: true, username: s.username, offline: true, error: err.message };
  }
}

// ---------------------------------------------------------------------------
// Temps de jeu
// ---------------------------------------------------------------------------

/** Temps de jeu par jeu : { [gameId]: secondes } (vide sans profil ou hors ligne). */
async function playtime() {
  if (!session()) return {};
  try {
    const list = await call('/api/account/playtime');
    const pending = settings.load().pendingPlaytime || [];
    const map = Object.fromEntries(list.map((p) => [p.gameId, p.seconds]));
    for (const p of pending) map[p.gameId] = (map[p.gameId] || 0) + p.seconds;
    return map;
  } catch {
    return {};
  }
}

/** Durées gardées faute de serveur : envoyées dès que possible. */
async function flushPlaytime() {
  const pending = settings.load().pendingPlaytime || [];
  if (!pending.length || !session()) return;
  const left = [];
  for (const p of pending) {
    try {
      await call('/api/account/playtime', { method: 'POST', body: p });
    } catch (err) {
      if (err.key === 'errors.unreachable') left.push(p);
    }
  }
  settings.save({ pendingPlaytime: left });
}

/** Ajoute une partie au temps de jeu du jeu (profil connecté). */
async function addPlaytime(gameId, seconds) {
  if (!session() || seconds < MIN_PLAY_SECONDS) return;
  const entry = { gameId, seconds: Math.round(seconds) };
  try {
    await flushPlaytime();
    await call('/api/account/playtime', { method: 'POST', body: entry });
  } catch (err) {
    if (err.key === 'errors.unreachable') settings.save({ pendingPlaytime: [...(settings.load().pendingPlaytime || []), entry] });
  }
  listener();
}

// ---------------------------------------------------------------------------
// Sauvegardes en ligne (moteur intégré)
// ---------------------------------------------------------------------------

const savePath = (gameId, core, kind) => `/api/account/saves/${gameId}/${encodeURIComponent(core)}/${kind}`;

/**
 * Avant de jouer : les sauvegardes en ligne plus récentes que celles du PC (partie continuée sur
 * un autre appareil) remplacent les fichiers locaux. [files] : { state, sram } (chemins).
 */
async function downloadNewer(gameId, core, files) {
  if (!session()) return [];
  let remote;
  try {
    remote = await call(`/api/account/saves?gameId=${gameId}`, { timeout: 8000 });
  } catch {
    return [];
  }
  const updated = [];
  for (const kind of KINDS) {
    const save = remote.find((s) => s.core === core && s.kind === kind);
    const file = files[kind];
    if (!save || !file) continue;
    const local = fs.existsSync(file) ? fs.statSync(file).mtimeMs : 0;
    if (Date.parse(save.savedAt) <= local + 1000) continue;
    try {
      const { data } = await call(savePath(gameId, core, kind), { raw: true, timeout: 120000 });
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(`${file}.download`, data);
      fs.renameSync(`${file}.download`, file);
      const time = new Date(Date.parse(save.savedAt));
      fs.utimesSync(file, time, time);
      updated.push(kind);
    } catch {
      /* sauvegarde locale gardée */
    }
  }
  return updated;
}

/** Après la partie : les fichiers modifiés depuis [since] (ms) sont envoyés au serveur. */
async function uploadChanged(gameId, core, files, since) {
  if (!session()) return [];
  const sent = [];
  for (const kind of KINDS) {
    const file = files[kind];
    if (!file || !fs.existsSync(file)) continue;
    const { mtimeMs, size } = fs.statSync(file);
    if (mtimeMs < since || !size) continue;
    try {
      await call(savePath(gameId, core, kind), {
        method: 'PUT',
        body: fs.readFileSync(file),
        extraHeaders: { 'X-Saved-At': String(Math.round(mtimeMs)) },
        timeout: 300000,
      });
      sent.push(kind);
    } catch (err) {
      reportError({ context: `saves:${kind}`, message: err.message });
    }
  }
  return sent;
}

/** Sauvegardes en ligne du jeu (fiche du jeu) : [{ core, kind, savedAt, device, … }]. */
async function saves(gameId) {
  if (!session()) return [];
  try {
    return await call(`/api/account/saves?gameId=${gameId}`, { timeout: 8000 });
  } catch {
    return [];
  }
}

// ---------------------------------------------------------------------------
// Erreurs
// ---------------------------------------------------------------------------

/** Signale une erreur à l'administration (sans effet si le serveur est injoignable). */
function reportError({ context, message, details }) {
  if (!message || !settings.load().serverUrl) return;
  call('/api/account/errors', { method: 'POST', body: { context, message: String(message), details }, timeout: 8000 }).catch(() => {});
}

module.exports = { onChange, register, login, logout, state, playtime, addPlaytime, downloadNewer, uploadChanged, saves, reportError, MIN_PLAY_SECONDS };
