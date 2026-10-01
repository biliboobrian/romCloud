// BIOS des systèmes : fichiers envoyés sur le serveur (data/bios/<id du système>/<chemin>)
// et BIOS attendus, lus dans les fiches des cœurs RetroArch des modèles d'émulateurs
// (https://github.com/libretro/libretro-core-info : firmwareN_path / _desc / _opt, MD5 dans « notes »).
// Un BIOS dont l'empreinte (MD5 ou SHA1) est connue n'est accepté que s'il y correspond.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';
import { db } from './db.js';
import { HttpError } from './http-error.js';
import { requireSystem } from './systems.js';
import { readZipEntries, readZipEntry } from './zip.js';

const CORE_INFO_URL = process.env.LIBRETRO_CORE_INFO_URL
  || 'https://raw.githubusercontent.com/libretro/libretro-core-info/master/';
const infoCache = new Map(); // cœur -> { at, info }
const infoCacheDir = () => path.join(config.dataDir, 'cache', 'core-info');

export const biosDir = (system) => path.join(config.dataDir, 'bios', system.id);

// ---------------------------------------------------------------------------
// Catalogue : BIOS attendus par les cœurs RetroArch d'un système
// ---------------------------------------------------------------------------

/** Cœurs RetroArch des modèles d'émulateurs (extra LIBRETRO), sans doublon. */
export function coresOfSystem(system) {
  const cores = [];
  for (const player of system.players || []) {
    const m = /(?:^|\s)-e\s+LIBRETRO\s+(\S+)/.exec(player.amStartArguments || '');
    if (!m) continue;
    const core = path.posix.basename(m[1]).replace(/\.so$/, '').replace(/_android$/, '').replace(/_libretro$/, '');
    if (!cores.includes(core)) cores.push(core);
  }
  return cores;
}

/** Analyse une fiche de cœur : liste des BIOS (chemin, description, obligatoire, MD5 de référence). */
export function parseCoreInfo(text) {
  const values = {};
  for (const line of text.split(/\r?\n/)) {
    const m = /^\s*(\w+)\s*=\s*(?:"(.*)"|(\S+))\s*$/.exec(line);
    if (m) values[m[1]] = m[2] ?? m[3];
  }
  const md5ByName = {};
  for (const note of (values.notes || '').split('|')) {
    const m = /\(!\)\s*(.+?)\s*\(md5\):\s*([0-9a-f]{32})/i.exec(note);
    if (m) md5ByName[m[1].toLowerCase()] = m[2].toLowerCase();
  }
  const count = Number(values.firmware_count || 0);
  const firmware = [];
  for (let i = 0; i < count; i++) {
    const rawPath = values[`firmware${i}_path`];
    if (!rawPath) continue;
    const biosPath = rawPath.replace(/\/+$/, '');
    const description = values[`firmware${i}_desc`] || biosPath;
    firmware.push({
      path: biosPath,
      description,
      required: values[`firmware${i}_opt`] === 'false',
      md5: md5ByName[path.posix.basename(biosPath).toLowerCase()] || null,
      // Dossier attendu (« 'pcsx2/bios' folder ») : rempli par l'envoi d'un .zip de son contenu.
      folder: rawPath.endsWith('/') || (/\bfolder\b/i.test(description) && !path.posix.extname(biosPath)),
    });
  }
  return { name: values.display_name || null, firmware };
}

async function fetchCoreInfo(core) {
  const cached = infoCache.get(core);
  // Fiche trouvée : 24 h ; introuvable ou serveur hors ligne : nouvel essai après 10 min.
  if (cached && Date.now() - cached.at < (cached.info ? 24 * 3600_000 : 600_000)) return cached.info;
  const disk = path.join(infoCacheDir(), `${core}.info`);
  // Les cœurs Android « _gles2/_gles3 » ont parfois leur propre fiche, sinon celle du cœur de base.
  for (const name of [core, core.replace(/_gles[23]$/, '')]) {
    try {
      const res = await fetch(`${CORE_INFO_URL}${encodeURIComponent(name)}_libretro.info`, { signal: AbortSignal.timeout(8000) });
      if (!res.ok) continue;
      const text = await res.text();
      fs.mkdirSync(infoCacheDir(), { recursive: true });
      fs.writeFileSync(disk, text);
      const info = parseCoreInfo(text);
      infoCache.set(core, { at: Date.now(), info });
      return info;
    } catch {
      break; // hors ligne : on se rabat sur le cache disque
    }
  }
  if (fs.existsSync(disk)) {
    const info = parseCoreInfo(fs.readFileSync(disk, 'utf8'));
    infoCache.set(core, { at: Date.now(), info });
    return info;
  }
  infoCache.set(core, { at: Date.now(), info: null });
  return null;
}

/**
 * Empreintes acceptées en plus de celles de libretro, par nom de fichier : bios-hashes.json fourni
 * avec le serveur, complété par DATA_DIR/bios-hashes.json (ou l'ancien DATA_DIR/bios-md5.json).
 * Format : { "scph5501.bin": ["490f66…", "0555c6…"], … } : 32 caractères hexadécimaux pour un MD5,
 * 40 pour un SHA1 (les clés commençant par « _ » sont ignorées).
 */
export function extraHashes() {
  const extra = {};
  const files = [
    path.join(config.rootDir, 'bios-hashes.json'),
    path.join(config.dataDir, 'bios-hashes.json'),
    path.join(config.dataDir, 'bios-md5.json'),
  ];
  for (const file of files) {
    let json;
    try {
      json = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      continue; // absent ou illisible
    }
    for (const [name, list] of Object.entries(json)) {
      if (name.startsWith('_')) continue;
      const entry = (extra[name.toLowerCase()] ||= { md5s: [], sha1s: [] });
      for (const hash of (Array.isArray(list) ? list : [list]).map((h) => String(h).toLowerCase())) {
        const kind = /^[0-9a-f]{32}$/.test(hash) ? entry.md5s : /^[0-9a-f]{40}$/.test(hash) ? entry.sha1s : null;
        if (kind && !kind.includes(hash)) kind.push(hash);
      }
    }
  }
  return extra;
}

/** Empreintes acceptées pour un chemin de BIOS : MD5 des cœurs, puis empreintes de bios-hashes.json. */
function acceptedHashes(biosPath, md5sFromCores, extra) {
  const more = extra[path.posix.basename(biosPath).toLowerCase()] || { md5s: [], sha1s: [] };
  return { md5s: [...new Set([...md5sFromCores, ...more.md5s])], sha1s: more.sha1s };
}

/** « ok », « mismatch », ou « unknown » quand aucune empreinte n'est connue pour ce BIOS. */
function hashStatus(file, ref) {
  if (!ref.md5s.length && !ref.sha1s.length) return 'unknown';
  return ref.md5s.includes(file.md5) || ref.sha1s.includes(file.sha1) ? 'ok' : 'mismatch';
}

/** MD5 et SHA1 d'un fichier, en un seul passage. */
async function hashBios(file) {
  const md5 = crypto.createHash('md5');
  const sha1 = crypto.createHash('sha1');
  for await (const chunk of fs.createReadStream(file, { highWaterMark: 1 << 20 })) {
    md5.update(chunk);
    sha1.update(chunk);
  }
  return { md5: md5.digest('hex'), sha1: sha1.digest('hex') };
}

/**
 * BIOS attendus pour un système, fusionnés entre ses cœurs (obligatoire si un cœur l'exige).
 * `md5` : référence libretro ; `md5s` / `sha1s` : toutes les empreintes acceptées.
 */
export async function expectedBios(system) {
  const byPath = new Map();
  const cores = coresOfSystem(system);
  let available = 0;
  for (const core of cores) {
    const info = await fetchCoreInfo(core);
    if (!info) continue;
    available++;
    for (const f of info.firmware) {
      const key = f.path.toLowerCase();
      const entry = byPath.get(key) || { ...f, md5s: [], cores: [] };
      entry.required ||= f.required;
      entry.folder ||= f.folder;
      entry.md5 ||= f.md5;
      if (f.md5 && !entry.md5s.includes(f.md5)) entry.md5s.push(f.md5);
      entry.cores.push(core);
      byPath.set(key, entry);
    }
  }
  const extra = extraHashes();
  const list = [...byPath.values()]
    .map((e) => ({ ...e, ...acceptedHashes(e.path, e.md5s, extra) }))
    .sort((a, b) => b.required - a.required || a.path.localeCompare(b.path));
  return { cores, coreInfoAvailable: available > 0, expected: list };
}

// ---------------------------------------------------------------------------
// Fichiers envoyés
// ---------------------------------------------------------------------------

/** Chemin relatif sûr (« dc/dc_boot.bin ») : pas de chemin absolu ni de « .. ». */
export function safeBiosPath(input) {
  const p = String(input || '').replace(/\\/g, '/').replace(/^\/+/, '').trim();
  const parts = p.split('/');
  if (!p || parts.some((s) => !s || s === '.' || s === '..' || /[<>:"|?*\x00-\x1f]/.test(s))) {
    throw new HttpError(400, 'errors.invalidBiosPath', { path: input });
  }
  return parts.join('/');
}

const rowToBios = (r) => ({ id: r.id, systemId: r.system_id, path: r.path, size: r.size, md5: r.md5, sha1: r.sha1, addedAt: r.added_at });

export function listBiosRows(systemId) {
  return db.prepare('SELECT * FROM bios WHERE system_id = ? ORDER BY path').all(systemId).map(rowToBios);
}

/** Le fichier est-il une archive .zip (signature « PK\\3\\4 ») ? */
function isZipFile(file) {
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const magic = Buffer.alloc(4);
    fs.readSync(fd, magic, 0, 4, 0);
    return magic.readUInt32LE(0) === 0x04034b50;
  } catch {
    return false;
  } finally {
    if (fd !== undefined) fs.closeSync(fd);
  }
}

/**
 * Fichiers à extraire d'un .zip envoyé pour le dossier [folder] : chemins dans le dossier
 * (« bios/scph39001.bin » -> « pcsx2/bios/scph39001.bin » si l'archive contient le dossier
 * lui-même) et contenu. Limites contre les archives démesurées.
 */
function folderZipContent(zipFile, folder) {
  const entries = readZipEntries(zipFile);
  if (!entries.length) throw new HttpError(422, 'errors.biosZipUnreadable', { path: folder });
  const total = entries.reduce((n, e) => n + e.size, 0);
  if (entries.length > 20000 || total > 4 * 1024 ** 3) throw new HttpError(422, 'errors.biosZipTooLarge', { path: folder });
  // Archive du dossier lui-même (« bios/… ») : ce premier niveau est retiré.
  const top = path.posix.basename(folder).toLowerCase();
  const strip = entries.every((e) => e.name.toLowerCase().startsWith(`${top}/`));
  return entries.map((e) => ({
    relative: safeBiosPath(`${folder}/${strip ? e.name.slice(top.length + 1) : e.name}`),
    data: readZipEntry(zipFile, e),
  }));
}

const hashBuffer = (data) => ({
  md5: crypto.createHash('md5').update(data).digest('hex'),
  sha1: crypto.createHash('sha1').update(data).digest('hex'),
});

/** Enregistre un BIOS (fichier ou contenu) et sa ligne en base ; écrase un BIOS de même chemin. */
function storeBios(system, relative, { tempPath, data }, hashes) {
  const dest = path.join(biosDir(system), ...relative.split('/'));
  // Un ancien fichier porte le nom d'un dossier à créer (.zip enregistré tel quel) : retiré.
  const parts = relative.split('/');
  for (let i = 1; i < parts.length; i++) {
    const prefix = parts.slice(0, i).join('/');
    const blocking = path.join(biosDir(system), ...parts.slice(0, i));
    if (fs.existsSync(blocking) && fs.statSync(blocking).isFile()) {
      fs.rmSync(blocking, { force: true });
      db.prepare('DELETE FROM bios WHERE system_id = ? AND path = ?').run(system.id, prefix);
    }
  }
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  if (fs.existsSync(dest) && fs.statSync(dest).isDirectory()) fs.rmSync(dest, { recursive: true, force: true });
  fs.rmSync(dest, { force: true });
  if (data) fs.writeFileSync(dest, data);
  else fs.renameSync(tempPath, dest);
  const size = fs.statSync(dest).size;
  db.prepare(
    `INSERT INTO bios (system_id, path, size, md5, sha1) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(system_id, path) DO UPDATE SET size = excluded.size, md5 = excluded.md5, sha1 = excluded.sha1,
       added_at = datetime('now')`,
  ).run(system.id, relative, size, hashes.md5, hashes.sha1);
}

/**
 * Réparation : .zip enregistré tel quel à la place d'un dossier (« pcsx2/bios », sans extension,
 * ou dossier attendu par un cœur) : son contenu est extrait dans le dossier.
 */
function expandFolderZips(system, files, folders) {
  let changed = false;
  for (const f of files) {
    const file = path.join(biosDir(system), ...f.path.split('/'));
    const folder = folders.has(f.path.toLowerCase()) || !path.posix.extname(f.path);
    if (!folder || !fs.existsSync(file) || !isZipFile(file)) continue;
    try {
      const content = folderZipContent(file, f.path);
      const zipCopy = `${file}.zip-${process.pid}`;
      fs.renameSync(file, zipCopy);
      db.prepare('DELETE FROM bios WHERE id = ?').run(f.id);
      for (const item of content) storeBios(system, item.relative, { data: item.data }, hashBuffer(item.data));
      fs.rmSync(zipCopy, { force: true });
      changed = true;
    } catch {
      // archive illisible : laissée telle quelle
    }
  }
  return changed;
}

/** BIOS d'un système : fichiers présents (avec contrôle des empreintes) et BIOS attendus par ses cœurs. */
export async function systemBios(systemId, { catalog: withCatalog = true } = {}) {
  const system = requireSystem(systemId);
  // Sans catalogue (applications) : uniquement les fichiers présents, sans requête vers libretro.
  const catalog = withCatalog ? await expectedBios(system) : { cores: [], coreInfoAvailable: false, expected: [] };
  const folders = new Set(catalog.expected.filter((e) => e.folder).map((e) => e.path.toLowerCase()));
  let files = listBiosRows(system.id);
  if (expandFolderZips(system, files, folders)) files = listBiosRows(system.id);
  // Fichiers envoyés avant l'enregistrement du SHA1 : calculé une fois.
  for (const f of files.filter((row) => !row.sha1)) {
    const file = path.join(biosDir(system), ...f.path.split('/'));
    if (!fs.existsSync(file)) continue;
    f.sha1 = (await hashBios(file)).sha1;
    db.prepare('UPDATE bios SET sha1 = ? WHERE id = ?').run(f.sha1, f.id);
  }
  const byPath = new Map(files.map((f) => [f.path.toLowerCase(), f]));
  const expected = catalog.expected.map((e) => {
    const file = byPath.get(e.path.toLowerCase());
    // Dossier : présent dès qu'il contient un fichier.
    const inFolder = e.folder ? files.filter((f) => f.path.toLowerCase().startsWith(`${e.path.toLowerCase()}/`)) : [];
    return { ...e, fileId: file?.id ?? null, present: Boolean(file) || inFolder.length > 0, fileCount: e.folder ? inFolder.length : undefined };
  });
  const expectedByPath = new Map(catalog.expected.map((e) => [e.path.toLowerCase(), e]));
  const extra = extraHashes();
  return {
    cores: catalog.cores,
    coreInfoAvailable: catalog.coreInfoAvailable,
    files: files.map((f) => {
      const ref = expectedByPath.get(f.path.toLowerCase());
      const accepted = ref ?? acceptedHashes(f.path, [], extra);
      return { ...f, description: ref?.description ?? null, required: ref?.required ?? false, hashStatus: hashStatus(f, accepted) };
    }),
    expected,
  };
}

/**
 * Enregistre des fichiers envoyés. Chemin : `targetPath` si fourni (un seul fichier), sinon le
 * chemin attendu dont le nom correspond (« dc_boot.bin » -> « dc/dc_boot.bin »), sinon le nom du fichier.
 * Pour un dossier attendu (« pcsx2/bios ») : un .zip est extrait dans le dossier, un autre fichier
 * y est rangé sous son nom. Un BIOS dont les empreintes sont connues doit y correspondre : sinon
 * tout l'envoi est refusé.
 */
export async function addBiosFiles(systemId, uploads, targetPath) {
  const system = requireSystem(systemId);
  const { expected } = await expectedBios(system);
  const byName = new Map(expected.map((e) => [path.posix.basename(e.path).toLowerCase(), e]));
  const byPath = new Map(expected.map((e) => [e.path.toLowerCase(), e]));
  const extra = extraHashes();
  const checked = [];
  const check = (name, relative, hashes, source) => {
    const ref = byPath.get(relative.toLowerCase()) ?? acceptedHashes(relative, [], extra);
    if (hashStatus(hashes, ref) === 'mismatch') {
      throw new HttpError(422, 'errors.biosHashMismatch', {
        name,
        path: relative,
        md5: hashes.md5,
        sha1: hashes.sha1,
        expected: [...ref.md5s.map((h) => `MD5 ${h}`), ...ref.sha1s.map((h) => `SHA1 ${h}`)].join(', '),
      });
    }
    checked.push({ relative, hashes, source });
  };
  for (const upload of uploads) {
    const name = path.basename(upload.originalName);
    const relative = safeBiosPath(targetPath && uploads.length === 1 ? targetPath : byName.get(name.toLowerCase())?.path || name);
    const folder = byPath.get(relative.toLowerCase())?.folder;
    if (folder && /\.zip$/i.test(name)) {
      // Contenu du dossier : chaque fichier de l'archive devient un BIOS du dossier.
      for (const item of folderZipContent(upload.tempPath, relative)) check(name, item.relative, hashBuffer(item.data), { data: item.data });
    } else if (folder) {
      const inFolder = safeBiosPath(`${relative}/${name}`);
      check(name, inFolder, await hashBios(upload.tempPath), { tempPath: upload.tempPath });
    } else {
      check(name, relative, await hashBios(upload.tempPath), { tempPath: upload.tempPath });
    }
  }
  const saved = [];
  for (const { relative, hashes, source } of checked) {
    storeBios(system, relative, source, hashes);
    saved.push(relative);
  }
  return saved;
}

export function requireBios(id) {
  const row = db.prepare('SELECT * FROM bios WHERE id = ?').get(Number(id));
  if (!row) throw new HttpError(404, 'errors.biosNotFound', { id });
  return row;
}

export function biosFilePath(row) {
  const system = requireSystem(row.system_id);
  return path.join(biosDir(system), ...row.path.split('/'));
}

export function deleteBios(id) {
  const row = requireBios(id);
  fs.rmSync(biosFilePath(row), { force: true });
  db.prepare('DELETE FROM bios WHERE id = ?').run(row.id);
}
