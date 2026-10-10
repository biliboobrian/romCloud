// Journal des téléchargements (ROM, BIOS, APK) : qui (profil connecté ou anonyme, appareil,
// plateforme, version de l'application, IP), quoi (jeu, système, fichier) et combien d'octets ont été
// envoyés. Une ligne par requête : un téléchargement repris (requête Range) en ajoute une, marquée
// comme reprise. Statistiques pour l'administration : par type, appareil, profil, jeu et jour.
import { db } from './db.js';

export const DOWNLOAD_KINDS = ['game', 'bios', 'apk'];

db.exec(`
  CREATE TABLE IF NOT EXISTS download_logs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    at          TEXT NOT NULL DEFAULT (datetime('now')),
    kind        TEXT NOT NULL,
    item_id     INTEGER,
    system_id   TEXT,
    label       TEXT,
    file_size   INTEGER,
    bytes       INTEGER NOT NULL DEFAULT 0,
    range_start INTEGER NOT NULL DEFAULT 0,
    complete    INTEGER NOT NULL DEFAULT 0,
    status      INTEGER,
    user_id     INTEGER REFERENCES users(id) ON DELETE SET NULL,
    device      TEXT,
    platform    TEXT,
    app_version TEXT,
    ip          TEXT,
    user_agent  TEXT
  );
  CREATE INDEX IF NOT EXISTS download_logs_at ON download_logs(at);
  CREATE INDEX IF NOT EXISTS download_logs_user ON download_logs(user_id);
`);

/** Début de la plage demandée (« bytes=1048576- » -> 1048576), 0 sans requête Range. */
export function rangeStart(header) {
  const m = /^bytes=(\d+)-/.exec(String(header || ''));
  return m ? Number(m[1]) : 0;
}

/**
 * Note le téléchargement servi par cette réponse quand elle se termine (ou est interrompue) :
 * [item] = { kind, itemId, systemId, label, fileSize } ; [auth] : profil connecté ou null.
 * Octets comptés sur la connexion (en-têtes compris, à quelques centaines d'octets près).
 */
export function trackDownload(req, res, item, auth, client) {
  if (req.method !== 'GET') return;
  const socket = req.socket;
  const before = socket?.bytesWritten ?? 0;
  const start = rangeStart(req.get('range'));
  let logged = false;
  const log = (finished) => {
    if (logged) return;
    logged = true;
    // Rien envoyé (fichier absent, 304, 416) : pas de ligne.
    if (res.statusCode !== 200 && res.statusCode !== 206) return;
    const bytes = Math.max(0, (socket?.bytesWritten ?? 0) - before);
    const length = Number(res.getHeader('content-length')) || 0;
    // Complet : réponse envoyée jusqu'au bout et allant jusqu'à la fin du fichier.
    const complete = finished && (!item.fileSize || start + length >= item.fileSize);
    db.prepare(`INSERT INTO download_logs (kind, item_id, system_id, label, file_size, bytes, range_start, complete, status,
                  user_id, device, platform, app_version, ip, user_agent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
      .run(item.kind, item.itemId ?? null, item.systemId ?? null, item.label ?? null, item.fileSize ?? null, bytes, start,
        complete ? 1 : 0, res.statusCode, auth?.user.id ?? null, client.device, client.platform, client.appVersion, client.ip, client.userAgent);
  };
  res.on('finish', () => log(true));
  res.on('close', () => log(res.writableFinished));
}

const rowToLog = (r) => ({
  id: r.id, at: r.at, kind: r.kind, itemId: r.item_id, systemId: r.system_id, label: r.label, fileSize: r.file_size,
  bytes: r.bytes, rangeStart: r.range_start, resumed: r.range_start > 0, complete: Boolean(r.complete), status: r.status,
  userId: r.user_id, username: r.username ?? null, device: r.device, platform: r.platform, appVersion: r.app_version,
  ip: r.ip, userAgent: r.user_agent, title: r.title ?? null,
});

/** Filtres communs : type, profil (« anonymous » : sans profil), appareil, depuis N jours. */
function where({ kind, userId, device, days } = {}) {
  const clauses = [];
  const params = [];
  if (DOWNLOAD_KINDS.includes(kind)) {
    clauses.push('l.kind = ?');
    params.push(kind);
  }
  if (userId === 'anonymous') clauses.push('l.user_id IS NULL');
  else if (userId != null && userId !== '') {
    clauses.push('l.user_id = ?');
    params.push(Number(userId));
  }
  if (device) {
    clauses.push('l.device IS ?');
    params.push(String(device));
  }
  if (Number(days) > 0) {
    clauses.push(`l.at >= datetime('now', ?)`);
    params.push(`-${Math.round(Number(days))} days`);
  }
  return { sql: clauses.length ? `WHERE ${clauses.join(' AND ')}` : '', params };
}

/** Dernières lignes du journal (plus récentes d'abord). */
export function listDownloads(filters = {}) {
  const { sql, params } = where(filters);
  const limit = Math.min(Number(filters.limit) || 300, 5000);
  return db.prepare(`SELECT l.*, u.username, g.title FROM download_logs l
                     LEFT JOIN users u ON u.id = l.user_id
                     LEFT JOIN games g ON l.kind = 'game' AND g.id = l.item_id
                     ${sql} ORDER BY l.id DESC LIMIT ?`).all(...params, limit).map(rowToLog);
}

/**
 * Statistiques : totaux, par type, par appareil, par profil (anonyme compris), jeux les plus
 * téléchargés et volume par jour. « downloads » : téléchargements commencés (hors reprises).
 */
export function downloadStats(filters = {}) {
  const { sql, params } = where(filters);
  const agg = `COUNT(*) AS requests, SUM(CASE WHEN l.range_start = 0 THEN 1 ELSE 0 END) AS downloads,
               SUM(l.complete) AS completed, COALESCE(SUM(l.bytes), 0) AS bytes, MAX(l.at) AS last_at`;
  const from = `FROM download_logs l LEFT JOIN users u ON u.id = l.user_id`;
  const shape = (r) => ({ requests: r.requests, downloads: r.downloads ?? 0, completed: r.completed ?? 0, bytes: r.bytes, lastAt: r.last_at });
  const andWhere = (extra) => (sql ? `${sql} AND ${extra}` : `WHERE ${extra}`);
  return {
    totals: shape(db.prepare(`SELECT ${agg} ${from} ${sql}`).get(...params)),
    byKind: db.prepare(`SELECT l.kind, ${agg} ${from} ${sql} GROUP BY l.kind ORDER BY bytes DESC`).all(...params)
      .map((r) => ({ kind: r.kind, ...shape(r) })),
    byDevice: db.prepare(`SELECT l.device, l.platform, ${agg} ${from} ${sql} GROUP BY l.device, l.platform ORDER BY bytes DESC LIMIT 100`).all(...params)
      .map((r) => ({ device: r.device, platform: r.platform, ...shape(r) })),
    byUser: db.prepare(`SELECT l.user_id, u.username, ${agg} ${from} ${sql} GROUP BY l.user_id ORDER BY bytes DESC LIMIT 100`).all(...params)
      .map((r) => ({ userId: r.user_id, username: r.user_id == null ? null : r.username ?? `#${r.user_id}`, ...shape(r) })),
    topItems: db.prepare(`SELECT l.kind, l.item_id, l.system_id, COALESCE(g.title, l.label) AS label, ${agg} ${from}
                          LEFT JOIN games g ON l.kind = 'game' AND g.id = l.item_id
                          ${andWhere('l.item_id IS NOT NULL')} GROUP BY l.kind, l.item_id ORDER BY downloads DESC, bytes DESC LIMIT 30`).all(...params)
      .map((r) => ({ kind: r.kind, itemId: r.item_id, systemId: r.system_id, label: r.label, ...shape(r) })),
    byDay: db.prepare(`SELECT date(l.at) AS day, ${agg} ${from} ${sql} GROUP BY date(l.at) ORDER BY day DESC LIMIT 90`).all(...params)
      .map((r) => ({ day: r.day, ...shape(r) })),
  };
}

export function clearDownloads() {
  db.prepare('DELETE FROM download_logs').run();
}
