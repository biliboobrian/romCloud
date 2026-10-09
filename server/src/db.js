import { DatabaseSync } from 'node:sqlite';
import { config } from './config.js';

export const db = new DatabaseSync(config.dbFile);

db.exec(`
  PRAGMA journal_mode = WAL;
  PRAGMA foreign_keys = ON;

  CREATE TABLE IF NOT EXISTS systems (
    id               TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    shortname        TEXT NOT NULL,
    folder           TEXT NOT NULL UNIQUE,
    filename_regex   TEXT,
    libretro_name    TEXT,
    screenscraper_id INTEGER,
    players          TEXT NOT NULL DEFAULT '[]',
    platforms        TEXT NOT NULL DEFAULT '[]',
    source           TEXT NOT NULL DEFAULT 'custom',
    source_revision  INTEGER,
    created_at       TEXT NOT NULL DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS games (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    system_id      TEXT NOT NULL REFERENCES systems(id) ON DELETE CASCADE,
    file_name      TEXT NOT NULL,
    size           INTEGER NOT NULL,
    mtime          INTEGER NOT NULL,
    crc32          TEXT,
    md5            TEXT,
    title          TEXT NOT NULL,
    description    TEXT,
    release_date   TEXT,
    developer      TEXT,
    publisher      TEXT,
    genre          TEXT,
    players        TEXT,
    rating         REAL,
    boxart         TEXT,
    screenshot     TEXT,
    details        TEXT,
    scrape_status  TEXT NOT NULL DEFAULT 'none',
    scrape_source  TEXT,
    scrape_error   TEXT,
    scraped_at     TEXT,
    added_at       TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at     TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (system_id, file_name)
  );

  CREATE INDEX IF NOT EXISTS games_system ON games(system_id);

  -- BIOS d'un système, stockés dans data/bios/<id du système>/<path>.
  CREATE TABLE IF NOT EXISTS bios (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    system_id  TEXT NOT NULL REFERENCES systems(id) ON DELETE CASCADE,
    path       TEXT NOT NULL,
    size       INTEGER NOT NULL,
    md5        TEXT,
    sha1       TEXT,
    added_at   TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (system_id, path)
  );

  -- APK des émulateurs Android, stockés dans data/apks/<paquet>.apk (un par paquet).
  CREATE TABLE IF NOT EXISTS apks (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    package_name  TEXT NOT NULL UNIQUE,
    label         TEXT NOT NULL,
    version_name  TEXT,
    version_code  INTEGER,
    size          INTEGER NOT NULL,
    md5           TEXT,
    file_name     TEXT,
    added_at      TEXT NOT NULL DEFAULT (datetime('now'))
  );
`);

// Profils des joueurs : comptes, sessions par appareil, journal des connexions, temps de jeu,
// sauvegardes (fichiers dans data/saves/<utilisateur>/<jeu>/) et erreurs signalées par les applications.
db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    username       TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash  TEXT NOT NULL,
    disabled       INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT NOT NULL DEFAULT (datetime('now')),
    last_login_at  TEXT
  );

  CREATE TABLE IF NOT EXISTS sessions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash    TEXT NOT NULL UNIQUE,
    device        TEXT,
    platform      TEXT,
    app_version   TEXT,
    ip            TEXT,
    method        TEXT NOT NULL DEFAULT 'password',
    created_at    TEXT NOT NULL DEFAULT (datetime('now')),
    last_seen_at  TEXT NOT NULL DEFAULT (datetime('now'))
  );
  CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);

  CREATE TABLE IF NOT EXISTS login_events (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER REFERENCES users(id) ON DELETE SET NULL,
    username    TEXT,
    event       TEXT NOT NULL,
    success     INTEGER NOT NULL,
    reason      TEXT,
    device      TEXT,
    platform    TEXT,
    app_version TEXT,
    ip          TEXT,
    user_agent  TEXT,
    at          TEXT NOT NULL DEFAULT (datetime('now'))
  );
  CREATE INDEX IF NOT EXISTS login_events_user ON login_events(user_id);

  CREATE TABLE IF NOT EXISTS playtime (
    user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    game_id         INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    seconds         INTEGER NOT NULL DEFAULT 0,
    sessions        INTEGER NOT NULL DEFAULT 0,
    first_played_at TEXT NOT NULL DEFAULT (datetime('now')),
    last_played_at  TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, game_id)
  );

  CREATE TABLE IF NOT EXISTS saves (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    game_id     INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    core        TEXT NOT NULL,
    kind        TEXT NOT NULL,
    size        INTEGER NOT NULL,
    md5         TEXT NOT NULL,
    saved_at    INTEGER NOT NULL,
    device      TEXT,
    platform    TEXT,
    uploaded_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (user_id, game_id, core, kind)
  );

  CREATE TABLE IF NOT EXISTS error_logs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER REFERENCES users(id) ON DELETE SET NULL,
    session_id  INTEGER REFERENCES sessions(id) ON DELETE SET NULL,
    platform    TEXT,
    device      TEXT,
    app_version TEXT,
    context     TEXT,
    message     TEXT NOT NULL,
    details     TEXT,
    ip          TEXT,
    at          TEXT NOT NULL DEFAULT (datetime('now'))
  );
  CREATE INDEX IF NOT EXISTS error_logs_user ON error_logs(user_id);
`);

// Migrations légères pour les bases créées par une version antérieure.
const systemColumns = db.prepare('PRAGMA table_info(systems)').all().map((c) => c.name);
if (!systemColumns.includes('image')) db.exec('ALTER TABLE systems ADD COLUMN image TEXT');
if (!systemColumns.includes('platforms')) db.exec("ALTER TABLE systems ADD COLUMN platforms TEXT NOT NULL DEFAULT '[]'");
const gameColumns = db.prepare('PRAGMA table_info(games)').all().map((c) => c.name);
if (!gameColumns.includes('details')) db.exec('ALTER TABLE games ADD COLUMN details TEXT');
const biosColumns = db.prepare('PRAGMA table_info(bios)').all().map((c) => c.name);
if (!biosColumns.includes('sha1')) db.exec('ALTER TABLE bios ADD COLUMN sha1 TEXT');

/** Exécute fn dans une transaction. */
export function transaction(fn) {
  db.exec('BEGIN');
  try {
    const result = fn();
    db.exec('COMMIT');
    return result;
  } catch (err) {
    db.exec('ROLLBACK');
    throw err;
  }
}
