// Profils des joueurs : comptes (mot de passe haché avec scrypt), sessions par appareil (jeton
// aléatoire, seul son empreinte SHA-256 est stockée), connexion d'une TV par QR code validé depuis un
// téléphone déjà connecté, temps de jeu par jeu, sauvegardes en ligne (état et mémoire du jeu),
// historique des états de chaque jeu (par appareil, avec miniature),
// TV prêtes à recevoir un jeu diffusé depuis un téléphone du même profil, et erreurs signalées par
// les applications. Administration : liste, détail et journaux.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { config } from './config.js';
import { db, transaction } from './db.js';
import { HttpError } from './http-error.js';

const savesDir = path.join(config.dataDir, 'saves');
const statesDir = path.join(config.dataDir, 'states');

const USERNAME_RE = /^[\p{L}\p{N}._-]{3,32}$/u;
const MIN_PASSWORD = 6;
/** Tentatives de connexion échouées autorisées par identifiant sur la période. */
const MAX_FAILURES = 10;
const FAILURE_WINDOW_MINUTES = 15;
const PAIR_TTL_MS = 5 * 60 * 1000;
/** Une TV qui ne s'est pas annoncée depuis ce délai n'est plus proposée (application fermée, TV éteinte). */
const RECEIVER_TTL_MS = 45 * 1000;
/** Une session pour une durée de jeu ne peut pas déclarer plus de 24 h. */
const MAX_PLAY_SECONDS = 24 * 3600;
export const SAVE_KINDS = ['state', 'sram'];
const CORE_RE = /^[a-z0-9._-]{1,64}$/i;
/** Identifiant d'un état de l'historique, choisi par l'appareil (UUID). */
const STATE_ID_RE = /^[a-z0-9-]{8,64}$/i;
/** États gardés par jeu et par appareil, hors états épinglés (les plus anciens sont supprimés). */
export const STATE_HISTORY_LIMIT = 10;
const THUMBNAIL_TYPES = { 'image/jpeg': 'jpg', 'image/png': 'png' };

const text = (value, max) => (value == null || value === '' ? null : String(value).slice(0, max));

// ---------------------------------------------------------------------------
// Fichiers des sauvegardes et des états, compressés par les applications
// ---------------------------------------------------------------------------

/**
 * Encodage d'un envoi : « gzip » (compressé par l'application, Content-Encoding) ou « identity »
 * (version précédente des applications, ou compression sans gain). Gardé tel quel sur le disque.
 */
export function uploadEncoding(contentEncoding) {
  const value = String(contentEncoding || 'identity').trim().toLowerCase();
  if (value !== 'gzip' && value !== 'identity') throw new HttpError(415, 'errors.unsupportedEncoding');
  return value;
}

/** Écrit le fichier reçu tel quel (remplacement atomique). */
function writeStored(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(`${file}.tmp`, data);
  fs.renameSync(`${file}.tmp`, file);
}

/** Contenu d'origine d'un fichier enregistré ([encoding] : celui de l'envoi). */
export function readStored(file, encoding) {
  const data = fs.readFileSync(file);
  return encoding === 'gzip' ? zlib.gunzipSync(data) : data;
}

/** Taille d'origine annoncée par l'application (X-Uncompressed-Size), sinon celle reçue. */
const originalSize = (data, encoding, size) => (encoding === 'gzip' && Number(size) > 0 ? Math.round(Number(size)) : data.length);

// ---------------------------------------------------------------------------
// Mots de passe et jetons
// ---------------------------------------------------------------------------

export function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(String(password), salt, 32);
  return `scrypt$${salt.toString('hex')}$${hash.toString('hex')}`;
}

export function verifyPassword(password, stored) {
  const [scheme, saltHex, hashHex] = String(stored || '').split('$');
  if (scheme !== 'scrypt' || !saltHex || !hashHex) return false;
  const expected = Buffer.from(hashHex, 'hex');
  const actual = crypto.scryptSync(String(password ?? ''), Buffer.from(saltHex, 'hex'), expected.length);
  return crypto.timingSafeEqual(actual, expected);
}

const tokenHash = (token) => crypto.createHash('sha256').update(String(token)).digest('hex');

function validateUsername(username) {
  const name = String(username ?? '').trim();
  if (!USERNAME_RE.test(name)) throw new HttpError(400, 'errors.invalidUsername');
  return name;
}

function validatePassword(password) {
  if (typeof password !== 'string' || password.length < MIN_PASSWORD) {
    throw new HttpError(400, 'errors.passwordTooShort', { n: MIN_PASSWORD });
  }
  return password;
}

// ---------------------------------------------------------------------------
// Comptes et sessions
// ---------------------------------------------------------------------------

const publicUser = (row) => row && {
  id: row.id,
  username: row.username,
  disabled: Boolean(row.disabled),
  createdAt: row.created_at,
  lastLoginAt: row.last_login_at,
};

export function createUser(username, password) {
  const name = validateUsername(username);
  validatePassword(password);
  if (db.prepare('SELECT 1 FROM users WHERE username = ?').get(name)) throw new HttpError(409, 'errors.usernameTaken');
  const { lastInsertRowid } = db.prepare('INSERT INTO users (username, password_hash) VALUES (?, ?)').run(name, hashPassword(password));
  return publicUser(db.prepare('SELECT * FROM users WHERE id = ?').get(lastInsertRowid));
}

/** Appareil d'une requête : { device, platform, appVersion, ip, userAgent }. */
export function clientInfo(req, body = {}) {
  return {
    device: text(body.device ?? req.get('x-romcloud-device'), 100),
    platform: text(body.platform ?? req.get('x-romcloud-platform'), 30),
    appVersion: text(body.appVersion ?? req.get('x-romcloud-version'), 40),
    ip: text(req.ip || req.socket?.remoteAddress, 64),
    userAgent: text(req.get('user-agent'), 200),
  };
}

function logEvent(event, { userId = null, username = null, success, reason = null }, client) {
  db.prepare(`INSERT INTO login_events (user_id, username, event, success, reason, device, platform, app_version, ip, user_agent)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
    .run(userId, text(username, 64), event, success ? 1 : 0, reason, client.device, client.platform, client.appVersion, client.ip, client.userAgent);
}

/** Ouvre une session pour l'utilisateur sur cet appareil ; renvoie le jeton (montré une seule fois). */
function openSession(user, client, method) {
  const token = crypto.randomBytes(32).toString('hex');
  db.prepare(`INSERT INTO sessions (user_id, token_hash, device, platform, app_version, ip, method)
              VALUES (?, ?, ?, ?, ?, ?, ?)`)
    .run(user.id, tokenHash(token), client.device, client.platform, client.appVersion, client.ip, method);
  db.prepare("UPDATE users SET last_login_at = datetime('now') WHERE id = ?").run(user.id);
  return { token, user: publicUser(db.prepare('SELECT * FROM users WHERE id = ?').get(user.id)) };
}

export function register(username, password, client) {
  const user = createUser(username, password);
  logEvent('register', { userId: user.id, username: user.username, success: true }, client);
  return openSession(user, client, 'password');
}

export function login(username, password, client) {
  const name = String(username ?? '').trim();
  const failures = db.prepare(`SELECT COUNT(*) AS n FROM login_events
      WHERE event = 'login' AND success = 0 AND username = ? COLLATE NOCASE AND at > datetime('now', ?)`)
    .get(name, `-${FAILURE_WINDOW_MINUTES} minutes`).n;
  const fail = (reason, status, key, userId = null) => {
    logEvent('login', { userId, username: name, success: false, reason }, client);
    throw new HttpError(status, key);
  };
  if (failures >= MAX_FAILURES) fail('locked', 429, 'errors.tooManyAttempts');
  const row = db.prepare('SELECT * FROM users WHERE username = ?').get(name);
  if (!row || !verifyPassword(password, row.password_hash)) fail(row ? 'password' : 'unknown', 401, 'errors.badCredentials', row?.id);
  if (row.disabled) fail('disabled', 403, 'errors.accountDisabled', row.id);
  logEvent('login', { userId: row.id, username: row.username, success: true }, client);
  return openSession(row, client, 'password');
}

/** Utilisateur et session d'un jeton ; null si inconnu ou compte désactivé. Note l'activité. */
export function authenticate(token, client) {
  if (!token) return null;
  const row = db.prepare(`SELECT s.id AS session_id, s.last_seen_at, u.* FROM sessions s JOIN users u ON u.id = s.user_id
                          WHERE s.token_hash = ?`).get(tokenHash(token));
  if (!row || row.disabled) return null;
  // Activité notée au plus une fois par minute.
  db.prepare(`UPDATE sessions SET last_seen_at = datetime('now'), ip = ?, app_version = COALESCE(?, app_version)
              WHERE id = ? AND last_seen_at < datetime('now', '-1 minute')`)
    .run(client.ip, client.appVersion, row.session_id);
  return { user: publicUser(row), sessionId: row.session_id };
}

export function logout(auth, client) {
  db.prepare('DELETE FROM sessions WHERE id = ?').run(auth.sessionId);
  logEvent('logout', { userId: auth.user.id, username: auth.user.username, success: true }, client);
}

export function changePassword(auth, current, next) {
  const row = db.prepare('SELECT * FROM users WHERE id = ?').get(auth.user.id);
  if (!verifyPassword(current, row.password_hash)) throw new HttpError(401, 'errors.badCredentials');
  db.prepare('UPDATE users SET password_hash = ? WHERE id = ?').run(hashPassword(validatePassword(next)), row.id);
}

// ---------------------------------------------------------------------------
// Connexion d'une TV par QR code
// ---------------------------------------------------------------------------

// Demandes en cours (en mémoire : un redémarrage du serveur les annule, la TV en redemande une).
const pairs = new Map();
const PAIR_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

function prunePairs(now = Date.now()) {
  for (const [code, p] of pairs) if (p.expiresAt < now) pairs.delete(code);
}

/** La TV demande un code (affiché en QR code) et un secret qu'elle seule connaît pour l'attendre. */
export function createPair(client) {
  prunePairs();
  let code;
  do {
    code = Array.from(crypto.randomBytes(8), (b) => PAIR_ALPHABET[b % PAIR_ALPHABET.length]).join('');
  } while (pairs.has(code));
  const secret = crypto.randomBytes(24).toString('hex');
  const expiresAt = Date.now() + PAIR_TTL_MS;
  pairs.set(code, { secret, expiresAt, client, result: null });
  return { code, secret, expiresAt: new Date(expiresAt).toISOString() };
}

const normalizeCode = (code) => String(code ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '');

/** Appareil qui attend derrière un code (affiché sur le téléphone avant de valider). */
export function pairInfo(code) {
  prunePairs();
  const pair = pairs.get(normalizeCode(code));
  if (!pair) throw new HttpError(404, 'errors.pairExpired');
  return { device: pair.client.device, platform: pair.client.platform, expiresAt: new Date(pair.expiresAt).toISOString() };
}

/** Le téléphone connecté valide le code : une session est ouverte pour la TV. */
export function approvePair(code, auth, phoneClient) {
  prunePairs();
  const pair = pairs.get(normalizeCode(code));
  if (!pair || pair.result) throw new HttpError(404, 'errors.pairExpired');
  pair.result = openSession(auth.user, pair.client, 'qr');
  logEvent('pair', { userId: auth.user.id, username: auth.user.username, success: true, reason: `${phoneClient.device || ''} -> ${pair.client.device || ''}` }, pair.client);
  return { device: pair.client.device };
}

/** La TV attend : { status: pending | approved, token?, user? } ; le jeton n'est remis qu'une fois. */
export function pollPair(code, secret) {
  prunePairs();
  const key = normalizeCode(code);
  const pair = pairs.get(key);
  if (!pair) return { status: 'expired' };
  const a = Buffer.from(String(secret ?? ''));
  const b = Buffer.from(pair.secret);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) throw new HttpError(403, 'errors.pairSecret');
  if (!pair.result) return { status: 'pending' };
  pairs.delete(key);
  return { status: 'approved', ...pair.result };
}

// ---------------------------------------------------------------------------
// Diffusion d'un jeu du téléphone sur une TV du même profil
// ---------------------------------------------------------------------------

// TV prêtes à recevoir, par session (en mémoire : elles se réannoncent toutes les quelques secondes).
const receivers = new Map();

function pruneReceivers(now = Date.now()) {
  for (const [id, r] of receivers) if (r.seenAt + RECEIVER_TTL_MS < now) receivers.delete(id);
}

const ADDRESS_RE = /^[0-9a-f.:%a-z]{2,64}$/i;

/**
 * La TV (application ouverte) s'annonce : adresses sur le réseau local, port d'écoute et clé que le
 * téléphone devra présenter. Réservé aux sessions TV.
 */
export function announceReceiver(auth, body, client) {
  if (client.platform !== 'androidtv') throw new HttpError(400, 'errors.streamTvOnly');
  const addresses = (Array.isArray(body?.addresses) ? body.addresses : []).map(String).filter((a) => ADDRESS_RE.test(a)).slice(0, 8);
  const port = Number(body?.port);
  const key = String(body?.key ?? '');
  if (!addresses.length || !Number.isInteger(port) || port < 1 || port > 65535 || !/^[0-9a-f]{16,128}$/i.test(key)) {
    throw new HttpError(400, 'errors.streamReceiver');
  }
  pruneReceivers();
  receivers.set(auth.sessionId, {
    userId: auth.user.id, device: client.device, addresses, port, key, width: Number(body.width) || null, height: Number(body.height) || null, seenAt: Date.now(),
  });
  return { ttlSeconds: RECEIVER_TTL_MS / 1000 };
}

/** La TV quitte l'application : plus proposée. */
export function withdrawReceiver(auth) {
  receivers.delete(auth.sessionId);
}

/** TV allumées (application ouverte) du profil, hors l'appareil qui demande. */
export function listReceivers(auth) {
  pruneReceivers();
  return [...receivers]
    .filter(([id, r]) => r.userId === auth.user.id && id !== auth.sessionId)
    .map(([id, r]) => ({ id, device: r.device, addresses: r.addresses, port: r.port, key: r.key, width: r.width, height: r.height }));
}

// ---------------------------------------------------------------------------
// Temps de jeu
// ---------------------------------------------------------------------------

function requireGame(gameId) {
  const id = Number(gameId);
  if (!Number.isInteger(id) || !db.prepare('SELECT 1 FROM games WHERE id = ?').get(id)) {
    throw new HttpError(404, 'errors.gameNotFound', { id: gameId });
  }
  return id;
}

/** Ajoute une partie (durée en secondes) au temps de jeu du jeu. */
export function addPlaytime(userId, gameId, seconds) {
  const id = requireGame(gameId);
  const s = Math.round(Number(seconds));
  if (!Number.isFinite(s) || s <= 0) throw new HttpError(400, 'errors.invalidDuration');
  db.prepare(`INSERT INTO playtime (user_id, game_id, seconds, sessions) VALUES (?, ?, ?, 1)
              ON CONFLICT (user_id, game_id) DO UPDATE SET seconds = seconds + excluded.seconds,
                sessions = sessions + 1, last_played_at = datetime('now')`)
    .run(userId, id, Math.min(s, MAX_PLAY_SECONDS));
  return playtimeOf(userId, id);
}

const playtimeRow = (r) => ({ gameId: r.game_id, seconds: r.seconds, sessions: r.sessions, firstPlayedAt: r.first_played_at, lastPlayedAt: r.last_played_at });

export function playtimeOf(userId, gameId) {
  const r = db.prepare('SELECT * FROM playtime WHERE user_id = ? AND game_id = ?').get(userId, gameId);
  return r ? playtimeRow(r) : { gameId, seconds: 0, sessions: 0, firstPlayedAt: null, lastPlayedAt: null };
}

export function listPlaytime(userId) {
  return db.prepare('SELECT * FROM playtime WHERE user_id = ? ORDER BY last_played_at DESC').all(userId).map(playtimeRow);
}

// ---------------------------------------------------------------------------
// Sauvegardes en ligne
// ---------------------------------------------------------------------------

function saveKey(gameId, core, kind) {
  const id = requireGame(gameId);
  if (!SAVE_KINDS.includes(kind)) throw new HttpError(400, 'errors.invalidSaveKind');
  if (!CORE_RE.test(String(core))) throw new HttpError(400, 'errors.invalidCore');
  return { id, core: String(core), kind };
}

const saveFile = (userId, gameId, core, kind) => path.join(savesDir, String(userId), String(gameId), `${core}.${kind}`);

const saveRow = (r) => ({
  gameId: r.game_id, core: r.core, kind: r.kind, size: r.size, md5: r.md5,
  savedAt: new Date(r.saved_at).toISOString(), device: r.device, platform: r.platform, uploadedAt: r.uploaded_at,
  // Place sur le disque (compressé par l'application) ; null pour un fichier d'une version précédente.
  storedSize: r.stored_size ?? null, compressed: r.encoding === 'gzip',
});

export function listSaves(userId, gameId) {
  const rows = gameId == null
    ? db.prepare('SELECT * FROM saves WHERE user_id = ? ORDER BY saved_at DESC').all(userId)
    : db.prepare('SELECT * FROM saves WHERE user_id = ? AND game_id = ? ORDER BY saved_at DESC').all(userId, Number(gameId));
  return rows.map(saveRow);
}

/**
 * Enregistre une sauvegarde ; [savedAt] : date du fichier sur l'appareil (ms), pour garder la plus
 * récente ; [encoding] : « gzip » si l'application l'a compressée ([size] : taille d'origine).
 */
export async function putSave(userId, gameId, core, kind, data, savedAt, client, { encoding = 'identity', size } = {}) {
  const key = saveKey(gameId, core, kind);
  if (!Buffer.isBuffer(data) || !data.length) throw new HttpError(400, 'errors.emptySave');
  const time = Number(savedAt) > 0 ? Math.round(Number(savedAt)) : Date.now();
  const file = saveFile(userId, key.id, key.core, key.kind);
  writeStored(file, data);
  const md5 = crypto.createHash('md5').update(data).digest('hex');
  db.prepare(`INSERT INTO saves (user_id, game_id, core, kind, size, md5, saved_at, device, platform, encoding, stored_size)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              ON CONFLICT (user_id, game_id, core, kind) DO UPDATE SET size = excluded.size, md5 = excluded.md5,
                saved_at = excluded.saved_at, device = excluded.device, platform = excluded.platform,
                encoding = excluded.encoding, stored_size = excluded.stored_size, uploaded_at = datetime('now')`)
    .run(userId, key.id, key.core, key.kind, originalSize(data, encoding, size), md5, time, client.device, client.platform, encoding, data.length);
  return saveRow(db.prepare('SELECT * FROM saves WHERE user_id = ? AND game_id = ? AND core = ? AND kind = ?').get(userId, key.id, key.core, key.kind));
}

/** Fichier, encodage sur le disque et informations d'une sauvegarde (404 si absente). */
export function getSave(userId, gameId, core, kind) {
  const key = saveKey(gameId, core, kind);
  const row = db.prepare('SELECT * FROM saves WHERE user_id = ? AND game_id = ? AND core = ? AND kind = ?').get(userId, key.id, key.core, key.kind);
  const file = saveFile(userId, key.id, key.core, key.kind);
  if (!row || !fs.existsSync(file)) throw new HttpError(404, 'errors.saveNotFound');
  return { file, encoding: row.encoding, save: saveRow(row) };
}

export function deleteSave(userId, gameId, core, kind) {
  const key = saveKey(gameId, core, kind);
  db.prepare('DELETE FROM saves WHERE user_id = ? AND game_id = ? AND core = ? AND kind = ?').run(userId, key.id, key.core, key.kind);
  fs.rmSync(saveFile(userId, key.id, key.core, key.kind), { force: true });
}

// ---------------------------------------------------------------------------
// Historique des états de sauvegarde
// ---------------------------------------------------------------------------

const stateFile = (userId, gameId, id) => path.join(statesDir, String(userId), String(gameId), `${id}.state`);
const thumbnailFile = (userId, gameId, id, type) => path.join(statesDir, String(userId), String(gameId), `${id}.${THUMBNAIL_TYPES[type]}`);

const stateRow = (r) => ({
  id: r.id, gameId: r.game_id, core: r.core, createdAt: new Date(r.created_at).toISOString(), device: r.device,
  platform: r.platform, size: r.size, md5: r.md5, thumbnail: Boolean(r.thumbnail), pinned: Boolean(r.pinned), uploadedAt: r.uploaded_at,
  storedSize: r.stored_size ?? null, compressed: r.encoding === 'gzip',
});

function requireStateId(id) {
  if (!STATE_ID_RE.test(String(id))) throw new HttpError(400, 'errors.invalidStateId');
  return String(id);
}

function requireState(userId, id) {
  const row = db.prepare('SELECT * FROM state_history WHERE id = ? AND user_id = ?').get(requireStateId(id), userId);
  if (!row) throw new HttpError(404, 'errors.stateNotFound');
  return row;
}

function removeStateFiles(row) {
  fs.rmSync(stateFile(row.user_id, row.game_id, row.id), { force: true });
  if (row.thumbnail) fs.rmSync(thumbnailFile(row.user_id, row.game_id, row.id, row.thumbnail), { force: true });
}

/** Garde les [STATE_HISTORY_LIMIT] états les plus récents du jeu pour cet appareil (épinglés en plus). */
function pruneStates(userId, gameId, device, platform) {
  const old = db.prepare(`SELECT * FROM state_history
      WHERE user_id = ? AND game_id = ? AND device IS ? AND platform IS ? AND pinned = 0
      ORDER BY created_at DESC LIMIT -1 OFFSET ?`).all(userId, gameId, device, platform, STATE_HISTORY_LIMIT);
  for (const row of old) {
    db.prepare('DELETE FROM state_history WHERE id = ?').run(row.id);
    removeStateFiles(row);
  }
}

/** États en ligne du profil (d'un jeu, ou tous), du plus récent au plus ancien. */
export function listStates(userId, gameId) {
  const rows = gameId == null
    ? db.prepare('SELECT * FROM state_history WHERE user_id = ? ORDER BY created_at DESC').all(userId)
    : db.prepare('SELECT * FROM state_history WHERE user_id = ? AND game_id = ? ORDER BY created_at DESC').all(userId, Number(gameId));
  return rows.map(stateRow);
}

/**
 * Enregistre un état de l'historique (renvoyé avec le même identifiant : remplacé). [createdAt] :
 * date de l'état sur l'appareil (ms) ; l'appareil d'origine est celui qui l'envoie.
 */
export async function putState(userId, gameId, core, id, data, { createdAt, pinned, encoding = 'identity', size } = {}, client) {
  const game = requireGame(gameId);
  const stateId = requireStateId(id);
  if (!CORE_RE.test(String(core))) throw new HttpError(400, 'errors.invalidCore');
  if (!Buffer.isBuffer(data) || !data.length) throw new HttpError(400, 'errors.emptySave');
  const existing = db.prepare('SELECT * FROM state_history WHERE id = ?').get(stateId);
  // Identifiant d'un autre profil ou d'un autre jeu : refusé (jamais écrasé).
  if (existing && (existing.user_id !== userId || existing.game_id !== game)) throw new HttpError(409, 'errors.stateConflict');
  const time = Number(createdAt) > 0 ? Math.round(Number(createdAt)) : Date.now();
  const file = stateFile(userId, game, stateId);
  writeStored(file, data);
  const md5 = crypto.createHash('md5').update(data).digest('hex');
  db.prepare(`INSERT INTO state_history (id, user_id, game_id, core, created_at, device, platform, size, md5, pinned, encoding, stored_size)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              ON CONFLICT (id) DO UPDATE SET core = excluded.core, created_at = excluded.created_at, size = excluded.size,
                md5 = excluded.md5, pinned = excluded.pinned, encoding = excluded.encoding, stored_size = excluded.stored_size,
                uploaded_at = datetime('now')`)
    .run(stateId, userId, game, String(core), time, client.device, client.platform, originalSize(data, encoding, size), md5, pinned ? 1 : 0, encoding, data.length);
  const row = db.prepare('SELECT * FROM state_history WHERE id = ?').get(stateId);
  pruneStates(userId, game, row.device, row.platform);
  const kept = db.prepare('SELECT * FROM state_history WHERE id = ?').get(stateId);
  return kept ? stateRow(kept) : null;
}

/** Miniature de l'état (image JPEG ou PNG de l'écran du jeu). */
export function putStateThumbnail(userId, id, data, type) {
  const row = requireState(userId, id);
  const mime = String(type || '').split(';')[0].trim().toLowerCase();
  if (!THUMBNAIL_TYPES[mime] || !Buffer.isBuffer(data) || !data.length) throw new HttpError(400, 'errors.invalidThumbnail');
  if (row.thumbnail && row.thumbnail !== mime) fs.rmSync(thumbnailFile(userId, row.game_id, row.id, row.thumbnail), { force: true });
  const file = thumbnailFile(userId, row.game_id, row.id, mime);
  fs.writeFileSync(`${file}.tmp`, data);
  fs.renameSync(`${file}.tmp`, file);
  db.prepare('UPDATE state_history SET thumbnail = ? WHERE id = ?').run(mime, row.id);
  return stateRow({ ...row, thumbnail: mime });
}

/** Fichier de l'état et son encodage sur le disque (404 si absent). */
export function getState(userId, id) {
  const row = requireState(userId, id);
  const file = stateFile(userId, row.game_id, row.id);
  if (!fs.existsSync(file)) throw new HttpError(404, 'errors.stateNotFound');
  return { file, encoding: row.encoding, state: stateRow(row) };
}

/** Fichier de la miniature et son type (404 si l'état n'en a pas). */
export function getStateThumbnail(userId, id) {
  const row = requireState(userId, id);
  const file = row.thumbnail && thumbnailFile(userId, row.game_id, row.id, row.thumbnail);
  if (!file || !fs.existsSync(file)) throw new HttpError(404, 'errors.stateNotFound');
  return { file, type: row.thumbnail };
}

/** Épingle (jamais supprimé par la limite de l'historique) ou désépingle un état ; null s'il a été supprimé. */
export function pinState(userId, id, pinned) {
  const row = requireState(userId, id);
  db.prepare('UPDATE state_history SET pinned = ? WHERE id = ?').run(pinned ? 1 : 0, row.id);
  if (!pinned) pruneStates(userId, row.game_id, row.device, row.platform);
  const updated = db.prepare('SELECT * FROM state_history WHERE id = ?').get(row.id);
  return updated ? stateRow(updated) : null;
}

export function deleteState(userId, id) {
  const row = requireState(userId, id);
  db.prepare('DELETE FROM state_history WHERE id = ?').run(row.id);
  removeStateFiles(row);
}

// ---------------------------------------------------------------------------
// Erreurs des applications
// ---------------------------------------------------------------------------

/** Détail d'un rapport (journal de l'application joint par l'utilisateur) : 200 000 caractères au plus. */
const MAX_ERROR_DETAILS = 200_000;

/**
 * Enregistre une erreur ou un rapport envoyé par une application ; [body.at] : moment de l'erreur
 * (ms ou ISO), pour un rapport gardé hors ligne puis envoyé plus tard (30 jours au plus, sinon maintenant).
 */
export function logError(body, auth, client) {
  const message = text(body?.message, 2000);
  if (!message) throw new HttpError(400, 'errors.messageRequired');
  const at = new Date(Number(body.at) || body.at || Date.now());
  const recent = !Number.isNaN(at.getTime()) && at.getTime() <= Date.now() + 60_000 && at.getTime() > Date.now() - 30 * 86400_000;
  const when = (recent ? at : new Date()).toISOString().replace('T', ' ').slice(0, 19);
  db.prepare(`INSERT INTO error_logs (user_id, session_id, platform, device, app_version, context, message, details, ip, at)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
    .run(auth?.user.id ?? null, auth?.sessionId ?? null, client.platform, client.device, client.appVersion,
      text(body.context, 200), message, text(body.details, MAX_ERROR_DETAILS), client.ip, when);
}

// ---------------------------------------------------------------------------
// Administration
// ---------------------------------------------------------------------------

function requireUser(id) {
  const row = db.prepare('SELECT * FROM users WHERE id = ?').get(Number(id));
  if (!row) throw new HttpError(404, 'errors.userNotFound');
  return row;
}

export function adminListUsers() {
  return db.prepare(`SELECT u.*,
      (SELECT COUNT(*) FROM sessions s WHERE s.user_id = u.id) AS session_count,
      (SELECT MAX(last_seen_at) FROM sessions s WHERE s.user_id = u.id) AS last_seen_at,
      (SELECT COALESCE(SUM(seconds), 0) FROM playtime p WHERE p.user_id = u.id) AS play_seconds,
      (SELECT COUNT(*) FROM playtime p WHERE p.user_id = u.id) AS games_played,
      (SELECT COALESCE(SUM(COALESCE(stored_size, size)), 0) FROM saves v WHERE v.user_id = u.id)
        + (SELECT COALESCE(SUM(COALESCE(stored_size, size)), 0) FROM state_history h WHERE h.user_id = u.id) AS saves_size,
      (SELECT COUNT(*) FROM error_logs e WHERE e.user_id = u.id) AS error_count
    FROM users u ORDER BY u.username COLLATE NOCASE`).all().map((r) => ({
    ...publicUser(r),
    sessions: r.session_count,
    lastSeenAt: r.last_seen_at,
    playSeconds: r.play_seconds,
    gamesPlayed: r.games_played,
    savesSize: r.saves_size,
    errors: r.error_count,
  }));
}

const sessionRow = (s) => ({
  id: s.id, device: s.device, platform: s.platform, appVersion: s.app_version, ip: s.ip,
  method: s.method, createdAt: s.created_at, lastSeenAt: s.last_seen_at,
});
const loginRow = (e) => ({
  id: e.id, userId: e.user_id, username: e.username, event: e.event, success: Boolean(e.success), reason: e.reason,
  device: e.device, platform: e.platform, appVersion: e.app_version, ip: e.ip, userAgent: e.user_agent, at: e.at,
});
const errorRow = (e) => ({
  id: e.id, userId: e.user_id, username: e.username ?? null, platform: e.platform, device: e.device, appVersion: e.app_version,
  context: e.context, message: e.message, details: e.details, ip: e.ip, at: e.at,
});
const gameLabel = (gameId) => db.prepare('SELECT g.title, s.name AS system FROM games g JOIN systems s ON s.id = g.system_id WHERE g.id = ?').get(gameId);

export function adminUserDetail(id) {
  const user = requireUser(id);
  const withGame = (r) => ({ ...r, ...(gameLabel(r.gameId) || {}) });
  return {
    user: publicUser(user),
    sessions: db.prepare('SELECT * FROM sessions WHERE user_id = ? ORDER BY last_seen_at DESC').all(user.id).map(sessionRow),
    logins: db.prepare('SELECT * FROM login_events WHERE user_id = ? ORDER BY id DESC LIMIT 200').all(user.id).map(loginRow),
    playtime: listPlaytime(user.id).map(withGame),
    saves: listSaves(user.id).map(withGame),
    states: listStates(user.id).map(withGame),
    errors: db.prepare('SELECT * FROM error_logs WHERE user_id = ? ORDER BY id DESC LIMIT 200').all(user.id).map(errorRow),
  };
}

export function adminUpdateUser(id, { username, password, disabled } = {}) {
  const user = requireUser(id);
  transaction(() => {
    if (username !== undefined && username !== user.username) {
      const name = validateUsername(username);
      if (db.prepare('SELECT 1 FROM users WHERE username = ? AND id <> ?').get(name, user.id)) throw new HttpError(409, 'errors.usernameTaken');
      db.prepare('UPDATE users SET username = ? WHERE id = ?').run(name, user.id);
    }
    if (password !== undefined && password !== '') {
      db.prepare('UPDATE users SET password_hash = ? WHERE id = ?').run(hashPassword(validatePassword(password)), user.id);
      // Nouveau mot de passe : les appareils connectés doivent se reconnecter.
      db.prepare('DELETE FROM sessions WHERE user_id = ?').run(user.id);
    }
    if (disabled !== undefined) {
      db.prepare('UPDATE users SET disabled = ? WHERE id = ?').run(disabled ? 1 : 0, user.id);
      if (disabled) db.prepare('DELETE FROM sessions WHERE user_id = ?').run(user.id);
    }
  });
  return publicUser(requireUser(id));
}

export function adminDeleteUser(id) {
  const user = requireUser(id);
  db.prepare('DELETE FROM users WHERE id = ?').run(user.id);
  fs.rmSync(path.join(savesDir, String(user.id)), { recursive: true, force: true });
  fs.rmSync(path.join(statesDir, String(user.id)), { recursive: true, force: true });
}

export function adminRevokeSession(userId, sessionId) {
  const { changes } = db.prepare('DELETE FROM sessions WHERE id = ? AND user_id = ?').run(Number(sessionId), Number(userId));
  if (!changes) throw new HttpError(404, 'errors.sessionNotFound');
}

export function adminLoginLog(limit = 300) {
  return db.prepare('SELECT * FROM login_events ORDER BY id DESC LIMIT ?').all(Math.min(Number(limit) || 300, 2000)).map(loginRow);
}

export function adminErrorLog(limit = 300) {
  return db.prepare(`SELECT e.*, u.username FROM error_logs e LEFT JOIN users u ON u.id = e.user_id ORDER BY e.id DESC LIMIT ?`)
    .all(Math.min(Number(limit) || 300, 2000)).map(errorRow);
}

export function adminClearErrors() {
  db.prepare('DELETE FROM error_logs').run();
}
