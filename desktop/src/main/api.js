// Accès au serveur RomCloud, avec cache disque des listes (utilisation hors ligne).
const fs = require('node:fs');
const path = require('node:path');
const { app } = require('electron');
const settings = require('./settings');
const connectivity = require('./connectivity');

class AppError extends Error {
  /** Erreur traduite côté interface : `key` (dictionnaire de l'app) ou message du serveur. */
  constructor(key, vars = {}, message = key) {
    super(message);
    this.key = key;
    this.vars = vars;
  }
}

const cacheDir = () => path.join(app.getPath('userData'), 'api-cache');
const cacheFile = (name) => path.join(cacheDir(), name);
const safeName = (s) => String(s).replace(/[^A-Za-z0-9._-]/g, '_');

function headers(apiKey) {
  const h = { 'Accept-Language': settings.language() };
  if (apiKey) h.Authorization = `Bearer ${apiKey}`;
  return h;
}

async function request(apiPath, { serverUrl, apiKey } = settings.load()) {
  if (!serverUrl) throw new AppError('errors.noServer');
  // Seul le serveur configuré compte pour l'état de connexion (pas une adresse en cours de test).
  const tracked = serverUrl === settings.load().serverUrl;
  let res;
  try {
    res = await fetch(serverUrl + apiPath, { headers: headers(apiKey), signal: AbortSignal.timeout(10000) });
  } catch (err) {
    if (tracked) connectivity.markOffline();
    throw new AppError('errors.unreachable', { detail: err.cause?.code || err.message });
  }
  if (tracked) connectivity.markOnline();
  const text = await res.text();
  if (!res.ok) {
    let serverMessage = null;
    try {
      serverMessage = JSON.parse(text).error;
    } catch {
      /* réponse non JSON */
    }
    if (serverMessage) throw new AppError('errors.server', { message: serverMessage }, serverMessage);
    throw new AppError(res.status === 401 ? 'errors.apiKey' : 'errors.http', { status: res.status });
  }
  return text;
}

const isUnreachable = (err) => err.key === 'errors.unreachable' || err.key === 'errors.noServer';

function readJson(file, fallback) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return fallback;
  }
}

/** Charge une liste et la met en cache ; hors ligne, renvoie la dernière version en cache. */
async function loadWithCache(name, apiPath) {
  try {
    const text = await request(apiPath);
    fs.mkdirSync(cacheDir(), { recursive: true });
    fs.writeFileSync(cacheFile(name), text);
    return { data: JSON.parse(text), offline: false };
  } catch (err) {
    if (!isUnreachable(err)) throw err;
    if (fs.existsSync(cacheFile(name))) {
      return { data: JSON.parse(fs.readFileSync(cacheFile(name), 'utf8')), offline: true };
    }
    throw err;
  }
}

// Jeux téléchargés (et leur système) : gardés à part pour être listés hors ligne, même si la liste
// de leur système n'a jamais été mise en cache (jeu téléchargé depuis la recherche globale).
const downloadedFile = () => cacheFile('downloaded.json');
const downloadedIndex = () => readJson(downloadedFile(), { systems: {}, games: {} });

function rememberDownloaded(system, game) {
  const index = downloadedIndex();
  index.systems[system.id] = system;
  index.games[game.id] = game;
  fs.mkdirSync(cacheDir(), { recursive: true });
  fs.writeFileSync(downloadedFile(), JSON.stringify(index));
}

/** Hors ligne : la liste en cache (ou rien) est complétée par les éléments [extra] de l'index des jeux téléchargés. */
async function withDownloaded(load, extra) {
  let result;
  let error = null;
  try {
    result = await load();
  } catch (err) {
    if (!isUnreachable(err)) throw err;
    result = { data: [], offline: true };
    error = err;
  }
  if (!result.offline) return result;
  const known = new Set(result.data.map((x) => x.id));
  const data = [...result.data, ...extra().filter((x) => !known.has(x.id))];
  if (!data.length && error) throw error;
  return { data, offline: true };
}

const systems = () => withDownloaded(() => loadWithCache('systems.json', '/api/systems'), () => Object.values(downloadedIndex().systems));
const games = (systemId) => withDownloaded(
  () => loadWithCache(`games-${safeName(systemId)}.json`, `/api/systems/${encodeURIComponent(systemId)}/games`),
  () => Object.values(downloadedIndex().games).filter((g) => g.systemId === systemId),
);

/** BIOS du système sur le serveur (liste vide si le système n'en a pas ou si le serveur est injoignable). */
async function bios(system) {
  if (!system.biosCount) return [];
  try {
    const { data } = await loadWithCache(`bios-${safeName(system.id)}.json`, `/api/systems/${encodeURIComponent(system.id)}/bios?catalog=0`);
    return data.files || [];
  } catch {
    return [];
  }
}

/** Recherche dans tous les systèmes ; hors ligne, dans les listes déjà en cache. */
async function search(query) {
  try {
    return { data: JSON.parse(await request(`/api/search?q=${encodeURIComponent(query)}`)), offline: false };
  } catch (err) {
    if (err.key !== 'errors.unreachable') throw err;
    const words = query.toLowerCase().split(/\s+/).filter(Boolean);
    const cached = fs.existsSync(cacheDir())
      ? fs.readdirSync(cacheDir())
        .filter((f) => f.startsWith('games-'))
        .flatMap((f) => {
          try {
            return JSON.parse(fs.readFileSync(cacheFile(f), 'utf8'));
          } catch {
            return [];
          }
        })
      : [];
    const byId = new Map([...Object.values(downloadedIndex().games), ...cached].map((g) => [g.id, g]));
    const data = [...byId.values()]
      .filter((g) => words.every((w) => g.title.toLowerCase().includes(w) || g.fileName.toLowerCase().includes(w)))
      .sort((a, b) => a.title.localeCompare(b.title));
    return { data, offline: true };
  }
}

/** Teste une adresse et une clé sans modifier les réglages. */
async function test(serverUrl, apiKey) {
  const target = { serverUrl: settings.normalizeUrl(serverUrl), apiKey: String(apiKey || '').trim() };
  const info = JSON.parse(await request('/api/info', target));
  await request('/api/status', target);
  return info;
}

module.exports = { AppError, request, systems, games, search, test, headers, bios, rememberDownloaded };
