// Miroir des émulateurs externes : les applications (Windows, Android, TV) ne téléchargent plus les
// émulateurs sur GitHub ou Forgejo mais sur le serveur, qui en garde une copie. Un émulateur retiré
// d'Internet (dépôt supprimé, version effacée) reste ainsi installable.
//  - Fichiers dans data/emulators/<source>/<version>/<fichier>, plusieurs versions par source ;
//  - liste des versions (format des Releases GitHub) : celles publiées, plus celles gardées ici et
//    disparues d'Internet ; Internet injoignable : celles gardées ici ;
//  - un fichier est récupéré par le serveur à la demande d'une application (« prepare »), qui suit la
//    progression puis le télécharge depuis le serveur (une mise à jour n'est proposée qu'une fois là) ;
//  - toutes les EMULATOR_REFRESH_HOURS heures, la nouvelle version des fichiers déjà gardés (même
//    variante : même nom au numéro de version près) est récupérée d'avance.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { config } from './config.js';
import { db } from './db.js';
import { HttpError } from './http-error.js';

db.exec(`
  -- Fichiers des émulateurs gardés par le serveur (data/emulators/<source>/<tag>/<name>).
  CREATE TABLE IF NOT EXISTS emulator_files (
    source        TEXT NOT NULL,
    tag           TEXT NOT NULL,
    name          TEXT NOT NULL,
    size          INTEGER NOT NULL,
    sha256        TEXT,
    release_name  TEXT,
    prerelease    INTEGER NOT NULL DEFAULT 0,
    published_at  TEXT,
    stored_at     TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (source, tag, name)
  );
`);

const github = (repo) => `https://api.github.com/repos/${repo}/releases?per_page=15`;
const EDEN = 'https://git.eden-emu.dev/api/v1/repos/eden-emu/eden/releases?limit=10';

/**
 * Sources des émulateurs : [releases] liste des versions (API GitHub, ou Forgejo, même format),
 * [prereleases] : préversions proposées (Lichii et Fritchii ne publient qu'elles, KytyPS5 plusieurs par jour).
 * [android] : applications Android publiées par la source, envoyées aux applications (« /emulators/android ») :
 * [package] paquet des modèles d'émulateurs Daijishou, [asset] nom de l'APK dans la Release (expression
 * régulière ; variantes « -sdkNN » : la plus récente acceptée par l'appareil), [name] nom affiché (celui
 * de la source sinon), [playStore] faux pour une application absente du Google Play Store (pas de repli
 * vers le Play Store quand le miroir ne la trouve pas). Un émulateur du miroir passe devant les autres
 * émulateurs externes du système (playersFor, extra-cores.js).
 * [windows] : archive Windows installée par défaut par l'application (emulator_updates.rs, managed.rs :
 * Eden PGO, Lichii et Orchid standard…), expressions régulières du nom ; avec [android], fichiers
 * récupérés par « Récupérer les dernières versions » ([fetchAllLatest]).
 */
export const SOURCES = {
  // Windows (RomCloud pour Windows) et Android (Eden : même dépôt).
  eden: {
    name: 'Eden', releases: EDEN, platforms: ['windows', 'android'],
    windows: [String.raw`Eden-Windows-.*-amd64-clang-pgo\.zip`],
    android: [
      { package: 'dev.eden.eden_emulator', asset: String.raw`Eden-Android-.*-standard\.apk` },
      { package: 'dev.legacy.eden_emulator', name: 'Eden (Legacy)', asset: String.raw`Eden-Android-.*-legacy\.apk` },
      { package: 'com.miHoYo.Yuanshen', name: 'Eden (Optimized)', asset: String.raw`Eden-Android-.*-optimized\.apk` },
    ],
  },
  lichii: { name: 'Lichii', releases: github('zortos293/Lichii'), prereleases: true, platforms: ['windows'], windows: [String.raw`lichii-(?:.+-)?windows-x86_64\.zip`] },
  orchid: { name: 'Orchid', releases: github('MythosOfs/Orchid'), prereleases: true, platforms: ['windows'], windows: [String.raw`orchid-(?:.+-)?windows-x86_64\.zip`] },
  fritchii: { name: 'Fritchii', releases: github('Frizti/Fritchii'), prereleases: true, platforms: ['windows'], windows: [String.raw`Fritchii-.+-Windows-x64\.zip`] },
  rpcs3: { name: 'RPCS3', releases: github('RPCS3/rpcs3-binaries-win'), platforms: ['windows'], windows: [String.raw`.+_win64_msvc\.7z`] },
  shadps4: { name: 'shadPS4', releases: github('shadps4-emu/shadPS4'), platforms: ['windows'], windows: [String.raw`.*win64-sdl.*\.zip`] },
  kytyps5: { name: 'KytyPS5', releases: github('KytyPS5/KytyPS5'), prereleases: true, platforms: ['windows'], windows: [String.raw`.+-windows-x64\.zip`] },
  cemu: { name: 'Cemu', releases: github('cemu-project/Cemu'), platforms: ['windows'], windows: [String.raw`.+-windows-x64\.zip`] },
  // Android, systèmes sans moteur intégré seulement (3DS, DS, Game Boy : cœurs Citra, melonDS, mGBA…).
  // Jeux PC (Wine / Proton) : RomCloud PC (android/pc, publié avec RomCloud), Winlator Cmod, Winlator, GameNative.
  'romcloud-pc': {
    name: 'RomCloud PC', releases: github('biliboobrian/romCloud'), platforms: ['android'],
    android: [{ package: 'com.romcloud.wine', asset: String.raw`RomCloud-PC-.*\.apk`, playStore: false }],
  },
  'winlator-cmod': {
    name: 'Winlator Cmod', releases: github('coffincolors/winlator'), platforms: ['android'],
    android: [{ package: 'com.winlator.cmod', asset: String.raw`Winlator-Cmod-.*\.apk`, playStore: false }],
  },
  winlator: {
    name: 'Winlator', releases: github('brunodev85/winlator'), platforms: ['android'],
    android: [{ package: 'com.winlator', asset: String.raw`Winlator_.*\.apk`, playStore: false }],
  },
  gamenative: {
    name: 'GameNative', releases: github('utkarshdalal/GameNative'), platforms: ['android'],
    // Version standard (pas « -legacy-xr », casques Quest).
    android: [{ package: 'app.gamenative', asset: String.raw`gamenative-v[\d.]+\.apk`, playStore: false }],
  },
  // Switch : Eden Duo (Eden avec deux écrans).
  'eden-duo': {
    name: 'Eden Duo', releases: github('igawa6/eden-duo'), platforms: ['android'],
    android: [{ package: 'dev.igawa6.edenduo', asset: String.raw`EdenDuo-.*\.apk`, playStore: false }],
  },
  // Naomi et Atomiswave.
  flycast: {
    name: 'Flycast', releases: github('flyinghead/flycast'), platforms: ['android'],
    android: [{ package: 'com.flycast.emulator', asset: String.raw`flycast-.*\.apk` }],
  },
  'vita3k-android': {
    name: 'Vita3K', releases: github('Vita3K/Vita3K-Android'), platforms: ['android'],
    android: [{ package: 'org.vita3k.emulator', asset: String.raw`vita3k-android-.*\.apk` }],
  },
  'cemu-android': {
    name: 'Cemu', releases: github('SSimco/Cemu'), platforms: ['android'],
    android: [{ package: 'info.cemu.cemu', asset: String.raw`Cemu-.*\.apk` }],
  },
  // PS3 : aPS3e, ARMSX3 (une variante par version d'Android, « -sdk30 », « -sdk33 »… ; pas « legacy »).
  aps3e: {
    name: 'aPS3e', releases: github('aenu1/aps3e'), platforms: ['android'],
    android: [{ package: 'aenu.aps3e', asset: String.raw`[\w.-]+\.apk` }],
  },
  armsx3: {
    name: 'ARMSX3', releases: github('ARMSX2/ARMSX3'), platforms: ['android'],
    android: [{ package: 'com.armsx3', asset: String.raw`ARMSX3-.*-a\d+-armv8\.2-sdk\d+\.apk`, playStore: false }],
  },
  // Xbox : X1 Box (xemu), hakuX ; Xbox et Xbox 360 : Xenra (édition Green, la Gold n'est plus publiée) ; Xbox 360 : XenDroid.
  x1box: {
    name: 'X1 Box', releases: github('izzy2lost/xemu'), platforms: ['android'],
    android: [{ package: 'com.izzy2lost.x1box', asset: String.raw`app-release\.apk`, playStore: false }],
  },
  hakux: {
    name: 'hakuX', releases: github('rfandango/hakuX'), platforms: ['android'],
    android: [{ package: 'com.rfandango.haku_x', asset: String.raw`hakuX-.*\.apk`, playStore: false }],
  },
  xenra: {
    name: 'Xenra', releases: github('Yebot32/xenra'), platforms: ['android'],
    android: [{ package: 'Ali.Xanite.green', name: 'Xenra (Green)', asset: String.raw`Xenra-.*-green-release\.apk`, playStore: false }],
  },
  xendroid: {
    name: 'XenDroid', releases: github('rfandango/XenDroid'), platforms: ['android'],
    android: [{ package: 'xendroid.compose', asset: String.raw`XenDroid_Release_.*\.apk`, playStore: false }],
  },
  // Game & Watch.
  mame4droid: {
    name: 'MAME4droid', releases: github('seleuco/MAME4droid-Current'), platforms: ['android'],
    android: [{ package: 'com.seleuco.mame4d2024', asset: String.raw`MAME4droid.*\.apk` }],
  },
};

/**
 * Applications Android du miroir : [{ package, name, source, host, asset, playStore }] (applications
 * Android : quelle version télécharger, et si le Play Store peut servir de repli).
 */
export function androidPackages() {
  return Object.entries(SOURCES).flatMap(([id, source]) =>
    (source.android || []).map((a) => ({
      package: a.package,
      name: a.name || source.name,
      source: id,
      host: hostOf(id),
      asset: a.asset,
      playStore: a.playStore !== false,
    })),
  );
}

const ANDROID_PACKAGES = new Set(Object.values(SOURCES).flatMap((s) => (s.android || []).map((a) => a.package)));

/** Paquet Android publié par une source du miroir. */
export const isMirroredPackage = (pkg) => ANDROID_PACKAGES.has(pkg);

export const mirrorDir = () => path.join(config.dataDir, 'emulators');

export function requireSource(id) {
  const source = SOURCES[id];
  if (!source) throw new HttpError(404, 'errors.unknownEmulator', { id });
  return source;
}

/** Nom de version ou de fichier utilisable comme nom de dossier / fichier (pas de chemin). */
export function safePart(value) {
  const s = String(value ?? '');
  return s.length > 0 && s.length <= 200 && !/[/\\\0:*?"<>|]/.test(s) && !s.startsWith('.') && s.trim() === s;
}

const filePath = (source, tag, name) => path.join(mirrorDir(), source, tag, name);

/** Site de la source (« github.com », « git.eden-emu.dev »). */
export const hostOf = (source) => new URL(SOURCES[source].releases).host.replace(/^api\./, '');

// ---- Versions publiées sur Internet ----

// Listes des versions de chaque source, gardées 15 min ; source injoignable : nouvel essai dans 2 min.
const upstreamCache = new Map();
const UPSTREAM_TTL = 15 * 60_000;
const RETRY_TTL = 2 * 60_000;
let fetchImpl = (...args) => fetch(...args);

/** Remplace fetch (tests). */
export function setFetch(fn) {
  fetchImpl = fn || ((...args) => fetch(...args));
  upstreamCache.clear();
}

function headers(url) {
  const h = { 'User-Agent': 'RomCloud', Accept: 'application/json' };
  // Jeton GitHub facultatif : 5000 requêtes par heure au lieu de 60.
  if (config.githubToken && new URL(url).host === 'api.github.com') h.Authorization = `Bearer ${config.githubToken}`;
  return h;
}

/** Release de GitHub ou Forgejo -> { tag, name, prerelease, publishedAt, assets: [{ name, size, url }] }. */
function normalizeRelease(r) {
  return {
    tag: String(r.tag_name || ''),
    name: r.name || r.tag_name || '',
    prerelease: Boolean(r.prerelease),
    draft: Boolean(r.draft),
    publishedAt: r.published_at || r.created_at || null,
    assets: (r.assets || [])
      .filter((a) => a?.name && a.browser_download_url)
      .map((a) => ({ name: String(a.name), size: Number(a.size) || 0, url: String(a.browser_download_url) })),
  };
}

/**
 * Versions publiées de la source, les plus récentes d'abord (sans brouillons, préversions seulement
 * si la source en propose), ou null si la source est injoignable (dernière liste connue sinon).
 */
export async function upstreamReleases(sourceId, { force = false } = {}) {
  const source = requireSource(sourceId);
  const cached = upstreamCache.get(sourceId);
  if (!force && cached && Date.now() < cached.until) return cached.value;
  try {
    const res = await fetchImpl(source.releases, { headers: headers(source.releases), signal: AbortSignal.timeout(8_000) });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const body = await res.json();
    const value = (Array.isArray(body) ? body : [body])
      .map(normalizeRelease)
      .filter((r) => r.tag && safePart(r.tag) && !r.draft && (source.prereleases || !r.prerelease))
      .sort((a, b) => String(b.publishedAt || '').localeCompare(String(a.publishedAt || '')));
    upstreamCache.set(sourceId, { until: Date.now() + UPSTREAM_TTL, value, error: null });
    return value;
  } catch (err) {
    const value = cached?.value ?? null;
    upstreamCache.set(sourceId, { until: Date.now() + RETRY_TTL, value, error: err.message });
    return value;
  }
}

// ---- Fichiers gardés par le serveur ----

const storedRows = (sourceId) =>
  db.prepare('SELECT * FROM emulator_files WHERE source = ? ORDER BY published_at DESC, stored_at DESC').all(sourceId);

const storedRow = (sourceId, tag, name) =>
  db.prepare('SELECT * FROM emulator_files WHERE source = ? AND tag = ? AND name = ?').get(sourceId, tag, name);

const downloadPath = (sourceId, tag, name) =>
  `/api/emulators/${encodeURIComponent(sourceId)}/files/${encodeURIComponent(tag)}/${encodeURIComponent(name)}`;

// Récupérations en cours ou échouées récemment : « source/tag/name » -> { state, received, total, error, promise }.
const jobs = new Map();
const jobKey = (sourceId, tag, name) => `${sourceId}/${tag}/${name}`;
// Échec gardé 30 s : vu par les applications qui suivent l'état (sans relancer aussitôt), puis nouvel essai possible.
const ERROR_KEEP_MS = 30_000;

/** État d'un fichier : stored (sur le serveur), fetching (récupération en cours), error, remote (seulement sur Internet). */
export function fileState(sourceId, tag, name) {
  const row = storedRow(sourceId, tag, name);
  if (row && fs.existsSync(filePath(sourceId, tag, name))) return { state: 'stored', received: row.size, total: row.size };
  const job = jobs.get(jobKey(sourceId, tag, name));
  if (job) return { state: job.state, received: job.received, total: job.total, ...(job.error ? { error: job.error } : {}) };
  return { state: 'remote', received: 0, total: 0 };
}

/** Fichier d'une version, au format des Releases GitHub, avec son adresse sur le serveur et son état. */
function assetJson(sourceId, tag, asset) {
  const state = fileState(sourceId, tag, asset.name);
  return {
    name: asset.name,
    size: state.state === 'stored' ? state.total : asset.size,
    path: downloadPath(sourceId, tag, asset.name),
    ...state,
  };
}

/**
 * Versions de la source pour les applications, les plus récentes d'abord : celles publiées sur
 * Internet (tous leurs fichiers, avec leur état sur le serveur), puis celles qui n'y sont plus
 * mais restent sur le serveur (fichiers gardés seulement). [offline] : Internet injoignable.
 */
export async function listReleases(sourceId, options = {}) {
  requireSource(sourceId);
  const upstream = await upstreamReleases(sourceId, options);
  const stored = storedRows(sourceId).filter((r) => fs.existsSync(filePath(sourceId, r.tag, r.name)));
  const releases = (upstream || []).map((r) => ({ ...r, assets: r.assets.map((a) => assetJson(sourceId, r.tag, a)), mirrorOnly: false }));
  const known = new Set(releases.map((r) => r.tag));
  for (const row of stored) {
    if (known.has(row.tag)) continue;
    let release = releases.find((r) => r.tag === row.tag && r.mirrorOnly);
    if (!release) {
      release = { tag: row.tag, name: row.release_name || row.tag, prerelease: Boolean(row.prerelease), publishedAt: row.published_at, assets: [], mirrorOnly: true };
      releases.push(release);
    }
    release.assets.push(assetJson(sourceId, row.tag, { name: row.name, size: row.size }));
  }
  releases.sort((a, b) => String(b.publishedAt || '').localeCompare(String(a.publishedAt || '')));
  const host = hostOf(sourceId);
  // Format des Releases GitHub (tag_name, assets[].name / size), lu tel quel par les applications.
  return {
    offline: upstream == null,
    releases: releases.map((r) => ({
      tag_name: r.tag,
      name: r.name,
      prerelease: r.prerelease,
      draft: false,
      published_at: r.publishedAt,
      source: host,
      mirror_only: r.mirrorOnly,
      assets: r.assets,
    })),
  };
}

/** Fichier publié sur Internet : { release, asset } ou null. */
async function findUpstream(sourceId, tag, name) {
  const releases = await upstreamReleases(sourceId);
  const release = releases?.find((r) => r.tag === tag);
  const asset = release?.assets.find((a) => a.name === name);
  return asset ? { release, asset } : null;
}

/** Télécharge le fichier dans le miroir (fichier .part renommé à la fin, empreinte SHA-256). */
async function fetchFile(sourceId, release, asset, job) {
  const dir = path.join(mirrorDir(), sourceId, release.tag);
  fs.mkdirSync(dir, { recursive: true });
  const part = path.join(dir, `.${asset.name}.part`);
  try {
    const res = await fetchImpl(asset.url, { headers: { 'User-Agent': 'RomCloud' }, redirect: 'follow', signal: AbortSignal.timeout(3 * 3600_000) });
    if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
    // Forgejo (Eden) : taille 0 dans la Release, celle de la réponse fait foi.
    job.total = Number(res.headers.get('content-length')) || asset.size || 0;
    const hash = crypto.createHash('sha256');
    const body = Readable.fromWeb(res.body);
    body.on('data', (chunk) => {
      job.received += chunk.length;
      hash.update(chunk);
    });
    await pipeline(body, fs.createWriteStream(part));
    const size = fs.statSync(part).size;
    if (job.total && size !== job.total) throw new Error(`taille reçue ${size} au lieu de ${job.total}`);
    fs.renameSync(part, filePath(sourceId, release.tag, asset.name));
    db.prepare(
      `INSERT INTO emulator_files (source, tag, name, size, sha256, release_name, prerelease, published_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(source, tag, name) DO UPDATE SET size = excluded.size, sha256 = excluded.sha256, stored_at = datetime('now')`,
    ).run(sourceId, release.tag, asset.name, size, hash.digest('hex'), release.name || null, release.prerelease ? 1 : 0, release.publishedAt || null);
    console.log(`[emulators] ${sourceId} ${release.tag} : ${asset.name} (${Math.round(size / 1048576)} Mo) gardé`);
  } catch (err) {
    fs.rmSync(part, { force: true });
    throw err;
  }
}

/**
 * Fichier [name] de la version [tag] sur le serveur : déjà là (stored), sinon sa récupération sur
 * Internet est lancée (fetching, une seule à la fois par fichier) ; l'application suit l'état puis
 * le télécharge depuis le serveur.
 */
export async function prepareFile(sourceId, tag, name) {
  requireSource(sourceId);
  if (!safePart(tag) || !safePart(name)) throw new HttpError(400, 'errors.emulatorFileNotFound', { name });
  const state = fileState(sourceId, tag, name);
  if (state.state !== 'remote') return state;
  const found = await findUpstream(sourceId, tag, name);
  if (!found) throw new HttpError(404, 'errors.emulatorFileNotFound', { name });
  const key = jobKey(sourceId, tag, name);
  if (jobs.get(key)?.state === 'fetching') return fileState(sourceId, tag, name);
  const job = { state: 'fetching', received: 0, total: found.asset.size || 0, error: null };
  jobs.set(key, job);
  job.promise = fetchFile(sourceId, found.release, found.asset, job).then(
    () => jobs.delete(key),
    (err) => {
      console.warn(`[emulators] ${sourceId} ${tag} : ${name} non récupéré (${err.message})`);
      Object.assign(job, { state: 'error', error: err.message });
      setTimeout(() => jobs.get(key) === job && jobs.delete(key), ERROR_KEEP_MS).unref?.();
    },
  );
  return fileState(sourceId, tag, name);
}

/** Oublie les échecs récents : nouvel essai possible tout de suite (tests). */
export function clearFileErrors() {
  for (const [key, job] of jobs) if (job.state === 'error') jobs.delete(key);
}

/** Attend la fin de la récupération en cours du fichier (tests, rafraîchissement). */
export async function waitFile(sourceId, tag, name) {
  await jobs.get(jobKey(sourceId, tag, name))?.promise;
  return fileState(sourceId, tag, name);
}

/** Fichier gardé sur le serveur : { path, row }, sinon erreur 404. */
export function storedFile(sourceId, tag, name) {
  requireSource(sourceId);
  const row = safePart(tag) && safePart(name) ? storedRow(sourceId, tag, name) : null;
  const file = row && filePath(sourceId, tag, name);
  if (!file || !fs.existsSync(file)) throw new HttpError(404, 'errors.emulatorFileNotFound', { name });
  return { path: file, row };
}

export function deleteFile(sourceId, tag, name) {
  const { path: file } = storedFile(sourceId, tag, name);
  fs.rmSync(file, { force: true });
  db.prepare('DELETE FROM emulator_files WHERE source = ? AND tag = ? AND name = ?').run(sourceId, tag, name);
  // Dossier de la version vide : supprimé.
  const dir = path.dirname(file);
  if (fs.existsSync(dir) && !fs.readdirSync(dir).length) fs.rmSync(dir, { recursive: true, force: true });
}

/** Sources et fichiers gardés (administration). */
export function mirrorSummary() {
  return Object.entries(SOURCES).map(([id, s]) => {
    const files = storedRows(id).map((r) => ({
      tag: r.tag, name: r.name, size: r.size, sha256: r.sha256, prerelease: Boolean(r.prerelease),
      publishedAt: r.published_at, storedAt: r.stored_at, path: downloadPath(id, r.tag, r.name),
      present: fs.existsSync(filePath(id, r.tag, r.name)),
    }));
    const fetching = [...jobs.entries()]
      .filter(([key]) => key.startsWith(`${id}/`))
      .map(([key, job]) => ({ file: key.slice(id.length + 1), state: job.state, received: job.received, total: job.total, error: job.error }));
    const cached = upstreamCache.get(id);
    const queued = (latestRun?.pending || []).filter((f) => f.source === id).map((f) => `${f.tag}/${f.name}`);
    return {
      id, name: s.name, platforms: s.platforms, host: hostOf(id), files, fetching, queued,
      totalSize: files.reduce((n, f) => n + f.size, 0),
      upstreamError: cached?.error || null,
    };
  });
}

// ---- Nouvelles versions récupérées d'avance ----

/**
 * Variante d'un fichier, sans ses numéros de version : « Eden-Windows-v0.2.1-amd64-clang-pgo.zip » ->
 * « eden-windows-v#.#.#-amd#-clang-pgo.zip » ; empreintes de commit (« df5b9b9 ») ignorées aussi,
 * variantes Android « -sdk33 » gardées ; numéros à plusieurs parties confondus (« Fritchii-v4- » et
 * « Fritchii-v4.1- » : même variante).
 */
export function variantOf(name) {
  return String(name)
    .toLowerCase()
    .replace(/sdk(\d+)/g, 'sdk~$1~')
    .replace(/(?<![a-z0-9])(?=[0-9a-f]*\d)(?=[0-9a-f]*[a-f])[0-9a-f]{7,40}(?![a-z0-9])/g, '#')
    .replace(/(?<!~)\d+(?!~)/g, '#')
    .replace(/#(?:\.#)+/g, '#')
    .replace(/~/g, '');
}

/**
 * Pour chaque source dont le serveur garde des fichiers : dans sa dernière version, les fichiers des
 * mêmes variantes que ceux gardés sont récupérés s'ils ne le sont pas encore. Renvoie les fichiers lancés.
 */
export async function refreshMirror({ force = true } = {}) {
  const started = [];
  const sources = db.prepare('SELECT DISTINCT source FROM emulator_files').all().map((r) => r.source).filter((s) => SOURCES[s]);
  for (const sourceId of sources) {
    const releases = await upstreamReleases(sourceId, { force });
    if (!releases?.length) continue;
    const variants = new Set(storedRows(sourceId).map((r) => variantOf(r.name)));
    // Dernière version qui contient au moins une des variantes (KytyPS5 : pas toujours l'archive Windows).
    for (const variant of variants) {
      const release = releases.find((r) => r.assets.some((a) => variantOf(a.name) === variant));
      const asset = release?.assets.find((a) => variantOf(a.name) === variant);
      if (!asset || fileState(sourceId, release.tag, asset.name).state !== 'remote') continue;
      await prepareFile(sourceId, release.tag, asset.name);
      started.push({ source: sourceId, tag: release.tag, name: asset.name });
    }
  }
  return started;
}

/** Fichiers par défaut d'une source : archive Windows ([windows]) et APK de chaque paquet Android. */
function defaultPatterns(source) {
  return [...(source.windows || []), ...(source.android || []).map((a) => a.asset)].map((p) => new RegExp(`^${p}$`));
}

/**
 * Fichiers par défaut de la dernière version de chaque source (pour chaque motif, la version la plus
 * récente qui en contient un) qui ne sont pas encore sur le serveur : [{ source, tag, name, size }] ;
 * sources injoignables ignorées.
 */
export async function latestDefaultFiles() {
  const files = [];
  for (const [id, source] of Object.entries(SOURCES)) {
    const releases = await upstreamReleases(id, { force: true });
    if (!releases?.length) continue;
    for (const pattern of defaultPatterns(source)) {
      const release = releases.find((r) => r.assets.some((a) => pattern.test(a.name)));
      for (const asset of release?.assets.filter((a) => pattern.test(a.name)) || []) {
        if (fileState(id, release.tag, asset.name).state !== 'remote') continue;
        if (files.some((f) => f.source === id && f.tag === release.tag && f.name === asset.name)) continue;
        files.push({ source: id, tag: release.tag, name: asset.name, size: asset.size });
      }
    }
  }
  return files;
}

// « Récupérer les dernières versions » en cours : fichiers restant à récupérer, un à la fois.
let latestRun = null;

/**
 * Dernières versions de tous les émulateurs récupérées d'un coup (administration) : fichiers par
 * défaut de chaque source absents du serveur, récupérés l'un après l'autre en tâche de fond (des Go
 * au total). Renvoie { running: déjà en cours, files : fichiers prévus }.
 */
export async function fetchAllLatest() {
  if (latestRun) return { running: true, files: latestRun.pending };
  const files = await latestDefaultFiles();
  const run = { pending: [...files] };
  latestRun = run;
  run.promise = (async () => {
    while (run.pending.length) {
      const f = run.pending[0];
      try {
        await prepareFile(f.source, f.tag, f.name);
        run.pending.shift();
        await waitFile(f.source, f.tag, f.name);
      } catch (err) {
        if (run.pending[0] === f) run.pending.shift();
        console.warn(`[emulators] ${f.source} ${f.tag} : ${f.name} non récupéré (${err.message})`);
      }
    }
  })().finally(() => {
    if (latestRun === run) latestRun = null;
  });
  return { running: false, files };
}

/** Fin de « Récupérer les dernières versions » (tests). */
export const latestRunDone = () => latestRun?.promise ?? Promise.resolve();

/** Rafraîchissement périodique (démarrage du serveur) ; 0 heure : désactivé. */
export function startMirrorRefresh() {
  const hours = config.emulatorRefreshHours;
  if (!(hours > 0)) return;
  const run = () => refreshMirror().catch((err) => console.warn(`[emulators] rafraîchissement : ${err.message}`));
  setTimeout(run, 60_000).unref();
  setInterval(run, hours * 3600_000).unref();
}
