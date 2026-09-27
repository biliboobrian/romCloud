// Téléchargements des ROMs et des BIOS : fichier « .part » et reprise (requête HTTP Range).
const fs = require('node:fs');
const path = require('node:path');
const { Readable } = require('node:stream');
const { pipeline } = require('node:stream/promises');
const { Transform } = require('node:stream');
const settings = require('./settings');
const library = require('./library');
const { AppError, headers } = require('./api');

const active = new Map(); // gameId -> { controller, state }
let notify = () => {};

/** Fonction appelée à chaque changement : (gameId, état | null, événement). */
function onUpdate(fn) {
  notify = fn;
}

function states() {
  return Object.fromEntries([...active].map(([id, d]) => [id, d.state]));
}

function update(id, state, event) {
  const d = active.get(id);
  if (d && state) d.state = state;
  notify(id, state, event);
}

/**
 * Télécharge d'abord les `bios` indiqués (BIOS manquants du système), puis le jeu sauf si
 * `includeRom` vaut false. La progression couvre l'ensemble.
 */
async function start(system, game, { bios = [], includeRom = true } = {}) {
  if (active.has(game.id) && active.get(game.id).state.status === 'running') return;
  if (!includeRom && !bios.length) return;
  const total = bios.reduce((n, b) => n + b.size, 0) + (includeRom ? game.size : 0);
  const controller = new AbortController();
  const running = (bytes) => ({ status: 'running', bytes, total, title: game.title });
  active.set(game.id, { controller, state: running(0) });
  update(game.id, running(0));
  try {
    const { serverUrl } = settings.load();
    let done = 0;
    const progress = (bytes) => update(game.id, running(done + bytes));
    for (const b of bios) {
      await fetchTo(`${serverUrl}/api/bios/${b.id}/file`, library.biosFile(b), b.size, controller.signal, progress);
      done += b.size;
    }
    if (includeRom) {
      await fetchTo(`${serverUrl}/api/games/${game.id}/file`, library.fileFor(system, game), game.size, controller.signal, progress);
    }
    active.delete(game.id);
    update(game.id, null, { type: 'completed', systemId: system.id, gameId: game.id, title: game.title, romIncluded: includeRom });
  } catch (err) {
    if (controller.signal.aborted) {
      active.delete(game.id);
      update(game.id, null, null);
      return;
    }
    const error = { key: err.key || 'errors.download', vars: err.vars || { detail: err.message }, message: err.message };
    active.get(game.id).state = { status: 'failed', title: game.title, error };
    update(game.id, active.get(game.id).state, { type: 'failed', gameId: game.id, title: game.title, error });
  }
}

function cancel(gameId) {
  active.get(gameId)?.controller.abort();
  active.delete(gameId);
  update(gameId, null, null);
}

function dismissError(gameId) {
  if (active.get(gameId)?.state.status === 'failed') {
    active.delete(gameId);
    update(gameId, null, null);
  }
}

/** Télécharge `url` vers `target` (via « .part », avec reprise) ; `onBytes` reçoit les octets reçus. */
async function fetchTo(url, target, expectedSize, signal, onBytes) {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const part = `${target}.part`;
  let offset = fs.existsSync(part) ? fs.statSync(part).size : 0;
  if (offset > expectedSize) {
    fs.rmSync(part);
    offset = 0;
  }

  const h = headers(settings.load().apiKey);
  if (offset > 0) h.Range = `bytes=${offset}-`;
  const res = await fetch(url, { headers: h, signal });
  if (res.status === 416) {
    offset = fs.statSync(part).size; // déjà complet
  } else {
    if (!res.ok) throw new AppError(res.status === 401 ? 'errors.apiKey' : 'errors.http', { status: res.status });
    const append = res.status === 206 && offset > 0;
    let bytes = append ? offset : 0;
    let last = 0;
    const progress = new Transform({
      transform(chunk, _enc, cb) {
        bytes += chunk.length;
        const now = Date.now();
        if (now - last > 250) {
          last = now;
          onBytes(bytes);
        }
        cb(null, chunk);
      },
    });
    await pipeline(Readable.fromWeb(res.body), progress, fs.createWriteStream(part, { flags: append ? 'a' : 'w' }), { signal });
  }
  const size = fs.statSync(part).size;
  if (size !== expectedSize) throw new AppError('errors.wrongSize', { got: size, expected: expectedSize });
  fs.rmSync(target, { force: true });
  fs.renameSync(part, target);
}

module.exports = { start, cancel, dismissError, states, onUpdate };
