import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import express from 'express';
import multer from 'multer';
import { addApk, apkFilePath, deleteApk, emulatorPackages, listApks, requireApk, updateApk } from './apks.js';
import { addBiosFiles, biosFilePath, deleteBios, requireBios, systemBios } from './bios.js';
import { config, screenscraperEnabled } from './config.js';
import { deleteGamesOfSystem, systemDuplicates } from './duplicates.js';
import { HttpError } from './http-error.js';
import { LANGUAGES, localize, requestLanguage, token } from './i18n.js';
import { cancelJob, enqueueScrape, listJobs } from './jobs.js';
import {
  acceptsFileName,
  deleteGame,
  gameFilePath,
  gameMediaDir,
  listGames,
  requireGameRow,
  rowToGame,
  safeFileName,
  scanAll,
  scanSystem,
  searchGames,
  updateGame,
} from './library.js';
import { SCRAPE_SOURCES, saveCustomMedia, scrapeGame } from './scraper/index.js';
import {
  createSystem,
  daijishouPlayers,
  deleteSystem,
  deleteSystemImage,
  importDaijishouPlatform,
  listDaijishouPlatforms,
  listSystems,
  requireSystem,
  setSystemImage,
  systemDir,
  systemImagePath,
  updateSystem,
} from './systems.js';

const VERSION = '1.0.0';
export const api = express.Router();

// Enveloppe les handlers async pour transmettre les erreurs à Express.
const h = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

api.use(express.json({ limit: '2mb' }));

// Langue de la réponse (Accept-Language) ; les jetons de message sont traduits à l'envoi.
api.use((req, res, next) => {
  const lang = requestLanguage(req);
  res.set('Content-Language', lang);
  const json = res.json.bind(res);
  res.json = (body) => json(localize(body, lang));
  next();
});

// Informations publiques (permet à l'application de savoir si une clé est requise).
api.get('/info', (req, res) => {
  res.json({
    name: 'RomCloud',
    version: VERSION,
    authRequired: Boolean(config.apiKey),
    adminKeyRequired: Boolean(config.adminKey || config.apiKey),
    languages: LANGUAGES,
  });
});

// ---- Authentification : clé des applications (lecture) et clé d'administration (tout) ----
function keyMatches(given, expected) {
  if (!given || !expected) return false;
  const a = Buffer.from(String(given));
  const b = Buffer.from(expected);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

/**
 * Droits d'une requête : clé d'administration (ADMIN_KEY, ou API_KEY seule) -> tout ; clé des
 * applications -> lectures (GET, HEAD) ; sans clé configurée, l'accès correspondant est libre.
 */
export function access(method, given, { apiKey, adminKey }) {
  const read = method === 'GET' || method === 'HEAD';
  const admin = adminKey || apiKey;
  if (!admin || keyMatches(given, admin)) return 'ok';
  if (read && (!apiKey || keyMatches(given, apiKey))) return 'ok';
  // Clé des applications sur une modification : réservée à l'administration.
  return keyMatches(given, apiKey) ? 'admin-required' : 'denied';
}

api.use((req, res, next) => {
  const auth = req.get('authorization') || '';
  const given = auth.startsWith('Bearer ') ? auth.slice(7) : req.get('x-api-key') || req.query.key;
  const result = access(req.method, given, config);
  if (result === 'ok') return next();
  if (result === 'admin-required') return res.status(403).json({ error: token('errors.adminKey'), code: 'errors.adminKey' });
  res.status(401).json({ error: token('errors.apiKey') });
});

api.get('/status', (req, res) => {
  res.json({
    version: VERSION,
    scrapers: { screenscraper: screenscraperEnabled(), libretro: true },
    romsDir: config.romsDir,
    jobs: listJobs().filter((j) => j.status === 'running' || j.status === 'queued').length,
  });
});

// ---- Systèmes ----
api.get('/systems', (req, res) => res.json(listSystems()));

api.post('/systems', (req, res) => res.status(201).json(createSystem(req.body || {})));

api.get('/systems/:id', (req, res) => res.json(requireSystem(req.params.id)));

api.put('/systems/:id', (req, res) => res.json(updateSystem(req.params.id, req.body || {})));

api.delete('/systems/:id', (req, res) => {
  deleteSystem(req.params.id, { deleteFiles: req.query.deleteFiles === '1' });
  res.status(204).end();
});

api.get('/systems/:id/image', (req, res) => {
  const file = systemImagePath(req.params.id);
  if (!file) throw new HttpError(404, 'errors.noSystemImage');
  // Le nom de fichier change à chaque envoi : on peut mettre en cache longtemps.
  res.sendFile(file, { maxAge: '30d' });
});

api.put(
  '/systems/:id/image',
  express.raw({ type: 'image/*', limit: '10mb' }),
  (req, res) => res.json(setSystemImage(req.params.id, req.body, req.get('content-type')?.split(';')[0])),
);

api.delete('/systems/:id/image', (req, res) => res.json(deleteSystemImage(req.params.id)));

api.get('/systems/:id/duplicates', h(async (req, res) => res.json(await systemDuplicates(req.params.id))));

api.post('/systems/:id/duplicates/delete', (req, res) => {
  res.json(deleteGamesOfSystem(req.params.id, (req.body && req.body.ids) || []));
});

// ---- BIOS ----
// { cores, coreInfoAvailable, files: BIOS présents, expected: BIOS attendus par les cœurs RetroArch }
// ?catalog=0 : fichiers présents seulement (utilisé par les applications).
api.get(
  '/systems/:id/bios',
  h(async (req, res) => res.json(await systemBios(req.params.id, { catalog: req.query.catalog !== '0' }))),
);

const biosUpload = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => {
      const dir = path.join(config.dataDir, 'bios', '.upload');
      fs.mkdirSync(dir, { recursive: true });
      cb(null, dir);
    },
    filename: (req, file, cb) => cb(null, crypto.randomUUID()),
  }),
  limits: { fileSize: 1024 * 1024 * 1024 },
});

// Envoi de BIOS (champ « files ») ; « path » (facultatif, un seul fichier) force le chemin relatif.
api.post(
  '/systems/:id/bios',
  biosUpload.array('files'),
  h(async (req, res) => {
    const files = req.files || [];
    try {
      if (!files.length) throw new HttpError(400, 'errors.noFiles');
      const uploads = files.map((f) => ({
        tempPath: f.path,
        originalName: Buffer.from(f.originalname, 'latin1').toString('utf8'),
      }));
      const saved = await addBiosFiles(req.params.id, uploads, req.body && req.body.path);
      res.status(201).json({ saved, ...(await systemBios(req.params.id)) });
    } finally {
      for (const f of files) fs.rmSync(f.path, { force: true });
    }
  }),
);

api.get('/bios/:id/file', (req, res, next) => {
  const row = requireBios(req.params.id);
  res.download(biosFilePath(row), path.posix.basename(row.path), { dotfiles: 'allow' }, (err) => {
    if (err && !res.headersSent) next(new HttpError(404, 'errors.fileNotFound'));
  });
});

api.delete('/bios/:id', (req, res) => {
  deleteBios(req.params.id);
  res.status(204).end();
});

// ---- APK des émulateurs Android ----
api.get('/apks', (req, res) => res.json(listApks()));

// Émulateurs utilisés par les systèmes (paquets des modèles), avec l'APK disponible le cas échéant.
api.get('/apks/emulators', (req, res) => res.json(emulatorPackages()));

const apkUpload = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => {
      const dir = path.join(config.dataDir, 'apks', '.upload');
      fs.mkdirSync(dir, { recursive: true });
      cb(null, dir);
    },
    filename: (req, file, cb) => cb(null, crypto.randomUUID()),
  }),
  limits: { fileSize: config.maxUploadMb * 1024 * 1024 },
});

// Envoi d'APK (champ « files ») : paquet et version lus dans le manifeste de chaque APK.
api.post(
  '/apks',
  apkUpload.array('files'),
  h(async (req, res) => {
    const files = req.files || [];
    try {
      if (!files.length) throw new HttpError(400, 'errors.noFiles');
      const saved = [];
      for (const f of files) saved.push(await addApk(f.path, Buffer.from(f.originalname, 'latin1').toString('utf8')));
      res.status(201).json(saved);
    } finally {
      for (const f of files) fs.rmSync(f.path, { force: true });
    }
  }),
);

api.put('/apks/:id', (req, res) => res.json(updateApk(req.params.id, req.body || {})));

api.delete('/apks/:id', (req, res) => {
  deleteApk(req.params.id);
  res.status(204).end();
});

api.get('/apks/:id/file', (req, res, next) => {
  const row = requireApk(req.params.id);
  res.type('application/vnd.android.package-archive');
  res.download(apkFilePath(row), `${row.package_name}-${row.version_name || row.version_code}.apk`, { dotfiles: 'allow' }, (err) => {
    if (err && !res.headersSent) next(new HttpError(404, 'errors.fileNotFound'));
  });
});

api.post('/systems/:id/scan', (req, res) => res.json(scanSystem(req.params.id)));

api.post('/scan', (req, res) => res.json(scanAll()));

// ---- Plateformes Daijishou ----
api.get('/daijishou/platforms', h(async (req, res) => res.json(await listDaijishouPlatforms())));

api.get(
  '/daijishou/platforms/:filename/players',
  h(async (req, res) => res.json(await daijishouPlayers(req.params.filename))),
);

api.post(
  '/daijishou/import',
  h(async (req, res) => {
    const { filename, filenames, json } = req.body || {};
    if (json) return res.status(201).json([await importDaijishouPlatform({ json })]);
    const list = filenames || (filename ? [filename] : []);
    if (!list.length) throw new HttpError(400, 'errors.noPlatform');
    const imported = [];
    for (const f of list) imported.push(await importDaijishouPlatform({ filename: f }));
    res.status(201).json(imported);
  }),
);

// ---- Jeux ----
// Recherche globale (tous les systèmes) : ?q=mots&limit=300
api.get('/search', (req, res) => res.json(searchGames(req.query.q, { limit: req.query.limit })));

api.get('/systems/:id/games', (req, res) => res.json(listGames(req.params.id, { q: req.query.q })));

const upload = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => {
      try {
        const dir = systemDir(requireSystem(req.params.id));
        fs.mkdirSync(dir, { recursive: true });
        cb(null, dir);
      } catch (err) {
        cb(err);
      }
    },
    // Écrit dans un fichier temporaire caché, renommé une fois l'envoi terminé.
    filename: (req, file, cb) => cb(null, `.upload-${crypto.randomUUID()}`),
  }),
  limits: { fileSize: config.maxUploadMb * 1024 * 1024 },
});

api.post(
  '/systems/:id/games',
  upload.array('files'),
  h(async (req, res) => {
    const system = requireSystem(req.params.id);
    const dir = systemDir(system);
    const saved = [];
    const rejected = [];
    for (const file of req.files || []) {
      // multer décode les noms en latin1 : on les réinterprète en UTF-8.
      const original = Buffer.from(file.originalname, 'latin1').toString('utf8');
      let name;
      try {
        name = safeFileName(original);
      } catch {
        name = null;
      }
      if (!name || !acceptsFileName(system, name)) {
        fs.rmSync(file.path, { force: true });
        rejected.push(original);
        continue;
      }
      fs.renameSync(file.path, path.join(dir, name));
      saved.push(name);
    }
    const scan = scanSystem(system.id);
    let job = null;
    if (req.query.scrape === '1' && scan.added.length) {
      job = enqueueScrape({ label: token('jobs.newGames', { system: system.name, count: scan.added.length }), systemId: system.id, gameIds: scan.added });
    }
    res.status(201).json({ saved, rejected, scan, job });
  }),
);

api.get('/games/:id', (req, res) => res.json(rowToGame(requireGameRow(req.params.id))));

api.put('/games/:id', (req, res) => res.json(updateGame(req.params.id, req.body || {})));

api.delete('/games/:id', (req, res) => {
  deleteGame(req.params.id, { deleteFile: req.query.keepFile !== '1' });
  res.status(204).end();
});

// Téléchargement du fichier ROM (gère les requêtes Range pour la reprise).
api.get('/games/:id/file', (req, res, next) => {
  const row = requireGameRow(req.params.id);
  res.download(gameFilePath(row), row.file_name, { dotfiles: 'allow' }, (err) => {
    if (err && !res.headersSent) next(new HttpError(404, 'errors.fileNotFound'));
  });
});

const MEDIA_TYPES = ['boxart', 'screenshot'];

api.get('/games/:id/media/:type', (req, res) => {
  const { type } = req.params;
  if (!MEDIA_TYPES.includes(type)) throw new HttpError(404, 'errors.unknownMediaType');
  const row = requireGameRow(req.params.id);
  if (!row[type]) throw new HttpError(404, 'errors.noMedia');
  res.sendFile(path.join(gameMediaDir(row.id), row[type]), { maxAge: '1h' });
});

api.put(
  '/games/:id/media/:type',
  express.raw({ type: 'image/*', limit: '20mb' }),
  (req, res) => {
    const { type } = req.params;
    if (!MEDIA_TYPES.includes(type)) throw new HttpError(404, 'errors.unknownMediaType');
    if (!Buffer.isBuffer(req.body) || !req.body.length) throw new HttpError(400, 'errors.imageMissing');
    res.json(saveCustomMedia(req.params.id, type, req.body, req.get('content-type')));
  },
);

// ---- Scraping ----
function sourceParam(req) {
  const source = (req.body && req.body.source) || req.query.source || 'auto';
  if (!SCRAPE_SOURCES.includes(source)) throw new HttpError(400, 'errors.unknownSource', { source });
  return source;
}

api.post('/games/:id/scrape', h(async (req, res) => res.json(await scrapeGame(req.params.id, sourceParam(req)))));

api.post('/systems/:id/scrape', (req, res) => {
  const system = requireSystem(req.params.id);
  const onlyMissing = (req.body && req.body.onlyMissing) !== false;
  const games = listGames(system.id).filter((g) => !onlyMissing || g.scrapeStatus !== 'ok');
  if (!games.length) return res.json({ job: null, message: token('messages.nothingToScrape') });
  const job = enqueueScrape({
    label: token('jobs.systemGames', { system: system.name, count: games.length }),
    systemId: system.id,
    gameIds: games.map((g) => g.id),
    source: sourceParam(req),
  });
  res.status(202).json({ job });
});

api.get('/jobs', (req, res) => res.json(listJobs()));

api.delete('/jobs/:id', (req, res) => {
  if (!cancelJob(req.params.id)) throw new HttpError(404, 'errors.unknownJob');
  res.status(204).end();
});

// ---- Erreurs ----
api.use((req, res) => res.status(404).json({ error: token('errors.routeNotFound') }));

// eslint-disable-next-line no-unused-vars
api.use((err, req, res, next) => {
  const tooLarge = err.code === 'LIMIT_FILE_SIZE';
  const status = err.status || (tooLarge ? 413 : 500);
  if (status >= 500) console.error(err);
  const error = tooLarge ? token('errors.fileTooLarge') : err.message || token('errors.internal');
  res.status(status).json({ error, code: err.key });
});
