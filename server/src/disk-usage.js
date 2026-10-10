// Espace disque utilisé par le serveur, en arborescence : ROMs (par système), sauvegardes et
// historique des états (par profil), BIOS (par système), images, APK, caches, base de données et
// tout autre dossier du répertoire de données ; place libre sur les disques concernés.
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';
import { db } from './db.js';

/** Taille et nombre de fichiers d'un dossier (ou d'un fichier), sous-dossiers compris. */
async function measure(target) {
  let size = 0;
  let files = 0;
  const walk = async (p) => {
    let stat;
    try {
      stat = await fs.promises.lstat(p);
    } catch {
      return; // supprimé entre-temps, illisible
    }
    if (stat.isSymbolicLink()) return;
    if (stat.isFile()) {
      size += stat.size;
      files++;
      return;
    }
    if (!stat.isDirectory()) return;
    let entries = [];
    try {
      entries = await fs.promises.readdir(p);
    } catch {
      return;
    }
    for (const name of entries) await walk(path.join(p, name));
  };
  await walk(target);
  return { size, files };
}

/** Nœud de l'arborescence : { id, label, path, size, files, children } (taille : somme des enfants s'il en a). */
async function node(id, label, target, children = null) {
  if (children) {
    const kids = (await Promise.all(children)).filter((c) => c && (c.size > 0 || c.files > 0));
    kids.sort((a, b) => b.size - a.size);
    return { id, label, path: target, size: kids.reduce((n, c) => n + c.size, 0), files: kids.reduce((n, c) => n + c.files, 0), children: kids };
  }
  return { id, label, path: target, ...(await measure(target)), children: null };
}

const subdirs = (dir) => {
  try {
    return fs.readdirSync(dir, { withFileTypes: true }).filter((e) => e.isDirectory() && !e.name.startsWith('.')).map((e) => e.name);
  } catch {
    return [];
  }
};

/** Disque d'un dossier : { total, free } en octets (null si inconnu). */
function disk(dir) {
  try {
    const s = fs.statfsSync(dir);
    return { path: dir, total: s.blocks * s.bsize, free: s.bavail * s.bsize };
  } catch {
    return null;
  }
}

const inside = (child, parent) => {
  const rel = path.relative(parent, child);
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
};

let cache = null;
const CACHE_MS = 60_000;

/**
 * Arborescence de l'espace utilisé ; [refresh] : recalculée (sinon gardée une minute, le parcours
 * des ROMs pouvant être long). Libellés des systèmes et des profils lus dans la base.
 */
export async function diskUsage({ refresh = false } = {}) {
  if (!refresh && cache && Date.now() - cache.at < CACHE_MS) return cache.value;
  const data = config.dataDir;
  const systems = db.prepare('SELECT id, name, folder FROM systems').all();
  const systemByFolder = new Map(systems.map((s) => [s.folder, s]));
  const systemById = new Map(systems.map((s) => [s.id, s]));
  const users = new Map(db.prepare('SELECT id, username FROM users').all().map((u) => [String(u.id), u.username]));
  const profile = (id) => users.get(id) ?? `#${id}`;

  const roms = node('roms', 'roms', config.romsDir, subdirs(config.romsDir).map((folder) =>
    node(`roms/${folder}`, systemByFolder.get(folder)?.name ?? folder, path.join(config.romsDir, folder))));

  // Sauvegardes et historique des états, par profil.
  const savesDir = path.join(data, 'saves');
  const statesDir = path.join(data, 'states');
  const profiles = [...new Set([...subdirs(savesDir), ...subdirs(statesDir)])];
  const saves = node('saves', 'saves', null, profiles.map((id) => node(`saves/${id}`, profile(id), null, [
    node(`saves/${id}/saves`, 'saves', path.join(savesDir, id)),
    node(`saves/${id}/states`, 'states', path.join(statesDir, id)),
  ])));

  const biosDir = path.join(data, 'bios');
  const bios = node('bios', 'bios', biosDir, subdirs(biosDir).map((id) =>
    node(`bios/${id}`, systemById.get(id)?.name ?? id, path.join(biosDir, id))));

  const known = new Set(['saves', 'states', 'bios', 'media', 'apks', 'cache', 'launchbox']);
  const dbFiles = fs.readdirSync(data).filter((n) => n.startsWith(path.basename(config.dbFile)));
  // Autres fichiers et dossiers du répertoire de données (ROMs à part s'il s'y trouve).
  const others = fs.readdirSync(data, { withFileTypes: true })
    .filter((e) => !known.has(e.name) && !dbFiles.includes(e.name) && !inside(config.romsDir, path.join(data, e.name)) && !e.name.startsWith('.'))
    .map((e) => node(`other/${e.name}`, e.name, path.join(data, e.name)));

  const children = [
    roms,
    saves,
    bios,
    node('media', 'media', config.mediaDir),
    node('apks', 'apks', path.join(data, 'apks')),
    node('cache', 'cache', null, [
      node('cache/core-info', 'core-info', path.join(data, 'cache')),
      node('cache/launchbox', 'launchbox', path.join(data, 'launchbox')),
    ]),
    node('database', 'database', null, dbFiles.map((n) => node(`database/${n}`, n, path.join(data, n)))),
    ...(others.length ? [node('other', 'other', null, others)] : []),
  ];
  const tree = await node('root', 'root', data, children);
  const disks = [disk(data)];
  if (!inside(config.romsDir, data)) disks.push(disk(config.romsDir));
  const value = { computedAt: new Date().toISOString(), dataDir: data, romsDir: config.romsDir, tree, disks: disks.filter(Boolean) };
  cache = { at: Date.now(), value };
  return value;
}
