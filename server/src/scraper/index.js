import fs from 'node:fs';
import path from 'node:path';
import { config, screenscraperEnabled } from '../config.js';
import { I18nError } from '../i18n.js';
import { db } from '../db.js';
import { ensureHashes, gameFilePath, gameMediaDir, getGameRow, requireGameRow, rowToGame } from '../library.js';
import { requireSystem } from '../systems.js';
import { mergeDetails, parseStoredDetails } from './details.js';
import { libretroMetadata, scrapeLibretro, titleFromLibretroName } from './libretro.js';
import { zipEntries, zipMainEntry } from './rom-identity.js';
import { findArcadeGame, isArcadeSystem } from './arcade.js';
import { scrapeScreenScraper } from './screenscraper.js';
import { scrapeWikipedia, wikidataFacts } from './wikipedia.js';

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
  // Jeu d'arcade renommé (« fatal fury.zip ») : retrouvé par les CRC des fichiers de l'archive,
  // puis cherché sous son nom court (« fatfury1.zip »).
  let arcade = null;
  if (isArcadeSystem(system) && /\.zip$/i.test(row.file_name)) {
    try {
      arcade = await findArcadeGame(zipEntries(gameFilePath(row)));
    } catch (err) {
      errors.push(err.message);
    }
  }
  const lookupName = arcade ? `${arcade.name}.zip` : row.file_name;
  // Autre jeu zippé : les bases connaissent la ROM de l'archive (nom, taille, CRC), pas le .zip.
  const inner = arcade ? null : zipMainEntry(gameFilePath(row));

  if (useSS) {
    try {
      row = await ensureHashes(row);
      if (arcade && lookupName !== row.file_name) meta = await scrapeScreenScraper({ system, fileName: lookupName, size: row.size });
      if (inner) meta ||= await scrapeScreenScraper({ system, fileName: inner.name, size: inner.size, crc32: inner.crc32 });
      meta ||= await scrapeScreenScraper({
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
      row = await ensureHashes(row);
      const lr = await scrapeLibretro({ system, fileName: lookupName, crc32: arcade ? null : inner?.crc32 || row.crc32 });
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

  // Fiches libretro-database (CRC, nom ou numéro de série) : complètent développeur, éditeur,
  // genre, date, joueurs et les informations détaillées (série, ESRB, vibrations…).
  if (useLibretro && system.libretroName && source !== 'screenscraper') {
    try {
      row = await ensureHashes(row);
      const lm = await libretroMetadata({ system, fileName: lookupName, crc32: arcade ? null : inner?.crc32 || row.crc32 });
      if (lm) {
        meta ||= { media: {} }; // jeu sans image chez libretro, mais présent dans ses fiches
        meta.title ||= lm.title;
        for (const key of ['developer', 'publisher', 'genre', 'releaseDate', 'players']) meta[key] ||= lm[key];
        meta.details = mergeDetails(meta.details, lm.details);
        if (!usedSources.includes('libretro')) usedSources.push('libretro');
      }
    } catch (err) {
      errors.push(err.message);
    }
  }

  // Jeu d'arcade identifié : titre, année et fabricant de la DAT FinalBurn Neo si rien de mieux.
  if (arcade) {
    meta ||= { media: {} };
    meta.title ||= titleFromLibretroName(arcade.description.split(' / ')[0]);
    if (/^\d{4}$/.test(arcade.year || '')) meta.releaseDate ||= arcade.year;
    meta.publisher ||= arcade.manufacturer;
    meta.details = mergeDetails(meta.details, { arcadeSet: arcade.name, arcadeParent: arcade.cloneOf });
    if (!usedSources.includes('fbneo')) usedSources.push('fbneo');
  }

  // Wikipedia : résumé manquant et lien vers l'article. Jeu introuvable ailleurs (fichier mal
  // nommé) : l'article l'identifie (titre officiel, image, Wikidata), et libretro est réinterrogé
  // avec le titre officiel pour les images.
  if (source !== 'screenscraper') {
    try {
      const unidentified = !meta;
      const wiki = await scrapeWikipedia({
        title: meta?.title || row.title,
        system: system.name,
        languages: config.screenscraper.languages,
        loose: unidentified,
      });
      if (wiki) {
        meta ||= { media: {} };
        if (!meta.description && !row.description) meta.description = wiki.text;
        meta.details = mergeDetails(meta.details, { links: [{ label: 'Wikipedia', url: wiki.url }] });
        if (unidentified) {
          meta.title = wiki.title;
          const facts = await wikidataFacts(wiki.wikidataId, config.screenscraper.languages).catch(() => null);
          if (facts) {
            for (const key of ['developer', 'publisher', 'genre', 'releaseDate']) meta[key] ||= facts[key];
            meta.details = mergeDetails(meta.details, { modes: facts.modes });
          }
          if (useLibretro && system.libretroName) {
            const lr = await scrapeLibretro({ system, fileName: `${wiki.title}.x`, crc32: null }).catch(() => null);
            if (lr) {
              meta.media.boxart ||= lr.media.boxart;
              meta.media.screenshot ||= lr.media.screenshot;
              if (!usedSources.includes('libretro')) usedSources.push('libretro');
            }
          }
          // Image de l'article (souvent la jaquette) à défaut d'autre.
          meta.media.boxart ||= wiki.image;
        }
        if (!usedSources.includes('wikipedia')) usedSources.push('wikipedia');
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
       details = ?,
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
    // Nouvelles informations d'abord, complétées par celles d'un scraping précédent.
    JSON.stringify(mergeDetails(meta.details, parseStoredDetails(row.details))),
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
