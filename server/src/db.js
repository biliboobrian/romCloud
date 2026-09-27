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
    added_at   TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (system_id, path)
  );
`);

// Migrations légères pour les bases créées par une version antérieure.
const systemColumns = db.prepare('PRAGMA table_info(systems)').all().map((c) => c.name);
if (!systemColumns.includes('image')) db.exec('ALTER TABLE systems ADD COLUMN image TEXT');

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
