// BIOS des systèmes : fichiers envoyés sur le serveur (data/bios/<id du système>/<chemin>)
// et BIOS attendus, lus dans les fiches des cœurs RetroArch des modèles d'émulateurs
// (https://github.com/libretro/libretro-core-info : firmwareN_path / _desc / _opt, MD5 dans « notes »).
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';
import { db } from './db.js';
import { HttpError } from './http-error.js';
import { hashFile } from './library.js';
import { requireSystem } from './systems.js';

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
    const biosPath = values[`firmware${i}_path`];
    if (!biosPath) continue;
    firmware.push({
      path: biosPath,
      description: values[`firmware${i}_desc`] || biosPath,
      required: values[`firmware${i}_opt`] === 'false',
      md5: md5ByName[path.posix.basename(biosPath).toLowerCase()] || null,
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
 * MD5 acceptés en plus de ceux de libretro (autres révisions d'un même BIOS), par nom de fichier :
 * bios-md5.json fourni avec le serveur, complété par DATA_DIR/bios-md5.json s'il existe.
 * Format : { "scph5501.bin": ["924e39…"], … } (les clés commençant par « _ » sont ignorées).
 */
export function extraMd5() {
  const extra = {};
  for (const file of [path.join(config.rootDir, 'bios-md5.json'), path.join(config.dataDir, 'bios-md5.json')]) {
    let json;
    try {
      json = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      continue; // absent ou illisible
    }
    for (const [name, list] of Object.entries(json)) {
      if (name.startsWith('_')) continue;
      const hashes = (Array.isArray(list) ? list : [list]).map((h) => String(h).toLowerCase()).filter((h) => /^[0-9a-f]{32}$/.test(h));
      extra[name.toLowerCase()] = [...new Set([...(extra[name.toLowerCase()] || []), ...hashes])];
    }
  }
  return extra;
}

/** MD5 acceptés pour un chemin de BIOS : ceux des cœurs puis ceux de bios-md5.json. */
const acceptedMd5 = (biosPath, fromCores, extra) =>
  [...new Set([...fromCores, ...(extra[path.posix.basename(biosPath).toLowerCase()] || [])])];

/**
 * BIOS attendus pour un système, fusionnés entre ses cœurs (obligatoire si un cœur l'exige).
 * `md5` : référence libretro ; `md5s` : toutes les empreintes acceptées.
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
      entry.md5 ||= f.md5;
      if (f.md5 && !entry.md5s.includes(f.md5)) entry.md5s.push(f.md5);
      entry.cores.push(core);
      byPath.set(key, entry);
    }
  }
  const extra = extraMd5();
  const list = [...byPath.values()]
    .map((e) => ({ ...e, md5s: acceptedMd5(e.path, e.md5s, extra) }))
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

const rowToBios = (r) => ({ id: r.id, systemId: r.system_id, path: r.path, size: r.size, md5: r.md5, addedAt: r.added_at });

export function listBiosRows(systemId) {
  return db.prepare('SELECT * FROM bios WHERE system_id = ? ORDER BY path').all(systemId).map(rowToBios);
}

/** BIOS d'un système : fichiers présents (avec contrôle MD5) et BIOS attendus par ses cœurs. */
export async function systemBios(systemId, { catalog: withCatalog = true } = {}) {
  const system = requireSystem(systemId);
  const files = listBiosRows(system.id);
  // Sans catalogue (applications) : uniquement les fichiers présents, sans requête vers libretro.
  const catalog = withCatalog ? await expectedBios(system) : { cores: [], coreInfoAvailable: false, expected: [] };
  const byPath = new Map(files.map((f) => [f.path.toLowerCase(), f]));
  const expected = catalog.expected.map((e) => {
    const file = byPath.get(e.path.toLowerCase());
    return { ...e, fileId: file?.id ?? null, present: Boolean(file) };
  });
  const expectedByPath = new Map(catalog.expected.map((e) => [e.path.toLowerCase(), e]));
  const extra = extraMd5();
  return {
    cores: catalog.cores,
    coreInfoAvailable: catalog.coreInfoAvailable,
    files: files.map((f) => {
      const ref = expectedByPath.get(f.path.toLowerCase());
      const accepted = ref?.md5s ?? acceptedMd5(f.path, [], extra);
      const md5Status = !accepted.length ? 'unknown' : accepted.includes(f.md5) ? 'ok' : 'mismatch';
      return { ...f, description: ref?.description ?? null, required: ref?.required ?? false, md5Status };
    }),
    expected,
  };
}

/**
 * Enregistre des fichiers envoyés. Chemin : `targetPath` si fourni (un seul fichier), sinon le
 * chemin attendu dont le nom correspond (« dc_boot.bin » -> « dc/dc_boot.bin »), sinon le nom du fichier.
 */
export async function addBiosFiles(systemId, uploads, targetPath) {
  const system = requireSystem(systemId);
  const { expected } = await expectedBios(system);
  const byName = new Map(expected.map((e) => [path.posix.basename(e.path).toLowerCase(), e.path]));
  const saved = [];
  for (const upload of uploads) {
    const name = path.basename(upload.originalName);
    const relative = safeBiosPath(targetPath && uploads.length === 1 ? targetPath : byName.get(name.toLowerCase()) || name);
    const dest = path.join(biosDir(system), ...relative.split('/'));
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.rmSync(dest, { force: true });
    fs.renameSync(upload.tempPath, dest);
    const { md5 } = await hashFile(dest);
    const size = fs.statSync(dest).size;
    db.prepare(
      `INSERT INTO bios (system_id, path, size, md5) VALUES (?, ?, ?, ?)
       ON CONFLICT(system_id, path) DO UPDATE SET size = excluded.size, md5 = excluded.md5, added_at = datetime('now')`,
    ).run(system.id, relative, size, md5);
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
