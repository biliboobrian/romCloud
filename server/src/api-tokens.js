// Clés d'accès générées depuis l'administration, en plus de la clé des applications (API_KEY) et de
// celle d'administration (ADMIN_KEY) : mêmes droits que la clé des applications, mais sur un nombre
// limité d'appareils. Un appareil (identifiant envoyé par l'application : en-tête
// X-RomCloud-Device-Id, ou ?device=) qui a utilisé la clé une fois prend une place ; une fois toutes
// les places prises, les autres appareils sont refusés. Retirer un appareil depuis l'administration
// libère sa place. Seule l'empreinte SHA-256 de la clé est gardée : elle n'est montrée qu'à sa création.
import crypto from 'node:crypto';
import { db, transaction } from './db.js';
import { HttpError } from './http-error.js';

/** Préfixe des clés générées (reconnues sans chercher en base les autres clés). */
const PREFIX = 'rct_';
export const MAX_DEVICES_LIMIT = 1000;
const DEVICE_ID_RE = /^[A-Za-z0-9._:-]{8,128}$/;

const hash = (key) => crypto.createHash('sha256').update(String(key)).digest('hex');
const text = (value, max) => (value == null || value === '' ? null : String(value).slice(0, max));

/** Identifiant d'appareil valable (sinon null). */
export const deviceIdOf = (value) => (DEVICE_ID_RE.test(String(value ?? '')) ? String(value) : null);

function validateName(name) {
  const value = String(name ?? '').trim();
  if (!value || value.length > 64) throw new HttpError(400, 'errors.invalidTokenName');
  return value;
}

function validateMaxDevices(value) {
  const n = Number(value);
  if (!Number.isInteger(n) || n < 1 || n > MAX_DEVICES_LIMIT) throw new HttpError(400, 'errors.invalidMaxDevices', { max: MAX_DEVICES_LIMIT });
  return n;
}

const tokenRow = (r) => ({
  id: r.id,
  name: r.name,
  prefix: r.prefix,
  maxDevices: r.max_devices,
  devices: r.device_count ?? 0,
  disabled: Boolean(r.disabled),
  createdAt: r.created_at,
  lastUsedAt: r.last_used_at,
});

const deviceRow = (d) => ({
  deviceId: d.device_id, device: d.device, platform: d.platform, appVersion: d.app_version, ip: d.ip,
  firstSeenAt: d.first_seen_at, lastSeenAt: d.last_seen_at,
});

const SELECT = `SELECT t.*, (SELECT COUNT(*) FROM api_token_devices d WHERE d.token_id = t.id) AS device_count FROM api_tokens t`;

function requireToken(id) {
  const row = db.prepare(`${SELECT} WHERE t.id = ?`).get(Number(id));
  if (!row) throw new HttpError(404, 'errors.tokenNotFound');
  return row;
}

/**
 * Vérifie la clé [given] pour l'appareil [deviceId] ([client] : nom, plateforme, version, adresse) :
 * « unknown » (pas une clé générée), « ok », « disabled », « device-required » (appareil non
 * identifié), « device-limit » (toutes les places prises par d'autres appareils).
 */
export function checkToken(given, deviceId, client = {}) {
  if (!given || !String(given).startsWith(PREFIX)) return 'unknown';
  const row = db.prepare('SELECT * FROM api_tokens WHERE token_hash = ?').get(hash(given));
  if (!row) return 'unknown';
  if (row.disabled) return 'disabled';
  const id = deviceIdOf(deviceId);
  if (!id) return 'device-required';
  return transaction(() => {
    const known = db.prepare('SELECT last_seen_at FROM api_token_devices WHERE token_id = ? AND device_id = ?').get(row.id, id);
    if (known) {
      // Activité notée au plus une fois par minute.
      db.prepare(`UPDATE api_token_devices SET last_seen_at = datetime('now'), ip = ?, device = COALESCE(?, device),
                    platform = COALESCE(?, platform), app_version = COALESCE(?, app_version)
                  WHERE token_id = ? AND device_id = ? AND last_seen_at < datetime('now', '-1 minute')`)
        .run(text(client.ip, 64), text(client.device, 100), text(client.platform, 30), text(client.appVersion, 40), row.id, id);
    } else {
      const used = db.prepare('SELECT COUNT(*) AS n FROM api_token_devices WHERE token_id = ?').get(row.id).n;
      if (used >= row.max_devices) return 'device-limit';
      db.prepare(`INSERT INTO api_token_devices (token_id, device_id, device, platform, app_version, ip) VALUES (?, ?, ?, ?, ?, ?)`)
        .run(row.id, id, text(client.device, 100), text(client.platform, 30), text(client.appVersion, 40), text(client.ip, 64));
    }
    db.prepare("UPDATE api_tokens SET last_used_at = datetime('now') WHERE id = ? AND (last_used_at IS NULL OR last_used_at < datetime('now', '-1 minute'))")
      .run(row.id);
    return 'ok';
  });
}

/** Crée une clé ([name], [maxDevices] appareils) ; la clé elle-même n'est renvoyée qu'ici. */
export function createToken({ name, maxDevices } = {}) {
  const label = validateName(name);
  const max = validateMaxDevices(maxDevices);
  const key = PREFIX + crypto.randomBytes(24).toString('base64url');
  const { lastInsertRowid } = db.prepare('INSERT INTO api_tokens (name, token_hash, prefix, max_devices) VALUES (?, ?, ?, ?)')
    .run(label, hash(key), key.slice(0, PREFIX.length + 6), max);
  return { ...tokenRow(requireToken(lastInsertRowid)), token: key };
}

export function listTokens() {
  return db.prepare(`${SELECT} ORDER BY t.created_at DESC, t.id DESC`).all().map(tokenRow);
}

/** Clé et ses appareils (du plus récemment vu au plus ancien). */
export function tokenDetail(id) {
  const row = requireToken(id);
  const devices = db.prepare('SELECT * FROM api_token_devices WHERE token_id = ? ORDER BY last_seen_at DESC').all(row.id).map(deviceRow);
  return { ...tokenRow(row), deviceList: devices };
}

/**
 * Modifie le nom, le nombre d'appareils ou l'état de la clé. Nombre réduit sous celui des appareils
 * déjà enregistrés : ils gardent l'accès, aucun nouveau n'est accepté tant qu'il y en a trop.
 */
export function updateToken(id, { name, maxDevices, disabled } = {}) {
  const row = requireToken(id);
  if (name !== undefined) db.prepare('UPDATE api_tokens SET name = ? WHERE id = ?').run(validateName(name), row.id);
  if (maxDevices !== undefined) db.prepare('UPDATE api_tokens SET max_devices = ? WHERE id = ?').run(validateMaxDevices(maxDevices), row.id);
  if (disabled !== undefined) db.prepare('UPDATE api_tokens SET disabled = ? WHERE id = ?').run(disabled ? 1 : 0, row.id);
  return tokenRow(requireToken(row.id));
}

export function deleteToken(id) {
  const row = requireToken(id);
  db.prepare('DELETE FROM api_tokens WHERE id = ?').run(row.id);
}

/** Retire un appareil de la clé : sa place est libérée (il la reprendra s'il revient et qu'il en reste). */
export function removeTokenDevice(id, deviceId) {
  const row = requireToken(id);
  const { changes } = db.prepare('DELETE FROM api_token_devices WHERE token_id = ? AND device_id = ?').run(row.id, String(deviceId));
  if (!changes) throw new HttpError(404, 'errors.tokenDeviceNotFound');
}
