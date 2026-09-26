import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import express from 'express';
import multer from 'multer';
import { config, screenscraperEnabled } from './config.js';
import { HttpError } from './http-error.js';
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
  updateGame,
} from './library.js';
import { SCRAPE_SOURCES, saveCustomMedia, scrapeGame } from './scraper/index.js';
import {
  createSystem,
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

// Informations publiques (permet à l'application de savoir si une clé est requise).
api.get('/info', (req, res) => {
  res.json({ name: 'RomCloud', version: VERSION, authRequired: Boolean(config.apiKey) });
});

// ---- Authentification par clé d'API ----
function keyMatches(given) {
  if (!given) return false;
  const a = Buffer.from(String(given));
  const b = Buffer.from(config.apiKey);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

api.use((req, res, next) => {
  if (!config.apiKey) return next();
  const auth = req.get('authorization') || '';
  const given = auth.startsWith('Bearer ') ? auth.slice(7) : req.get('x-api-key') || req.query.key;
  if (keyMatches(given)) return next();
  res.status(401).json({ error: 'Clé d’API invalide ou manquante' });
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
  if (!file) throw new HttpError(404, 'Aucune image pour ce système');
  // Le nom de fichier change à chaque envoi : on peut mettre en cache longtemps.
  res.sendFile(file, { maxAge: '30d' });
});

api.put(
  '/systems/:id/image',
  express.raw({ type: 'image/*', limit: '10mb' }),
  (req, res) => res.json(setSystemImage(req.params.id, req.body, req.get('content-type')?.split(';')[0])),
);

api.delete('/systems/:id/image', (req, res) => res.json(deleteSystemImage(req.params.id)));

api.post('/systems/:id/scan', (req, res) => res.json(scanSystem(req.params.id)));

api.post('/scan', (req, res) => res.json(scanAll()));

// ---- Plateformes Daijishou ----
api.get('/daijishou/platforms', h(async (req, res) => res.json(await listDaijishouPlatforms())));

api.post(
  '/daijishou/import',
  h(async (req, res) => {
    const { filename, filenames, json } = req.body || {};
    if (json) return res.status(201).json([await importDaijishouPlatform({ json })]);
    const list = filenames || (filename ? [filename] : []);
    if (!list.length) throw new HttpError(400, 'Aucune plateforme indiquée');
    const imported = [];
    for (const f of list) imported.push(await importDaijishouPlatform({ filename: f }));
    res.status(201).json(imported);
  }),
);

// ---- Jeux ----
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
      job = enqueueScrape({ label: `${system.name} : ${scan.added.length} nouveau(x) jeu(x)`, systemId: system.id, gameIds: scan.added });
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
    if (err && !res.headersSent) next(new HttpError(404, 'Fichier introuvable sur le serveur'));
  });
});

const MEDIA_TYPES = ['boxart', 'screenshot'];

api.get('/games/:id/media/:type', (req, res) => {
  const { type } = req.params;
  if (!MEDIA_TYPES.includes(type)) throw new HttpError(404, 'Type de média inconnu');
  const row = requireGameRow(req.params.id);
  if (!row[type]) throw new HttpError(404, 'Aucun média');
  res.sendFile(path.join(gameMediaDir(row.id), row[type]), { maxAge: '1h' });
});

api.put(
  '/games/:id/media/:type',
  express.raw({ type: 'image/*', limit: '20mb' }),
  (req, res) => {
    const { type } = req.params;
    if (!MEDIA_TYPES.includes(type)) throw new HttpError(404, 'Type de média inconnu');
    if (!Buffer.isBuffer(req.body) || !req.body.length) throw new HttpError(400, 'Image manquante');
    res.json(saveCustomMedia(req.params.id, type, req.body, req.get('content-type')));
  },
);

// ---- Scraping ----
function sourceParam(req) {
  const source = (req.body && req.body.source) || req.query.source || 'auto';
  if (!SCRAPE_SOURCES.includes(source)) throw new HttpError(400, `Source inconnue : ${source}`);
  return source;
}

api.post('/games/:id/scrape', h(async (req, res) => res.json(await scrapeGame(req.params.id, sourceParam(req)))));

api.post('/systems/:id/scrape', (req, res) => {
  const system = requireSystem(req.params.id);
  const onlyMissing = (req.body && req.body.onlyMissing) !== false;
  const games = listGames(system.id).filter((g) => !onlyMissing || g.scrapeStatus !== 'ok');
  if (!games.length) return res.json({ job: null, message: 'Aucun jeu à scraper' });
  const job = enqueueScrape({
    label: `${system.name} : ${games.length} jeu(x)`,
    systemId: system.id,
    gameIds: games.map((g) => g.id),
    source: sourceParam(req),
  });
  res.status(202).json({ job });
});

api.get('/jobs', (req, res) => res.json(listJobs()));

api.delete('/jobs/:id', (req, res) => {
  if (!cancelJob(req.params.id)) throw new HttpError(404, 'Tâche inconnue');
  res.status(204).end();
});

// ---- Erreurs ----
api.use((req, res) => res.status(404).json({ error: 'Route inconnue' }));

// eslint-disable-next-line no-unused-vars
api.use((err, req, res, next) => {
  const status = err.status || (err.code === 'LIMIT_FILE_SIZE' ? 413 : 500);
  if (status >= 500) console.error(err);
  res.status(status).json({ error: err.message || 'Erreur interne' });
});
