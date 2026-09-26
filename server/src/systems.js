import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';
import { db } from './db.js';
import { HttpError } from './http-error.js';
import { SCREENSCRAPER_SYSTEM_IDS } from './screenscraper-systems.js';

const ID_RE = /^[a-z0-9][a-z0-9._-]{0,63}$/;

function rowToSystem(row, stats) {
  if (!row) return null;
  return {
    id: row.id,
    name: row.name,
    shortname: row.shortname,
    folder: row.folder,
    filenameRegex: row.filename_regex,
    libretroName: row.libretro_name,
    screenscraperId: row.screenscraper_id,
    players: JSON.parse(row.players || '[]'),
    source: row.source,
    hasImage: Boolean(row.image),
    // Nom du fichier image : change à chaque envoi, sert à invalider les caches.
    imageVersion: row.image,
    sourceRevision: row.source_revision,
    createdAt: row.created_at,
    gameCount: stats?.game_count ?? 0,
    totalSize: stats?.total_size ?? 0,
  };
}

export function listSystems() {
  const stats = new Map(
    db
      .prepare('SELECT system_id, COUNT(*) AS game_count, SUM(size) AS total_size FROM games GROUP BY system_id')
      .all()
      .map((r) => [r.system_id, r]),
  );
  return db
    .prepare('SELECT * FROM systems ORDER BY name COLLATE NOCASE')
    .all()
    .map((r) => rowToSystem(r, stats.get(r.id)));
}

export function getSystem(id) {
  const row = db.prepare('SELECT * FROM systems WHERE id = ?').get(id);
  if (!row) return null;
  const stats = db
    .prepare('SELECT COUNT(*) AS game_count, SUM(size) AS total_size FROM games WHERE system_id = ?')
    .get(id);
  return rowToSystem(row, stats);
}

export function requireSystem(id) {
  const system = getSystem(id);
  if (!system) throw new HttpError(404, `Système inconnu : ${id}`);
  return system;
}

export function systemDir(system) {
  return path.join(config.romsDir, system.folder);
}

function validateRegex(re) {
  if (!re) return null;
  try {
    new RegExp(re);
    return re;
  } catch {
    throw new HttpError(400, `Expression régulière invalide : ${re}`);
  }
}

function validateFolder(folder) {
  if (!folder || !/^[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}$/.test(folder)) {
    throw new HttpError(400, 'Nom de dossier invalide (lettres, chiffres, espace, . _ -)');
  }
  return folder;
}

export function createSystem(input) {
  const id = String(input.id || '').toLowerCase();
  if (!ID_RE.test(id)) throw new HttpError(400, 'Identifiant invalide (a-z, 0-9, . _ -)');
  if (!input.name) throw new HttpError(400, 'Le nom est obligatoire');
  if (getSystem(id)) throw new HttpError(409, `Le système « ${id} » existe déjà`);
  const folder = validateFolder(input.folder || id);
  if (db.prepare('SELECT 1 FROM systems WHERE folder = ?').get(folder)) {
    throw new HttpError(409, `Le dossier « ${folder} » est déjà utilisé`);
  }
  db.prepare(
    `INSERT INTO systems (id, name, shortname, folder, filename_regex, libretro_name, screenscraper_id, players, source, source_revision)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(
    id,
    input.name,
    input.shortname || id,
    folder,
    validateRegex(input.filenameRegex),
    input.libretroName || null,
    input.screenscraperId ? Number(input.screenscraperId) : SCREENSCRAPER_SYSTEM_IDS[id] ?? null,
    JSON.stringify(input.players || []),
    input.source || 'custom',
    input.sourceRevision ?? null,
  );
  fs.mkdirSync(path.join(config.romsDir, folder), { recursive: true });
  return getSystem(id);
}

export function updateSystem(id, input) {
  const current = requireSystem(id);
  const next = {
    name: input.name ?? current.name,
    shortname: input.shortname ?? current.shortname,
    filenameRegex: input.filenameRegex !== undefined ? validateRegex(input.filenameRegex) : current.filenameRegex,
    libretroName: input.libretroName !== undefined ? input.libretroName || null : current.libretroName,
    screenscraperId:
      input.screenscraperId !== undefined
        ? input.screenscraperId === '' || input.screenscraperId === null
          ? null
          : Number(input.screenscraperId)
        : current.screenscraperId,
    players: input.players ?? current.players,
  };
  db.prepare(
    `UPDATE systems SET name = ?, shortname = ?, filename_regex = ?, libretro_name = ?, screenscraper_id = ?, players = ?
     WHERE id = ?`,
  ).run(
    next.name,
    next.shortname,
    next.filenameRegex,
    next.libretroName,
    next.screenscraperId,
    JSON.stringify(next.players),
    id,
  );
  return getSystem(id);
}

// ---------------------------------------------------------------------------
// Image du système (logo, photo de la console…) affichée dans l'application
// ---------------------------------------------------------------------------

const IMAGE_EXT = { 'image/png': '.png', 'image/jpeg': '.jpg', 'image/webp': '.webp', 'image/gif': '.gif' };
const systemImagesDir = () => path.join(config.mediaDir, 'systems');

function imageRow(id) {
  return db.prepare('SELECT image FROM systems WHERE id = ?').get(id)?.image || null;
}

export function systemImagePath(id) {
  requireSystem(id);
  const image = imageRow(id);
  return image ? path.join(systemImagesDir(), image) : null;
}

function removeImageFile(id) {
  const image = imageRow(id);
  if (image) fs.rmSync(path.join(systemImagesDir(), image), { force: true });
}

export function setSystemImage(id, buffer, mimeType) {
  requireSystem(id);
  const ext = IMAGE_EXT[mimeType];
  if (!ext) throw new HttpError(415, 'Format d’image non supporté (PNG, JPEG, WebP ou GIF)');
  if (!buffer?.length) throw new HttpError(400, 'Image manquante');
  fs.mkdirSync(systemImagesDir(), { recursive: true });
  removeImageFile(id);
  const file = `${id}-${Date.now()}${ext}`;
  fs.writeFileSync(path.join(systemImagesDir(), file), buffer);
  db.prepare('UPDATE systems SET image = ? WHERE id = ?').run(file, id);
  return getSystem(id);
}

export function deleteSystemImage(id) {
  requireSystem(id);
  removeImageFile(id);
  db.prepare('UPDATE systems SET image = NULL WHERE id = ?').run(id);
  return getSystem(id);
}

export function deleteSystem(id, { deleteFiles = false } = {}) {
  const system = requireSystem(id);
  removeImageFile(id);
  const gameIds = db.prepare('SELECT id FROM games WHERE system_id = ?').all(id).map((r) => r.id);
  db.prepare('DELETE FROM systems WHERE id = ?').run(id);
  for (const gid of gameIds) fs.rmSync(path.join(config.mediaDir, String(gid)), { recursive: true, force: true });
  if (deleteFiles) fs.rmSync(systemDir(system), { recursive: true, force: true });
}

// ---------------------------------------------------------------------------
// Import des plateformes Daijishou (https://github.com/TapiocaFox/Daijishou/tree/main/platforms)
// et des plateformes fournies par RomCloud (server/platforms/, même format), pour les
// systèmes absents du catalogue Daijishou (ex. Amstrad GX4000).
// ---------------------------------------------------------------------------

let indexCache = null;
let indexCacheAt = 0;

const LOCAL_PLATFORMS_DIR = path.join(config.rootDir, 'platforms');

function localPlatformList() {
  try {
    return JSON.parse(fs.readFileSync(path.join(LOCAL_PLATFORMS_DIR, 'index.json'), 'utf8')).platformList || [];
  } catch {
    return [];
  }
}

async function fetchJson(url) {
  const res = await fetch(url, { headers: { 'User-Agent': 'romcloud-server' } });
  if (!res.ok) throw new HttpError(502, `Échec du téléchargement de ${url} (${res.status})`);
  return res.json();
}

export async function listDaijishouPlatforms() {
  const local = localPlatformList();
  if (!indexCache || Date.now() - indexCacheAt > 3600_000) {
    try {
      const index = await fetchJson(new URL('index.json', config.daijishouBaseUrl).href);
      indexCache = index.platformList;
      indexCacheAt = Date.now();
    } catch (err) {
      // Sans accès à GitHub, le catalogue RomCloud reste disponible.
      if (!local.length) throw err;
      indexCache = null;
    }
  }
  const daijishou = indexCache || [];
  const daijishouIds = new Set(daijishou.map((p) => p.platformUniqueId));
  const entries = [
    ...daijishou.map((p) => ({ ...p, source: 'daijishou' })),
    // Une plateforme ajoutée plus tard au catalogue Daijishou prend le pas sur la version RomCloud.
    ...local.filter((p) => !daijishouIds.has(p.platformUniqueId)).map((p) => ({ ...p, source: 'romcloud' })),
  ].sort((a, b) => a.platformName.localeCompare(b.platformName, 'fr'));

  const existing = new Map(listSystems().map((s) => [s.id, s]));
  return entries.map((p) => ({
    filename: p.filename,
    name: p.platformName,
    shortname: p.platformShortname,
    uniqueId: p.platformUniqueId,
    revision: p.revisionNumber,
    source: p.source,
    imported: existing.has(p.platformUniqueId),
    importedRevision: existing.get(p.platformUniqueId)?.sourceRevision ?? null,
  }));
}

/** Transforme un fichier plateforme Daijishou en données de système. */
export function platformToSystem(json, source = 'daijishou') {
  const p = json.platform;
  if (!p?.uniqueId) throw new HttpError(400, 'Fichier plateforme Daijishou invalide');
  const libretro = (p.scraperSourceList || []).find((s) => s.startsWith('LIBRETRO:'));
  return {
    id: p.uniqueId.toLowerCase(),
    name: p.name,
    shortname: p.shortname || p.uniqueId,
    folder: p.shortname || p.uniqueId,
    filenameRegex: p.acceptedFilenameRegex || null,
    libretroName: libretro ? libretro.slice('LIBRETRO:'.length) : null,
    screenscraperId: SCREENSCRAPER_SYSTEM_IDS[p.uniqueId] ?? null,
    players: (json.playerList || []).map((pl) => ({
      name: pl.name,
      uniqueId: pl.uniqueId,
      description: pl.description || null,
      acceptedFilenameRegex: pl.acceptedFilenameRegex || null,
      amStartArguments: pl.amStartArguments,
      killPackageProcesses: Boolean(pl.killPackageProcesses),
    })),
    source,
    sourceRevision: json.revisionNumber ?? null,
  };
}

/** Charge une plateforme : catalogue RomCloud (fichier local) en priorité, sinon Daijishou. */
async function fetchPlatform(filename) {
  if (!filename || !/^[\w&.' -]+\.json$/.test(filename)) throw new HttpError(400, 'Nom de fichier invalide');
  if (localPlatformList().some((p) => p.filename === filename)) {
    const json = JSON.parse(fs.readFileSync(path.join(LOCAL_PLATFORMS_DIR, filename), 'utf8'));
    return { json, source: 'romcloud' };
  }
  const json = await fetchJson(new URL(encodeURIComponent(filename), config.daijishouBaseUrl).href);
  return { json, source: 'daijishou' };
}

/** Émulateurs d'une plateforme du catalogue (pour les copier vers un autre système). */
export async function daijishouPlayers(filename) {
  const { json, source } = await fetchPlatform(filename);
  return platformToSystem(json, source).players;
}

/** Importe (ou met à jour) une plateforme à partir de son nom de fichier ou de son JSON brut. */
export async function importDaijishouPlatform({ filename, json }) {
  let source = 'daijishou';
  if (!json) ({ json, source } = await fetchPlatform(filename));
  const data = platformToSystem(json, source);
  const existing = getSystem(data.id);
  if (existing) {
    // Mise à jour : on conserve le dossier et les réglages de scraping saisis à la main.
    updateSystem(data.id, { name: data.name, shortname: data.shortname, filenameRegex: data.filenameRegex, players: data.players });
    db.prepare('UPDATE systems SET source = ?, source_revision = ? WHERE id = ?').run(source, data.sourceRevision, data.id);
    if (!existing.libretroName && data.libretroName) updateSystem(data.id, { libretroName: data.libretroName });
    if (!existing.screenscraperId && data.screenscraperId) updateSystem(data.id, { screenscraperId: data.screenscraperId });
    return getSystem(data.id);
  }
  let folder = data.folder;
  for (let i = 2; db.prepare('SELECT 1 FROM systems WHERE folder = ?').get(folder); i++) folder = `${data.folder}-${i}`;
  return createSystem({ ...data, folder });
}
