import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import express from 'express';
import multer from 'multer';
import * as accounts from './accounts.js';
import * as play from './play.js';
import * as lobbies from './lobbies.js';
import { addApk, apkFilePath, deleteApk, emulatorPackages, listApks, requireApk, updateApk } from './apks.js';
import { addBiosFiles, biosFilePath, deleteBios, fetchBiosFromSource, importBiosPack, requireBios, systemBios } from './bios.js';
import { config, screenscraperEnabled } from './config.js';
import { diskUsage } from './disk-usage.js';
import { clearDownloads, downloadStats, listDownloads, trackDownload } from './download-logs.js';
import { deleteGamesOfSystem, systemDuplicates } from './duplicates.js';
import { profileStats } from './profile-stats.js';
import { fileInFolder, readPackManifest } from './folder-games.js';
import { queueFolderPack } from './folder-pack.js';
import { readZipEntries, zipEntryStream } from './zip.js';
import { HttpError } from './http-error.js';
import { LANGUAGES, localize, requestLanguage, token } from './i18n.js';
import { setGroup } from './groups.js';
import { cancelJob, enqueueScrape, listJobs } from './jobs.js';
import {
  acceptsFileName,
  deleteGame,
  gameFilePath,
  gameFolderFiles,
  gameMediaDir,
  gameWithParts,
  listGames,
  requireGameRow,
  SCREENSHOT_TYPES,
  deleteGameMedia,
  safeFileName,
  scanAll,
  scanSystem,
  searchGames,
  updateGame,
} from './library.js';
import { SCRAPE_SOURCES, saveCustomMedia, scrapeGame } from './scraper/index.js';
import { importLicenseList } from './licenses.js';
import { mediaVariant, VARIANTS_DIR } from './media-variants.js';
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
import { emulatorCandidates } from './emulator-order.js';

// Version du serveur : celle de l'image Docker (tag v1.2.3 de la publication), sinon package.json.
const VERSION = process.env.ROMCLOUD_VERSION || JSON.parse(fs.readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version;
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

// Sonde de connexion des applications (au démarrage, et pour repasser en ligne) : publique et sans
// lecture de la base, pour répondre vite.
api.get('/ping', (req, res) => {
  res.set('Cache-Control', 'no-store');
  res.json({ ok: true, version: VERSION });
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

/**
 * Niveau d'une route : profil du joueur (/account : la clé des applications suffit, même pour
 * enregistrer) ; utilisateurs et journaux (/users, /logs : administration, même en lecture).
 */
export function routeMethod(method, routePath) {
  if (routePath.startsWith('/account/')) return 'GET';
  if (routePath.startsWith('/users') || routePath.startsWith('/logs')) return 'POST';
  return method;
}

api.use((req, res, next) => {
  const auth = req.get('authorization') || '';
  const given = auth.startsWith('Bearer ') ? auth.slice(7) : req.get('x-api-key') || req.query.key;
  const result = access(routeMethod(req.method, req.path), given, config);
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
// Plateforme de l'application (android, androidtv, windows) : seuls les systèmes qui lui sont
// proposés sont listés et cherchés ; sans plateforme (administration), tous.
const platformOf = (req) => String(req.get('x-romcloud-platform') || req.query.platform || '').toLowerCase();

api.get('/systems', (req, res) => res.json(listSystems({ platform: platformOf(req) })));

api.post('/systems', (req, res) => {
  const system = createSystem(req.body || {});
  if (system.folderGames) scanSystem(system.id);
  res.status(201).json(system);
});

api.get('/systems/:id', (req, res) => res.json(requireSystem(req.params.id)));

api.put('/systems/:id', (req, res) => {
  const before = requireSystem(req.params.id);
  const system = updateSystem(req.params.id, req.body || {});
  // Jeux en dossiers activés ou coupés : fiches refaites aussitôt (dossiers <-> fichiers).
  if (system.folderGames !== before.folderGames) scanSystem(system.id);
  res.json(system);
});

// Émulateurs proposés par chaque application pour ce système, dans leur ordre par défaut
// ({ windows, android, androidtv } : [{ id, name, kind }]), pour en choisir l'ordre.
api.get('/systems/:id/emulators', (req, res) => res.json(emulatorCandidates(requireSystem(req.params.id))));

api.delete('/systems/:id', (req, res) => {
  deleteSystem(req.params.id, { deleteFiles: req.query.deleteFiles === '1' });
  res.status(204).end();
});

api.get('/systems/:id/image', async (req, res) => {
  const file = systemImagePath(req.params.id);
  if (!file) throw new HttpError(404, 'errors.noSystemImage');
  // Le nom de fichier change à chaque envoi : on peut mettre en cache longtemps.
  await sendImage(res, file, req.query.size, path.join(path.dirname(file), VARIANTS_DIR));
});

/**
 * Image envoyée aux applications : version réduite en WebP si une taille est demandée (?size=thumb
 * ou medium, media-variants.js), sinon l'original. Adresses versionnées : mise en cache longue.
 */
async function sendImage(res, file, size, variantsDir) {
  const variant = size ? await mediaVariant(file, String(size), variantsDir) : null;
  res.sendFile(variant || file, { maxAge: '30d' });
}

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

// Licences des jeux en bloc (« zrif » : PS Vita, « rap » : PS3) : liste TSV (format NoPayStation)
// envoyée en texte brut ; « ?replace=1 » remplace aussi les licences déjà saisies.
api.post(
  '/systems/:id/licenses/:kind/import',
  express.text({ type: () => true, limit: '64mb' }),
  (req, res) => {
    const text = typeof req.body === 'string' ? req.body : '';
    res.json(importLicenseList(req.params.id, req.params.kind, text, { replace: req.query.replace === '1' }));
  },
);

// Suppression de plusieurs jeux d'un système ({ ids }) : fichiers, parties et images compris.
api.post('/systems/:id/games/delete', (req, res) => {
  res.json(deleteGamesOfSystem(req.params.id, (req.body && req.body.ids) || []));
});

// ---- BIOS ----
// { cores, coreInfoAvailable, files: BIOS présents, expected: BIOS attendus par les cœurs RetroArch }
// ?catalog=0 : fichiers présents seulement (utilisé par les applications).
api.get(
  '/systems/:id/bios',
  h(async (req, res) => res.json(await systemBios(req.params.id, { catalog: req.query.catalog !== '0', language: requestLanguage(req) }))),
);

// Dernière version d'un fichier attendu récupérée sur sa source Internet ({ path }).
api.post(
  '/systems/:id/bios/fetch',
  h(async (req, res) => {
    const fetched = await fetchBiosFromSource(req.params.id, req.body?.path);
    res.json({ fetched, ...(await systemBios(req.params.id, { language: requestLanguage(req) })) });
  }),
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
      res.status(201).json({ saved, ...(await systemBios(req.params.id, { language: requestLanguage(req) })) });
    } finally {
      for (const f of files) fs.rmSync(f.path, { force: true });
    }
  }),
);

// Import groupé (champ « files », et « paths » : chemin de chaque fichier dans le dossier choisi) :
// chaque BIOS rangé dans les systèmes qui l'attendent ; rapport { stored, already, mismatch, unknown }.
api.post(
  '/bios/import',
  biosUpload.array('files'),
  h(async (req, res) => {
    const files = req.files || [];
    try {
      if (!files.length) throw new HttpError(400, 'errors.noFiles');
      const paths = [req.body?.paths ?? []].flat();
      const uploads = files.map((f, i) => ({
        tempPath: f.path,
        originalName: Buffer.from(f.originalname, 'latin1').toString('utf8'),
        relativePath: paths[i] || null,
      }));
      res.json(await importBiosPack(uploads));
    } finally {
      for (const f of files) fs.rmSync(f.path, { force: true });
    }
  }),
);

api.get('/bios/:id/file', (req, res, next) => {
  const row = requireBios(req.params.id);
  logDownload(req, res, { kind: 'bios', itemId: row.id, systemId: row.system_id, label: row.path, fileSize: row.size });
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
  logDownload(req, res, { kind: 'apk', itemId: row.id, label: `${row.package_name} ${row.version_name || ''}`.trim(), fileSize: row.size });
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
api.get('/search', (req, res) => res.json(searchGames(req.query.q, { limit: req.query.limit, platform: platformOf(req) })));

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

api.get('/games/:id', (req, res) => res.json(gameWithParts(req.params.id)));

api.put('/games/:id', (req, res) => {
  updateGame(req.params.id, req.body || {});
  res.json(gameWithParts(req.params.id));
});

// Rattachement d'un fichier à un jeu : { parentId, kind: disc | update | dlc } ; { parentId: null } :
// jeu à part entière ; { auto: true } : regroupement automatique. Renvoie le jeu qui contient le fichier.
api.put('/games/:id/group', (req, res) => {
  setGroup(req.params.id, req.body || {});
  const row = requireGameRow(req.params.id);
  res.json(gameWithParts(row.parent_id ?? row.id));
});

api.delete('/games/:id', (req, res) => {
  deleteGame(req.params.id, { deleteFile: req.query.keepFile !== '1' });
  res.status(204).end();
});

// Jeu en dossier : fichiers notés dans le journal des téléchargements à partir de cette taille.
const FOLDER_LOG_MIN_BYTES = 8 * 1024 * 1024;

// Téléchargement du fichier ROM (gère les requêtes Range pour la reprise). Jeu en dossier (PC) :
// un fichier de son dossier (?path=Bin/Game.exe, voir /games/:id/files).
api.get('/games/:id/file', (req, res, next) => {
  const row = requireGameRow(req.params.id);
  if (row.is_folder) {
    if (req.query.path === undefined) throw new HttpError(400, 'errors.folderGamePath');
    const dir = gameFilePath(row);
    const rel = String(req.query.path).replace(/\\/g, '/');
    let file = fileInFolder(dir, rel);
    let name = path.posix.basename(rel);
    // Jeu compressé (folder-pack.js) : archive « X.zip » du fichier, envoyée telle quelle avec
    // ?packed=1 (applications récentes, qui la décompressent) ; sinon décompressée à la volée
    // (anciennes applications, sans reprise du téléchargement).
    const packed = readPackManifest(dir)[rel];
    if (packed && !fs.existsSync(file) && fs.existsSync(`${file}.zip`)) {
      file = `${file}.zip`;
      if (req.query.packed !== '1') {
        const entry = readZipEntries(file)[0];
        if (!entry) throw new HttpError(404, 'errors.fileNotFound');
        if (packed.size >= FOLDER_LOG_MIN_BYTES) {
          logDownload(req, res, { kind: 'game', itemId: row.id, systemId: row.system_id, label: `${row.file_name}/${rel}`, fileSize: packed.size });
        }
        res.set({ 'Content-Type': 'application/octet-stream', 'Content-Length': String(packed.size), 'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(name)}` });
        zipEntryStream(file, entry).on('error', () => res.destroy()).pipe(res);
        return;
      }
      name += '.zip';
    }
    const size = fs.statSync(file, { throwIfNoEntry: false })?.size ?? 0;
    // Journal : seulement les gros fichiers (un jeu en compte parfois des milliers).
    if (size >= FOLDER_LOG_MIN_BYTES) {
      logDownload(req, res, { kind: 'game', itemId: row.id, systemId: row.system_id, label: `${row.file_name}/${rel}`, fileSize: size });
    }
    return res.download(file, name, { dotfiles: 'allow' }, (err) => {
      if (err && !res.headersSent) next(new HttpError(404, 'errors.fileNotFound'));
    });
  }
  logDownload(req, res, { kind: 'game', itemId: row.id, systemId: row.system_id, label: row.file_name, fileSize: row.size });
  res.download(gameFilePath(row), row.file_name, { dotfiles: 'allow' }, (err) => {
    if (err && !res.headersSent) next(new HttpError(404, 'errors.fileNotFound'));
  });
});

// Jeu en dossier (PC) : ses fichiers ({ path, size }), à télécharger un par un, et ses exécutables.
api.get('/games/:id/files', (req, res) => res.json(gameFolderFiles(requireGameRow(req.params.id))));

// Jeu PC : compression fichier par fichier ({ mode: "zip" }) ou retour en clair ({ mode: "flat" }),
// en tâche de fond (voir /jobs).
api.post('/games/:id/pack', (req, res) => {
  const job = queueFolderPack(req.params.id, req.body?.mode);
  res.status(202).json({ job });
});

const MEDIA_TYPES = ['boxart', ...SCREENSHOT_TYPES];

api.get('/games/:id/media/:type', async (req, res) => {
  const { type } = req.params;
  if (!MEDIA_TYPES.includes(type)) throw new HttpError(404, 'errors.unknownMediaType');
  const row = requireGameRow(req.params.id);
  if (!row[type]) throw new HttpError(404, 'errors.noMedia');
  // Adresse versionnée par les applications (?v=updatedAt) : mise en cache longue, ce qui garde
  // les jaquettes des jeux déjà vus affichables hors ligne.
  const dir = gameMediaDir(row.id);
  await sendImage(res, path.join(dir, row[type]), req.query.size, path.join(dir, VARIANTS_DIR));
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

// Suppression d'une image (jaquette, capture : les suivantes remontent d'une place).
api.delete('/games/:id/media/:type', (req, res) => {
  if (!MEDIA_TYPES.includes(req.params.type)) throw new HttpError(404, 'errors.unknownMediaType');
  res.json(deleteGameMedia(req.params.id, req.params.type));
});

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

// ---- Profil du joueur (session : en-tête X-RomCloud-Session) ----
const sessionToken = (req) => req.get('x-romcloud-session') || '';
const userAuth = (req) => accounts.authenticate(sessionToken(req), accounts.clientInfo(req));

/** Téléchargement noté dans le journal (profil connecté s'il y en a un, appareil, octets envoyés). */
function logDownload(req, res, item) {
  try {
    trackDownload(req, res, item, userAuth(req), accounts.clientInfo(req));
  } catch (err) {
    console.warn(`[downloads] ${err.message}`);
  }
}

/** Route réservée à un joueur connecté : req.auth = { user, sessionId }. */
function signedIn(req, res, next) {
  req.auth = userAuth(req);
  if (!req.auth) return res.status(401).json({ error: token('errors.signedOut'), code: 'errors.signedOut' });
  next();
}

api.post('/account/register', (req, res) => {
  const b = req.body || {};
  res.status(201).json(accounts.register(b.username, b.password, accounts.clientInfo(req, b)));
});

api.post('/account/login', (req, res) => {
  const b = req.body || {};
  res.json(accounts.login(b.username, b.password, accounts.clientInfo(req, b)));
});

api.post('/account/logout', signedIn, (req, res) => {
  accounts.logout(req.auth, accounts.clientInfo(req));
  res.status(204).end();
});

api.get('/account/me', signedIn, (req, res) => {
  const playtime = accounts.listPlaytime(req.auth.user.id);
  res.json({
    user: req.auth.user,
    avatars: accounts.AVATARS,
    playSeconds: playtime.reduce((n, p) => n + p.seconds, 0),
    gamesPlayed: playtime.length,
  });
});

api.put('/account/avatar', signedIn, (req, res) => res.json({ user: accounts.setAvatar(req.auth, req.body?.avatar ?? null) }));

api.post('/account/password', signedIn, (req, res) => {
  accounts.changePassword(req.auth, req.body?.current, req.body?.password);
  res.status(204).end();
});

// Connexion d'une TV : elle affiche un QR code ; un téléphone connecté le scanne et valide.
api.post('/account/pair', (req, res) => res.status(201).json(accounts.createPair(accounts.clientInfo(req, req.body || {}))));
api.get('/account/pair/:code', (req, res) => {
  if (req.query.secret !== undefined) return res.json(accounts.pollPair(req.params.code, req.query.secret));
  res.json(accounts.pairInfo(req.params.code));
});
api.post('/account/pair/:code/approve', signedIn, (req, res) => {
  res.json(accounts.approvePair(req.params.code, req.auth, accounts.clientInfo(req)));
});

// Diffusion d'un jeu sur une TV du même profil (la TV s'annonce tant que l'application est ouverte).
// ---- Jeu à plusieurs par Internet (play.js) : présence des appareils connectés à un profil ----
api.put('/account/play/presence', signedIn, (req, res) => res.json(play.announce(req.auth, req.body || {}, accounts.clientInfo(req))));
api.delete('/account/play/presence/:deviceId', signedIn, (req, res) => {
  play.withdraw(req.auth, req.params.deviceId);
  res.status(204).end();
});

// Lobbies du multijoueur (lobbies.js) : appareil désigné par « deviceId » (corps ou paramètre).
api.get('/account/play/lobbies', signedIn, (req, res) => res.json(lobbies.list()));
api.post('/account/play/lobbies', signedIn, (req, res) => res.status(201).json(lobbies.create(req.auth, req.body || {})));
api.post('/account/play/lobbies/:id/join', signedIn, (req, res) => res.json(lobbies.join(req.auth, req.params.id, req.body || {})));
api.post('/account/play/lobbies/:id/leave', signedIn, (req, res) => {
  lobbies.leave(req.auth, req.params.id, req.body || {});
  res.status(204).end();
});
api.put('/account/play/lobbies/:id', signedIn, (req, res) => res.json(lobbies.update(req.auth, req.params.id, req.body || {})));
api.put('/account/play/lobbies/:id/status', signedIn, (req, res) => res.json(lobbies.setStatus(req.auth, req.params.id, req.body || {})));
api.delete('/account/play/lobbies/:id', signedIn, (req, res) => {
  lobbies.dissolve(req.auth, req.params.id, req.query.deviceId);
  res.status(204).end();
});

/**
 * Relais (requête « Upgrade », hors d'Express) : même contrôle que /api/account (clé des
 * applications, puis session du joueur). Renvoie le profil connecté, ou null.
 */
export function relayAuth(req) {
  const header = (name) => {
    const v = req.headers[name];
    return Array.isArray(v) ? v[0] : v || '';
  };
  const auth = header('authorization');
  const url = new URL(req.url, 'http://relay');
  const given = auth.startsWith('Bearer ') ? auth.slice(7) : header('x-api-key') || url.searchParams.get('key');
  if (access(routeMethod('GET', '/account/play'), given, config) !== 'ok') return null;
  return accounts.authenticate(header('x-romcloud-session'), {
    device: header('x-romcloud-device').slice(0, 100),
    platform: header('x-romcloud-platform').slice(0, 30),
    appVersion: header('x-romcloud-version').slice(0, 40),
    ip: String(req.socket?.remoteAddress || '').slice(0, 64),
    userAgent: header('user-agent').slice(0, 200),
  });
}

api.put('/account/stream/receiver', signedIn, (req, res) => {
  res.json(accounts.announceReceiver(req.auth, req.body || {}, accounts.clientInfo(req)));
});
api.delete('/account/stream/receiver', signedIn, (req, res) => {
  accounts.withdrawReceiver(req.auth);
  res.status(204).end();
});
api.get('/account/stream/receivers', signedIn, (req, res) => res.json(accounts.listReceivers(req.auth)));

api.get('/account/playtime', signedIn, (req, res) => res.json(accounts.listPlaytime(req.auth.user.id)));
api.post('/account/playtime', signedIn, (req, res) => {
  res.json(accounts.addPlaytime(req.auth.user.id, req.body?.gameId, req.body?.seconds, accounts.clientInfo(req), req.body?.at));
});
// Statistiques du profil : temps de jeu, jeux téléchargés par appareil, jeux les plus joués, dernières parties.
api.get('/account/stats', signedIn, (req, res) => res.json(profileStats(req.auth.user.id)));

api.get('/account/saves', signedIn, (req, res) => res.json(accounts.listSaves(req.auth.user.id, req.query.gameId)));
/**
 * Envoi compressé par l'application (Content-Encoding: gzip) : gardé compressé. L'en-tête est mis de
 * côté pour que la lecture du corps ne le décompresse pas.
 */
function keepCompressed(req, res, next) {
  req.uploadEncoding = req.headers['content-encoding'];
  delete req.headers['content-encoding'];
  next();
}

/**
 * Fichier de sauvegarde ou d'état : compressé par l'application qui l'a envoyé, il est renvoyé tel
 * quel aux applications qui acceptent gzip (Content-Encoding, décompressé chez elles), décompressé
 * ici pour les versions précédentes qui ne l'annoncent pas.
 */
function sendStored(req, res, next, file, encoding) {
  res.type('application/octet-stream');
  if (encoding !== 'gzip') return res.sendFile(file);
  res.vary('Accept-Encoding');
  if (/\bgzip\b/.test(req.get('accept-encoding') || '')) {
    res.set('Content-Encoding', 'gzip');
    return res.sendFile(file);
  }
  fs.createReadStream(file).on('error', next).pipe(zlib.createGunzip()).on('error', next).pipe(res);
}

api.get('/account/saves/:gameId/:core/:kind', signedIn, (req, res, next) => {
  const { file, encoding, save } = accounts.getSave(req.auth.user.id, req.params.gameId, req.params.core, req.params.kind);
  res.set('X-Saved-At', save.savedAt);
  sendStored(req, res, next, file, encoding);
});
api.put(
  '/account/saves/:gameId/:core/:kind',
  signedIn,
  keepCompressed,
  express.raw({ type: () => true, limit: '1024mb' }),
  h(async (req, res) => {
    const { gameId, core, kind } = req.params;
    const stored = { encoding: accounts.uploadEncoding(req.uploadEncoding), size: req.get('x-uncompressed-size') };
    res.json(await accounts.putSave(req.auth.user.id, gameId, core, kind, req.body, req.get('x-saved-at'), accounts.clientInfo(req), stored));
  }),
);
api.delete('/account/saves/:gameId/:core/:kind', signedIn, (req, res) => {
  accounts.deleteSave(req.auth.user.id, req.params.gameId, req.params.core, req.params.kind);
  res.status(204).end();
});

// Historique des états de sauvegarde du profil (plusieurs par jeu, par appareil, avec miniature).
api.get('/account/states', signedIn, (req, res) => res.json(accounts.listStates(req.auth.user.id, req.query.gameId)));
api.get('/account/states/:id', signedIn, (req, res, next) => {
  const { file, encoding, state } = accounts.getState(req.auth.user.id, req.params.id);
  res.set('X-Created-At', state.createdAt);
  sendStored(req, res, next, file, encoding);
});
api.get('/account/states/:id/thumbnail', signedIn, (req, res) => {
  const { file, type } = accounts.getStateThumbnail(req.auth.user.id, req.params.id);
  res.type(type).sendFile(file);
});
api.put(
  '/account/states/:gameId/:core/:id',
  signedIn,
  keepCompressed,
  express.raw({ type: () => true, limit: '1024mb' }),
  h(async (req, res) => {
    const { gameId, core, id } = req.params;
    const meta = {
      createdAt: req.get('x-created-at'),
      pinned: req.get('x-pinned') === '1',
      encoding: accounts.uploadEncoding(req.uploadEncoding),
      size: req.get('x-uncompressed-size'),
    };
    const state = await accounts.putState(req.auth.user.id, gameId, core, id, req.body, meta, accounts.clientInfo(req));
    if (state) res.json(state);
    else res.status(204).end(); // plus ancien que les états gardés : aussitôt retiré
  }),
);
api.put('/account/states/:id/thumbnail', signedIn, express.raw({ type: () => true, limit: '4mb' }), (req, res) => {
  res.json(accounts.putStateThumbnail(req.auth.user.id, req.params.id, req.body, req.get('content-type')));
});
api.patch('/account/states/:id', signedIn, (req, res) => {
  const state = accounts.pinState(req.auth.user.id, req.params.id, Boolean(req.body?.pinned));
  if (state) res.json(state);
  else res.status(204).end();
});
api.delete('/account/states/:id', signedIn, (req, res) => {
  accounts.deleteState(req.auth.user.id, req.params.id);
  res.status(204).end();
});

// Erreur rencontrée par une application (avec ou sans joueur connecté).
api.post('/account/errors', (req, res) => {
  accounts.logError(req.body, userAuth(req), accounts.clientInfo(req, req.body || {}));
  res.status(204).end();
});

// ---- Administration des utilisateurs et journaux ----
api.get('/users', (req, res) => res.json(accounts.adminListUsers()));
api.post('/users', (req, res) => res.status(201).json(accounts.createUser(req.body?.username, req.body?.password)));
api.get('/users/:id', (req, res) => res.json(accounts.adminUserDetail(req.params.id)));
api.put('/users/:id', (req, res) => res.json(accounts.adminUpdateUser(req.params.id, req.body || {})));
api.delete('/users/:id', (req, res) => {
  accounts.adminDeleteUser(req.params.id);
  res.status(204).end();
});
api.delete('/users/:id/sessions/:sessionId', (req, res) => {
  accounts.adminRevokeSession(req.params.id, req.params.sessionId);
  res.status(204).end();
});
api.get('/logs/logins', (req, res) => res.json(accounts.adminLoginLog(req.query.limit)));
api.get('/logs/errors', (req, res) => res.json(accounts.adminErrorLog(req.query.limit)));
// Journal des téléchargements (ROM, BIOS, APK) et statistiques ; filtres : kind, userId (« anonymous »), device, days.
api.get('/logs/downloads', (req, res) => res.json(listDownloads(req.query)));
api.get('/logs/downloads/stats', (req, res) => res.json(downloadStats(req.query)));
// Espace disque utilisé, en arborescence (ROMs par système, sauvegardes par profil…) ; ?refresh=1 : recalculé.
api.get('/logs/disk', h(async (req, res) => res.json(await diskUsage({ refresh: req.query.refresh === '1' }))));
api.delete('/logs/downloads', (req, res) => {
  clearDownloads();
  res.status(204).end();
});
api.delete('/logs/errors', (req, res) => {
  accounts.adminClearErrors();
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
