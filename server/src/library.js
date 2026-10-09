import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { config } from './config.js';
import { db, transaction } from './db.js';
import { HttpError } from './http-error.js';
import { fileNameDetails, mergeDetails, parseStoredDetails } from './scraper/details.js';
import { stars } from './scraper/rating.js';
import { contentIdLabel } from './scraper/serial.js';
import { listSystems, requireSystem, systemDir } from './systems.js';

// Fichiers ignorés lors du scan : fichiers cachés, envois en cours, métadonnées diverses.
const IGNORED_RE = /^\.|\.(part|tmp|upload|txt|nfo|db|ini|xml|jpg|jpeg|png)$/i;

/** "Super Mario World (USA) [!].sfc" -> "Super Mario World" */
/** Indique si un nom de fichier est une ROM acceptée pour ce système. */
export function acceptsFileName(system, name) {
  if (IGNORED_RE.test(name)) return false;
  return !system.filenameRegex || new RegExp(system.filenameRegex).test(name);
}

export function titleFromFileName(fileName) {
  // Identifiant de contenu PS Vita / PSP (« EP0001-PCSB00040_00-ASPHALTINJECTION ») : son libellé.
  const label = contentIdLabel(fileName);
  if (label) return label;
  const base = fileName.replace(/\.[^.]+$/, '');
  const cleaned = base
    .replace(/\s*[([][^)\]]*[)\]]/g, '')
    .replace(/_/g, ' ')
    // Homebrew : auteur en fin de nom (« … by Mickey McMurray », prénom et nom).
    .replace(/\s+by\s+[A-Z][\w'.-]*(?:\s+[A-Z][\w'.-]*)+\s*$/, '')
    // Version (« v1.004 », « V2 ») en fin de titre ou avant l'article (« Gus and Rob V2, The »).
    .replace(/\s+v\d+(?:\.\d+)*(?=\s*(?:,|$))/i, '')
    .replace(/\s+/g, ' ')
    .trim()
    // Article rejeté à la fin, à la No-Intro : « Adventures of Gus and Rob, The » -> « The Adventures… ».
    .replace(/^(.+), (The|A|An)$/, '$2 $1');
  return cleaned || base;
}

export function rowToGame(row) {
  if (!row) return null;
  return {
    id: row.id,
    systemId: row.system_id,
    fileName: row.file_name,
    size: row.size,
    crc32: row.crc32,
    md5: row.md5,
    title: row.title,
    description: row.description,
    releaseDate: row.release_date,
    developer: row.developer,
    publisher: row.publisher,
    genre: row.genre,
    players: row.players,
    rating: row.rating,
    // Informations détaillées : nom de fichier (régions, langues, révision), puis scraping.
    details: mergeDetails(parseStoredDetails(row.details), fileNameDetails(row.file_name)),
    hasBoxart: Boolean(row.boxart),
    hasScreenshot: Boolean(row.screenshot),
    scrapeStatus: row.scrape_status,
    scrapeSource: row.scrape_source,
    scrapeError: row.scrape_error,
    scrapedAt: row.scraped_at,
    addedAt: row.added_at,
    updatedAt: row.updated_at,
  };
}

export function listGames(systemId, { q } = {}) {
  requireSystem(systemId);
  let sql = 'SELECT * FROM games WHERE system_id = ?';
  const params = [systemId];
  if (q) {
    sql += ' AND (title LIKE ? OR file_name LIKE ?)';
    params.push(`%${q}%`, `%${q}%`);
  }
  sql += ' ORDER BY title COLLATE NOCASE, file_name';
  return db.prepare(sql).all(...params).map(rowToGame);
}

/**
 * Recherche dans tous les systèmes : chaque mot doit apparaître dans le titre ou le nom de
 * fichier (insensible à la casse). Résultats triés par titre, limités à `limit`.
 */
/** Recherche ; `platform` : seulement dans les systèmes proposés à cette plateforme. */
export function searchGames(query, { limit = 300, platform } = {}) {
  const words = String(query || '').trim().split(/\s+/).filter(Boolean).slice(0, 8);
  if (!words.length) return [];
  // « % » et « _ » saisis sont cherchés tels quels (échappés avec « ! »).
  const like = (w) => `%${w.replace(/[!%_]/g, (c) => `!${c}`)}%`;
  let where = words.map(() => "(title LIKE ? ESCAPE '!' OR file_name LIKE ? ESCAPE '!')").join(' AND ');
  const params = words.flatMap((w) => [like(w), like(w)]);
  const systems = listSystems({ platform }).map((s) => s.id);
  where += ` AND system_id IN (${systems.map(() => '?').join(', ')})`;
  params.push(...systems);
  const max = Math.min(Math.max(Number(limit) || 300, 1), 1000);
  return db
    .prepare(`SELECT * FROM games WHERE ${where} ORDER BY title COLLATE NOCASE, file_name LIMIT ${max}`)
    .all(...params)
    .map(rowToGame);
}

export function getGameRow(id) {
  return db.prepare('SELECT * FROM games WHERE id = ?').get(Number(id));
}

export function requireGameRow(id) {
  const row = getGameRow(id);
  if (!row) throw new HttpError(404, 'errors.gameNotFound', { id });
  return row;
}

export function gameFilePath(row) {
  const system = requireSystem(row.system_id);
  return path.join(systemDir(system), row.file_name);
}

export function gameMediaDir(gameId) {
  return path.join(config.mediaDir, String(gameId));
}

const EDITABLE = {
  title: 'title',
  description: 'description',
  releaseDate: 'release_date',
  developer: 'developer',
  publisher: 'publisher',
  genre: 'genre',
  players: 'players',
  rating: 'rating',
};

export function updateGame(id, input) {
  requireGameRow(id);
  const sets = [];
  const values = [];
  for (const [key, column] of Object.entries(EDITABLE)) {
    if (input[key] === undefined) continue;
    let value = input[key] === '' ? null : input[key];
    if (key === 'rating' && value !== null) value = stars(value);
    if (key === 'title' && !value) throw new HttpError(400, 'errors.titleRequired');
    sets.push(`${column} = ?`);
    values.push(value);
  }
  if (sets.length) {
    db.prepare(`UPDATE games SET ${sets.join(', ')}, updated_at = datetime('now') WHERE id = ?`).run(...values, Number(id));
  }
  return rowToGame(getGameRow(id));
}

export function deleteGame(id, { deleteFile = true } = {}) {
  const row = requireGameRow(id);
  if (deleteFile) fs.rmSync(gameFilePath(row), { force: true });
  fs.rmSync(gameMediaDir(row.id), { recursive: true, force: true });
  db.prepare('DELETE FROM games WHERE id = ?').run(row.id);
}

/** Calcule CRC32 et MD5 d'un fichier en un seul passage (en flux). */
export async function hashFile(file) {
  const md5 = crypto.createHash('md5');
  let crc = 0;
  for await (const chunk of fs.createReadStream(file, { highWaterMark: 1 << 20 })) {
    md5.update(chunk);
    crc = zlib.crc32(chunk, crc);
  }
  return { crc32: (crc >>> 0).toString(16).padStart(8, '0'), md5: md5.digest('hex') };
}

/** Renvoie CRC/MD5 du jeu, en les calculant et en les mémorisant si nécessaire. */
export async function ensureHashes(row) {
  if (row.crc32 && row.md5) return row;
  if (row.size > config.hashMaxMb * 1024 * 1024) return row;
  const { crc32, md5 } = await hashFile(gameFilePath(row));
  db.prepare('UPDATE games SET crc32 = ?, md5 = ? WHERE id = ?').run(crc32, md5, row.id);
  return { ...row, crc32, md5 };
}

/** Synchronise la base avec le contenu du dossier d'un système. */
export function scanSystem(systemId) {
  const system = requireSystem(systemId);
  const dir = systemDir(system);
  fs.mkdirSync(dir, { recursive: true });
  const files = new Map();
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (!entry.isFile() || !acceptsFileName(system, entry.name)) continue;
    const stat = fs.statSync(path.join(dir, entry.name));
    files.set(entry.name, { size: stat.size, mtime: Math.floor(stat.mtimeMs) });
  }

  const existing = new Map(
    db.prepare('SELECT id, file_name, size, mtime FROM games WHERE system_id = ?').all(systemId).map((r) => [r.file_name, r]),
  );
  const result = { added: [], updated: 0, removed: 0 };

  transaction(() => {
    const insert = db.prepare('INSERT INTO games (system_id, file_name, size, mtime, title) VALUES (?, ?, ?, ?, ?)');
    const update = db.prepare(
      "UPDATE games SET size = ?, mtime = ?, crc32 = NULL, md5 = NULL, updated_at = datetime('now') WHERE id = ?",
    );
    for (const [name, info] of files) {
      const row = existing.get(name);
      if (!row) {
        const r = insert.run(systemId, name, info.size, info.mtime, titleFromFileName(name));
        result.added.push(Number(r.lastInsertRowid));
      } else if (row.size !== info.size || row.mtime !== info.mtime) {
        update.run(info.size, info.mtime, row.id);
        result.updated++;
      }
    }
    const del = db.prepare('DELETE FROM games WHERE id = ?');
    for (const [name, row] of existing) {
      if (files.has(name)) continue;
      del.run(row.id);
      fs.rmSync(gameMediaDir(row.id), { recursive: true, force: true });
      result.removed++;
    }
  });
  return result;
}

export function scanAll() {
  const summary = {};
  for (const s of listSystems()) summary[s.id] = scanSystem(s.id);
  return summary;
}

/** Nom de fichier sûr (pas de chemin, pas de caractères interdits sous Windows/Android). */
export function safeFileName(name) {
  const base = path.basename(String(name)).replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').trim();
  if (!base || base === '.' || base === '..') throw new HttpError(400, 'errors.invalidFileName');
  return base;
}
