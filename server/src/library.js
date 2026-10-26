import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { config } from './config.js';
import { db, transaction } from './db.js';
import { HttpError } from './http-error.js';
import { compareParts, groupSystem } from './groups.js';
import { queuePs4Conversions } from './ps4-convert.js';
import { fileNameDetails, mergeDetails, parseStoredDetails } from './scraper/details.js';
import { stars } from './scraper/rating.js';
import { contentIdLabel } from './scraper/serial.js';
import { exeCandidates, folderFiles, folderStats, prereqsOf, uninstallerOf } from './folder-games.js';
import { listSystems, requireSystem, systemDir } from './systems.js';

// Fichiers ignorés lors du scan : fichiers cachés, envois en cours, métadonnées diverses.
const IGNORED_RE = /^\.|\.(part|tmp|upload|txt|nfo|db|ini|xml|jpg|jpeg|png)$/i;

/** "Super Mario World (USA) [!].sfc" -> "Super Mario World" */
/** Indique si un nom de fichier est une ROM acceptée pour ce système. */
export function acceptsFileName(system, name) {
  if (IGNORED_RE.test(name)) return false;
  return !system.filenameRegex || new RegExp(system.filenameRegex).test(name);
}

/**
 * Groupe de release en fin de nom d'un dossier de jeu PC : groupes connus (« -GOG », « -ElAmigos »),
 * ou mot en capitales après un nom écrit à la façon des releases (mots séparés par des points ou
 * des « _ » : « Rock.Bottom-TENOKE ») ; « Half-Life », « X-COM » restent tels quels.
 */
const RELEASE_GROUP_RE = new RegExp(
  [
    String.raw`-(?:GOG|ElAmigos|DARKSiDERS|TiNYiSO|SiMPLEX|DINOByTES|FitGirl|DODI|KaOs|P2P|Razor1911|RELOADED|SKIDROW|CODEX|PLAZA|EMPRESS|FLT|RUNE|TENOKE|DOGE|HOODLUM|PROPHET|CPY|GOLDBERG|I_KnoW)$`,
    String.raw`(?<=^[^\s]{4,}[._][^\s]*)-[A-Z0-9]*[A-Z][A-Z0-9]{2,}$`,
  ].join('|'),
);

export function titleFromFileName(fileName, { folder = false } = {}) {
  // Identifiant de contenu PS Vita / PSP (« EP0001-PCSB00040_00-ASPHALTINJECTION ») : son libellé.
  const label = contentIdLabel(fileName);
  if (label) return label;
  // Dossier d'un jeu PC (« Mr. Robot ») : pas d'extension à retirer ; nom de release
  // (« Rock.Bottom-TENOKE », « Jeu_v1.2-GOG ») : groupe de release retiré.
  const base = folder ? fileName.replace(RELEASE_GROUP_RE, '') : fileName.replace(/\.[^.]+$/, '');
  const bare = base.replace(/\s*[([][^)\]]*[)\]]/g, '');
  const cleaned = bare
    // Nom à la façon des releases, sans espace (« Minecraft.Dungeons.II ») : points entre les mots.
    .replace(/(?<=[A-Za-z]{2})\.(?=[A-Za-z0-9])/g, /\s/.test(bare) ? '.' : ' ')
    .replace(/_/g, ' ')
    // Groupe de release Switch en fin de nom (« SuperXCI-NX », « SuperXCi - CLC »).
    .replace(/\s+\S*(?:xci|xcz|nsp|nsz)\S*(?:\s+-\s+\S+)?\s*$/i, '')
    // Homebrew : auteur en fin de nom (« … by Mickey McMurray », prénom et nom).
    .replace(/\s+by\s+[A-Z][\w'.-]*(?:\s+[A-Z][\w'.-]*)+\s*$/, '')
    // Nom de release (Switch : « Titre v1.1.0 Eur SuperXCi - CLC », « Titre v1.0.2- CLC ») : tout ce
    // qui suit la version.
    .replace(/\s+v\d+(?:\.\d+)+[\s-]+\S.*$/i, '')
    // Version (« v1.004 », « V2 ») en fin de titre ou avant l'article (« Gus and Rob V2, The »).
    .replace(/\s+v\d+(?:\.\d+)*(?=\s*(?:,|$))/i, '')
    .replace(/\s+/g, ' ')
    .trim()
    // Article rejeté à la fin, à la No-Intro : « Adventures of Gus and Rob, The » -> « The Adventures… ».
    .replace(/^(.+), (The|A|An)$/, '$2 $1');
  return cleaned || base;
}

/** Partie d'un jeu (disque, mise à jour, DLC) telle que l'envoie l'API. */
function rowToPart(row) {
  return { id: row.id, fileName: row.file_name, size: row.size, kind: row.part_kind, index: row.part_index, ...(row.rap ? { rap: row.rap } : {}) };
}

/** Parties des jeux [parentIds], par jeu, dans l'ordre (disques, mises à jour, DLC). */
export function partsOf(parentIds) {
  const parts = new Map();
  const ids = [...new Set(parentIds)];
  for (let i = 0; i < ids.length; i += 500) {
    const chunk = ids.slice(i, i + 500);
    const rows = db.prepare(`SELECT * FROM games WHERE parent_id IN (${chunk.map(() => '?').join(', ')})`).all(...chunk);
    for (const row of rows) parts.set(row.parent_id, [...(parts.get(row.parent_id) || []), rowToPart(row)]);
  }
  for (const list of parts.values()) list.sort(compareParts);
  return parts;
}

/**
 * Fiche d'un jeu. [parts] : ses disques, mises à jour et DLC (téléchargés avec lui) ; totalSize :
 * taille du jeu et de ses parties.
 */
export function rowToGame(row, parts = []) {
  if (!row) return null;
  return {
    id: row.id,
    systemId: row.system_id,
    fileName: row.file_name,
    size: row.size,
    parts,
    totalSize: row.size + parts.reduce((n, p) => n + p.size, 0),
    parentId: row.parent_id ?? null,
    partKind: row.part_kind ?? null,
    // Jeu en dossier (PC Windows) : fichiers listés par /games/:id/files, lancé par [exe].
    // uninstallExe : désinstalleur lancé quand le jeu est supprimé du PC (null : aucun).
    // extraExes : autres exécutables (prérequis : DirectX, Visual C++…) proposés par l'application Windows.
    // packed : « zip » (tous les fichiers compressés, un .zip par fichier), « partial » (une partie)
    // ou « flat » (en clair) ; packedSize : taille à télécharger (size : taille une fois installé).
    ...(row.is_folder
      ? {
          folder: true,
          exe: row.exe ?? null,
          uninstallExe: row.uninstall_exe || null,
          extraExes: extraExesOf(row),
          packed: packState(row),
          packedSize: row.packed_size ?? row.size,
          // Format des archives : « xz », « zip » (compressions d'avant le xz), « mixed » ; null : aucune.
          packFormat: row.packed_files ? row.pack_format || 'zip' : null,
          // AppID Steam choisi dans la fiche (null : retrouvé par les fichiers du jeu ou son titre).
          steamAppId: row.steam_app_id ?? null,
        }
      : {}),
    groupMode: row.group_mode ?? null,
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
    zrif: row.zrif ?? null,
    rap: row.rap ?? null,
    // Informations détaillées : nom de fichier (régions, langues, révision), puis scraping.
    details: mergeDetails(parseStoredDetails(row.details), fileNameDetails(row.file_name)),
    hasBoxart: Boolean(row.boxart),
    hasScreenshot: Boolean(row.screenshot),
    // Captures d'écran (1 à 4) : médias « screenshot », « screenshot2 »… sans trou.
    screenshotCount: SCREENSHOT_TYPES.filter((type) => row[type]).length,
    scrapeStatus: row.scrape_status,
    scrapeSource: row.scrape_source,
    scrapeError: row.scrape_error,
    scrapedAt: row.scraped_at,
    addedAt: row.added_at,
    updatedAt: row.updated_at,
  };
}

/** Fiches des jeux [rows], avec leurs parties. */
export function rowsToGames(rows) {
  const parts = partsOf(rows.map((r) => r.id));
  return rows.map((r) => rowToGame(r, parts.get(r.id)));
}

/** Fiche d'un jeu par son identifiant, avec ses parties. */
export function gameWithParts(id) {
  return rowsToGames([requireGameRow(id)])[0];
}

/** Jeux d'un système (les disques, mises à jour et DLC sont dans les parties de leur jeu). */
export function listGames(systemId, { q } = {}) {
  requireSystem(systemId);
  let sql = 'SELECT * FROM games WHERE system_id = ? AND parent_id IS NULL';
  const params = [systemId];
  if (q) {
    sql += ' AND (title LIKE ? OR file_name LIKE ?)';
    params.push(`%${q}%`, `%${q}%`);
  }
  sql += ' ORDER BY title COLLATE NOCASE, file_name';
  return rowsToGames(db.prepare(sql).all(...params));
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
  let where = `parent_id IS NULL AND ${words.map(() => "(title LIKE ? ESCAPE '!' OR file_name LIKE ? ESCAPE '!')").join(' AND ')}`;
  const params = words.flatMap((w) => [like(w), like(w)]);
  const systems = listSystems({ platform }).map((s) => s.id);
  where += ` AND system_id IN (${systems.map(() => '?').join(', ')})`;
  params.push(...systems);
  const max = Math.min(Math.max(Number(limit) || 300, 1), 1000);
  return rowsToGames(
    db.prepare(`SELECT * FROM games WHERE ${where} ORDER BY title COLLATE NOCASE, file_name LIMIT ${max}`).all(...params),
  );
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
  zrif: 'zrif',
  rap: 'rap',
  exe: 'exe',
  uninstallExe: 'uninstall_exe',
  steamAppId: 'steam_app_id',
};

/** Licence zRIF (base64, « KO5ifR1dQ+eHBl… ») sans espaces ni retours à la ligne ; null si vide. */
export function normalizeZrif(input) {
  const zrif = String(input ?? '').replace(/\s+/g, '');
  if (!zrif) return null;
  if (!/^[A-Za-z0-9+/]+=*$/.test(zrif) || zrif.length < 16) throw new HttpError(400, 'errors.invalidZrif');
  return zrif;
}

/** Licence RAP d'un jeu PS3 (16 octets en hexadécimal, « 1763B247… ») en majuscules ; null si vide. */
export function normalizeRap(input) {
  const rap = String(input ?? '').replace(/\s+/g, '').toUpperCase();
  if (!rap) return null;
  if (!/^[0-9A-F]{32}$/.test(rap)) throw new HttpError(400, 'errors.invalidRap');
  return rap;
}

export function updateGame(id, input) {
  requireGameRow(id);
  const sets = [];
  const values = [];
  for (const [key, column] of Object.entries(EDITABLE)) {
    if (input[key] === undefined) continue;
    let value = input[key] === '' ? null : input[key];
    if (key === 'rating' && value !== null) value = stars(value);
    if (key === 'zrif') value = normalizeZrif(value);
    if (key === 'rap') value = normalizeRap(value);
    // AppID Steam : nombre, ou adresse de la boutique (« store.steampowered.com/app/4297840/… »).
    if (key === 'steamAppId' && value !== null) {
      value = /(?:^|\/app\/)(\d{1,10})(?:\/|$|\?)/.exec(String(value).trim())?.[1] ?? null;
      if (!value) throw new HttpError(400, 'errors.invalidSteamAppId');
    }
    if (key === 'exe') value = validateExe(id, value);
    // Désinstalleur : vide = aucun, gardé tel quel (pas deviné de nouveau au scan).
    if (key === 'uninstallExe') value = input[key] === '' || input[key] === null ? '' : validateExe(id, value);
    if (key === 'title' && !value) throw new HttpError(400, 'errors.titleRequired');
    sets.push(`${column} = ?`);
    values.push(value);
  }
  // Autres exécutables choisis dans la fiche (liste, vide : aucun) : gardés au scan ; null : devinés de nouveau.
  if (input.extraExes !== undefined) {
    const row = requireGameRow(id);
    if (!row.is_folder) throw new HttpError(400, 'errors.notFolderGame');
    const { exes } = gameFolderFiles(row);
    const chosen = input.extraExes !== null;
    const list = chosen ? [...new Set([].concat(input.extraExes).map((e) => String(e).replace(/\\/g, '/')))] : prereqsOf(folderFiles(gameFilePath(row)));
    for (const exe of list) if (!exes.includes(exe)) throw new HttpError(400, 'errors.unknownExe', { exe });
    sets.push('extra_exes = ?', 'extra_exes_chosen = ?');
    values.push(JSON.stringify(list), chosen ? 1 : 0);
  }
  if (sets.length) {
    db.prepare(`UPDATE games SET ${sets.join(', ')}, updated_at = datetime('now') WHERE id = ?`).run(...values, Number(id));
  }
  return rowToGame(getGameRow(id));
}

/** Médias des captures d'écran d'un jeu, dans l'ordre (colonnes et noms de fichier sans extension). */
export const SCREENSHOT_TYPES = ['screenshot', 'screenshot2', 'screenshot3', 'screenshot4'];

/**
 * Captures du jeu ramenées aux premières places après une suppression (« screenshot3 » devient
 * « screenshot2 » si celle-ci manque) : fichiers renommés, colonnes mises à jour.
 */
export function compactScreenshots(id) {
  const row = requireGameRow(id);
  const dir = gameMediaDir(row.id);
  const files = SCREENSHOT_TYPES.map((type) => row[type]).filter(Boolean);
  const values = SCREENSHOT_TYPES.map((type, i) => {
    const file = files[i];
    if (!file) return null;
    const target = `${type}${path.extname(file)}`;
    if (file !== target) {
      for (const old of fs.existsSync(dir) ? fs.readdirSync(dir) : []) if (old.startsWith(`${type}.`)) fs.rmSync(path.join(dir, old));
      fs.renameSync(path.join(dir, file), path.join(dir, target));
    }
    return target;
  });
  db.prepare(`UPDATE games SET ${SCREENSHOT_TYPES.map((t) => `${t} = ?`).join(', ')} WHERE id = ?`).run(...values, row.id);
}

/** Supprime une image du jeu (jaquette ou capture d'écran, les suivantes remontant d'une place). */
export function deleteGameMedia(id, type) {
  const row = requireGameRow(id);
  if (type !== 'boxart' && !SCREENSHOT_TYPES.includes(type)) throw new HttpError(404, 'errors.unknownMediaType');
  if (row[type]) fs.rmSync(path.join(gameMediaDir(row.id), row[type]), { force: true });
  db.prepare(`UPDATE games SET ${type} = NULL, updated_at = datetime('now') WHERE id = ?`).run(row.id);
  if (type !== 'boxart') compactScreenshots(row.id);
  return gameWithParts(row.id);
}

/** Supprime le jeu (fichier, fiche, médias) et ses parties (disques, mises à jour, DLC). */
export function deleteGame(id, { deleteFile = true } = {}) {
  const row = requireGameRow(id);
  for (const part of db.prepare('SELECT id FROM games WHERE parent_id = ?').all(row.id)) deleteGame(part.id, { deleteFile });
  if (deleteFile) fs.rmSync(gameFilePath(row), { force: true, recursive: Boolean(row.is_folder) });
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
  // Jeu en dossier : pas d'empreinte (plusieurs fichiers).
  if (row.is_folder) return row;
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
  const folders = system.folderGames;
  const files = new Map();
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (folders) {
      // Jeux en dossiers : un sous-dossier par jeu (dossiers cachés et vides ignorés).
      if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
      const stats = folderStats(path.join(dir, entry.name));
      if (stats.count) files.set(entry.name, { size: stats.size, mtime: stats.mtime });
      continue;
    }
    if (!entry.isFile() || !acceptsFileName(system, entry.name)) continue;
    const stat = fs.statSync(path.join(dir, entry.name));
    files.set(entry.name, { size: stat.size, mtime: Math.floor(stat.mtimeMs) });
  }

  const existing = new Map(
    db
      .prepare('SELECT id, file_name, size, mtime, is_folder FROM games WHERE system_id = ?')
      .all(systemId)
      // Mode du système changé (fichiers <-> dossiers) : anciennes fiches retirées, nouvelles créées.
      .filter((r) => Boolean(r.is_folder) === folders)
      .map((r) => [r.file_name, r]),
  );
  const result = { added: [], updated: 0, removed: 0 };

  transaction(() => {
    const insert = db.prepare('INSERT INTO games (system_id, file_name, size, mtime, title, is_folder) VALUES (?, ?, ?, ?, ?, ?)');
    const update = db.prepare(
      "UPDATE games SET size = ?, mtime = ?, crc32 = NULL, md5 = NULL, updated_at = datetime('now') WHERE id = ?",
    );
    for (const [name, info] of files) {
      const row = existing.get(name);
      if (!row) {
        const r = insert.run(systemId, name, info.size, info.mtime, titleFromFileName(name, { folder: folders }), folders ? 1 : 0);
        result.added.push(Number(r.lastInsertRowid));
      } else if (row.size !== info.size || row.mtime !== info.mtime) {
        update.run(info.size, info.mtime, row.id);
        result.updated++;
      }
    }
    // Anciennes fiches de l'autre mode : retirées (fichier laissé sur le disque).
    for (const row of db.prepare('SELECT id, is_folder FROM games WHERE system_id = ?').all(systemId)) {
      if (Boolean(row.is_folder) === folders) continue;
      db.prepare('DELETE FROM games WHERE id = ?').run(row.id);
      fs.rmSync(gameMediaDir(row.id), { recursive: true, force: true });
      result.removed++;
    }
    const del = db.prepare('DELETE FROM games WHERE id = ?');
    for (const [name, row] of existing) {
      if (files.has(name)) continue;
      del.run(row.id);
      fs.rmSync(gameMediaDir(row.id), { recursive: true, force: true });
      result.removed++;
    }
  });
  if (folders) {
    // Exécutable et désinstalleur de chaque jeu : devinés s'ils n'ont pas été choisis, ou s'ils
    // n'existent plus (désinstalleur « aucun » choisi dans la fiche : gardé).
    for (const row of db.prepare('SELECT id, file_name, exe, uninstall_exe, extra_exes, extra_exes_chosen FROM games WHERE system_id = ?').all(systemId)) {
      const files = folderFiles(path.join(dir, row.file_name));
      // Compression (folder-pack.js) : fichiers compressés et taille à télécharger.
      const stats = folderStats(null, files);
      db.prepare('UPDATE games SET packed_files = ?, packed_size = ?, folder_files = ?, pack_format = ? WHERE id = ?').run(stats.packed, stats.packedSize, stats.count, stats.format, row.id);
      const candidates = exeCandidates(files);
      if (!row.exe || !candidates.includes(row.exe)) db.prepare('UPDATE games SET exe = ? WHERE id = ?').run(candidates[0] ?? null, row.id);
      // Autres exécutables : devinés, ou ceux choisis qui existent encore.
      const extras = row.extra_exes_chosen ? extraExesOf(row).filter((p) => candidates.includes(p)) : prereqsOf(files);
      db.prepare('UPDATE games SET extra_exes = ? WHERE id = ?').run(JSON.stringify(extras), row.id);
      if (row.uninstall_exe === '' || (row.uninstall_exe && candidates.includes(row.uninstall_exe))) continue;
      db.prepare('UPDATE games SET uninstall_exe = ? WHERE id = ?').run(uninstallerOf(files), row.id);
    }
    return result;
  }
  // Disques, mises à jour et DLC rattachés à leur jeu.
  groupSystem(systemId);
  // Paquets PS4 convertis en .zip du dossier du jeu, en tâche de fond.
  queuePs4Conversions(systemId);
  return result;
}

/**
 * Fichiers d'un jeu en dossier ({ path, size } ; compressé : format (xz, zip), packedSize et crc32
 * en plus, archive à demander avec ?packed=1 ; zip : zipSize aussi) et ses exécutables (le plus probable en premier) ; packed : état de compression,
 * packedSize : taille à télécharger.
 */
export function gameFolderFiles(row, { zst = false } = {}) {
  if (!row.is_folder) throw new HttpError(400, 'errors.notFolderGame');
  const files = folderFiles(gameFilePath(row));
  const stats = folderStats(null, files);
  return {
    // Archives zstd : listées seulement pour les applications qui savent les lire ([zst]) ; pour les
    // autres, fichiers en clair (décompressés à la volée par le serveur au téléchargement).
    files: files.map(({ path: p, size, packed, format, packedSize, zipSize, crc32 }) =>
      packed && (zst || format !== 'zst')
        ? { path: p, size, format, packedSize, crc32, ...(zipSize !== undefined ? { zipSize } : {}) }
        : { path: p, size },
    ),
    packed: stats.packed === 0 ? 'flat' : stats.packed === files.length ? 'zip' : 'partial',
    packFormat: stats.format,
    size: stats.size,
    packedSize: stats.packedSize,
    exes: exeCandidates(files),
    exe: row.exe ?? null,
    uninstallExe: row.uninstall_exe || null,
    extraExes: extraExesOf(row),
  };
}

/** Jeu PC compressé : « zip » (tous ses fichiers), « partial » (une partie) ou « flat » (aucun). */
function packState(row) {
  if (!row.packed_files) return 'flat';
  return row.packed_files >= (row.folder_files ?? 0) ? 'zip' : 'partial';
}

/** Autres exécutables d'un jeu en dossier (colonne JSON), liste vide sans eux. */
function extraExesOf(row) {
  try {
    const list = JSON.parse(row.extra_exes || '[]');
    return Array.isArray(list) ? list.filter((p) => typeof p === 'string') : [];
  } catch {
    return [];
  }
}

/** Exécutable choisi pour un jeu en dossier : un .exe de son dossier (vide : aucun). */
function validateExe(id, value) {
  if (value === null) return null;
  const row = requireGameRow(id);
  if (!row.is_folder) throw new HttpError(400, 'errors.notFolderGame');
  const exe = String(value).replace(/\\/g, '/');
  if (!gameFolderFiles(row).exes.includes(exe)) throw new HttpError(400, 'errors.unknownExe', { exe });
  return exe;
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
