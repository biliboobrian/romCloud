import fs from 'node:fs';
import path from 'node:path';
import { screenscraperEnabled } from '../config.js';
import { I18nError } from '../i18n.js';
import { db } from '../db.js';
import { ensureHashes, gameMediaDir, getGameRow, requireGameRow, rowToGame } from '../library.js';
import { requireSystem } from '../systems.js';
import { scrapeLibretro } from './libretro.js';
import { scrapeScreenScraper } from './screenscraper.js';

export { QuotaError } from './screenscraper.js';

export const SCRAPE_SOURCES = ['auto', 'screenscraper', 'libretro'];

const EXT_BY_TYPE = { 'image/png': '.png', 'image/jpeg': '.jpg', 'image/webp': '.webp', 'image/gif': '.gif' };

async function downloadMedia(url, dir, baseName) {
  const res = await fetch(url);
  if (!res.ok) return null;
  const type = (res.headers.get('content-type') || '').split(';')[0].trim();
  if (!type.startsWith('image/')) return null;
  const buffer = Buffer.from(await res.arrayBuffer());
  fs.mkdirSync(dir, { recursive: true });
  for (const old of fs.readdirSync(dir)) if (old.startsWith(`${baseName}.`)) fs.rmSync(path.join(dir, old));
  const file = `${baseName}${EXT_BY_TYPE[type] || '.png'}`;
  fs.writeFileSync(path.join(dir, file), buffer);
  return file;
}

function markStatus(id, status, source, error) {
  db.prepare(
    "UPDATE games SET scrape_status = ?, scrape_source = ?, scrape_error = ?, scraped_at = datetime('now') WHERE id = ?",
  ).run(status, source, error, id);
}

/**
 * Scrape un jeu et enregistre ses métadonnées + médias.
 * source = 'auto' : ScreenScraper (si configuré) puis repli sur Libretro pour les images manquantes.
 */
export async function scrapeGame(gameId, source = 'auto') {
  if (!SCRAPE_SOURCES.includes(source)) throw new I18nError('errors.unknownSource', { source });
  let row = requireGameRow(gameId);
  const system = requireSystem(row.system_id);
  const useSS = source === 'screenscraper' || (source === 'auto' && screenscraperEnabled());
  const useLibretro = source === 'libretro' || (source === 'auto' && system.libretroName);

  let meta = null;
  let usedSources = [];
  const errors = [];

  if (useSS) {
    try {
      row = await ensureHashes(row);
      meta = await scrapeScreenScraper({
        system,
        fileName: row.file_name,
        size: row.size,
        crc32: row.crc32,
        md5: row.md5,
      });
      if (meta) usedSources.push('screenscraper');
    } catch (err) {
      if (source === 'screenscraper' || err.name === 'QuotaError') {
        markStatus(row.id, 'error', 'screenscraper', err.message);
        throw err;
      }
      errors.push(err.message);
    }
  }

  if (useLibretro && (!meta || !meta.media.boxart || !meta.media.screenshot)) {
    try {
      const lr = await scrapeLibretro({ system, fileName: row.file_name });
      if (lr) {
        meta = meta || { media: {} };
        meta.title ||= lr.title;
        meta.media.boxart ||= lr.media.boxart;
        meta.media.screenshot ||= lr.media.screenshot;
        usedSources.push('libretro');
      }
    } catch (err) {
      errors.push(err.message);
    }
  }

  if (!meta) {
    const status = errors.length ? 'error' : 'notfound';
    markStatus(row.id, status, usedSources.join('+') || source, errors[0] || null);
    return rowToGame(getGameRow(row.id));
  }

  const dir = gameMediaDir(row.id);
  const boxart = meta.media.boxart ? await downloadMedia(meta.media.boxart, dir, 'boxart') : null;
  const screenshot = meta.media.screenshot ? await downloadMedia(meta.media.screenshot, dir, 'screenshot') : null;

  db.prepare(
    `UPDATE games SET
       title = COALESCE(?, title),
       description = COALESCE(?, description),
       release_date = COALESCE(?, release_date),
       developer = COALESCE(?, developer),
       publisher = COALESCE(?, publisher),
       genre = COALESCE(?, genre),
       players = COALESCE(?, players),
       rating = COALESCE(?, rating),
       boxart = COALESCE(?, boxart),
       screenshot = COALESCE(?, screenshot),
       updated_at = datetime('now')
     WHERE id = ?`,
  ).run(
    meta.title ?? null,
    meta.description ?? null,
    meta.releaseDate ?? null,
    meta.developer ?? null,
    meta.publisher ?? null,
    meta.genre ?? null,
    meta.players ?? null,
    meta.rating ?? null,
    boxart,
    screenshot,
    row.id,
  );
  markStatus(row.id, 'ok', usedSources.join('+'), errors[0] || null);
  return rowToGame(getGameRow(row.id));
}

/** Enregistre une image fournie manuellement (upload depuis l'interface web). */
export function saveCustomMedia(gameId, type, buffer, mimeType) {
  const row = requireGameRow(gameId);
  const ext = EXT_BY_TYPE[mimeType];
  if (!ext) throw new I18nError('errors.imageFormat');
  const dir = gameMediaDir(row.id);
  fs.mkdirSync(dir, { recursive: true });
  for (const old of fs.readdirSync(dir)) if (old.startsWith(`${type}.`)) fs.rmSync(path.join(dir, old));
  fs.writeFileSync(path.join(dir, `${type}${ext}`), buffer);
  db.prepare(`UPDATE games SET ${type} = ?, updated_at = datetime('now') WHERE id = ?`).run(`${type}${ext}`, row.id);
  return rowToGame(getGameRow(row.id));
}
