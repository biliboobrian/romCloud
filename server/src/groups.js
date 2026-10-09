// Jeux en plusieurs fichiers : disques d'un même jeu, DLC et mises à jour, rattachés au fichier
// principal (colonnes parent_id, part_kind, part_index de la table games). Les applications ne
// voient que le jeu principal et téléchargent ses parties avec lui.
//
// Regroupement automatique d'après les noms de fichiers, refait à chaque scan :
//  - disques : « (Disc 2) », « (Disk 2 of 3) », « (CD2) », « - Disc 2 »… -> rattachés au disque
//    de plus petit numéro du même nom ;
//  - mises à jour et DLC : « [UPD] », « (Update v1.2) », « [DLC] », « (Add-On) »… ; identifiant Wii U
//    (0005000E… mise à jour, 0005000C… DLC du jeu 00050000…) ; sinon le jeu dont le titre commence
//    le titre du DLC ;
//  - paquets Sony (.pkg : PS Vita, PS3, PSP) : type de contenu lu dans l'en-tête du paquet (jeu, patch,
//    DLC), à défaut d'après l'identifiant de contenu (« EP0001-PCSB00598_00-DLC0100000000000 »,
//    « …_patch_01.02 ») ; rattachés au jeu du même numéro de série (PCSB00598).
// Un rattachement ou un détachement fait dans l'interface web (group_mode = 'manual') n'est plus
// modifié par le regroupement automatique.
import fs from 'node:fs';
import path from 'node:path';
import { db, transaction } from './db.js';
import { HttpError } from './http-error.js';
import { titleFromFileName } from './library.js';
import { requireSystem, systemDir } from './systems.js';

export const PART_KINDS = ['disc', 'update', 'dlc'];

const DISC_RE = [
  // « (Disc 2) », « (Disk 2 of 3) », « [CD 2] », « (Disc B) »
  /\s*[([]\s*(?:disc|disk|disque|cd|dvd)\s*[-_ ]?(\d{1,2}|[a-h])(?:\s*(?:of|sur|\/)\s*\d{1,2})?\s*[)\]]/i,
  // « - Disc 2 », « _CD2 », « Disk B » sans parenthèses
  /[\s._-]+(?:disc|disk|disque|cd)\s*[-_ ]?(\d{1,2}|[a-h])(?=[\s._-]|$)/i,
];
const UPDATE_RE = /\s*[([]\s*(?:upd|update|patch|mise à jour)\b[^)\]]*[)\]]|\s*\b(?:update|patch)\s+v?\d[\w.]*/i;
const DLC_RE = /\s*[([]\s*(?:dlc|add-?ons?)\b[^)\]]*[)\]]|\s*\bDLC\b/i;
const WIIU_ID_RE = /\b0005000([0CE])([0-9A-F]{8})\b/i;
// Numéro de série Sony (« BLUS30443 », « BLES-01234 »), aussi au milieu d'un identifiant de contenu
// (« UP0001-BLUS30443_00-… »).
const SERIAL_RE = /(?:^|[^A-Z0-9])([A-Z]{4})[-_]?(\d{5})(?![0-9])/;
// Identifiant de contenu Sony (nommage NoPayStation) : région et éditeur, numéro de série, libellé
// de 16 caractères, « _patch_01.02 » pour un patch.
const CONTENT_ID_RE = /^[A-Z]{2}\d{4}-([A-Z]{4}\d{5})_\d{2}-([A-Z0-9]{16})(_patch_[\d.]+)?$/i;
// Types de contenu des paquets Sony (métadonnée 2 de l'en-tête).
const PKG_GAME_DATA = 0x4; // PS3 : données de jeu (DLC ou patch)
const PKG_GAME_EXEC = 0x5; // PS3 : jeu
const PKG_VITA_APP = 0x15; // PS Vita : jeu ou patch (drapeau 0x10)
const PKG_VITA_DLC = 0x16; // PS Vita : DLC

/** En-tête d'un paquet Sony : { type (type de contenu), patch } ; null si ce n'est pas un .pkg lisible. */
export function parsePkgHeader(buffer) {
  if (buffer.length < 16 || buffer.readUInt32BE(0) !== 0x7f504b47) return null;
  let offset = buffer.readUInt32BE(8);
  const count = buffer.readUInt32BE(12);
  let type = null;
  let flags = 0;
  for (let i = 0; i < count && offset + 8 <= buffer.length; i++) {
    const id = buffer.readUInt32BE(offset);
    const size = buffer.readUInt32BE(offset + 4);
    if (offset + 8 + size > buffer.length) break;
    if (id === 2 && size >= 4) type = buffer.readUInt32BE(offset + 8);
    if (id === 3 && size >= 4) flags = buffer.readUInt32BE(offset + 8);
    offset += 8 + size;
  }
  return type === null ? null : { type, patch: (flags & 0x10) !== 0 };
}

// En-têtes déjà lus : chemin -> { key (taille et date), header }.
const pkgCache = new Map();

/** En-tête du paquet [file] (4 premiers Ko), mémorisé tant que sa taille et sa date ne changent pas. */
export function pkgHeader(file, size, mtime) {
  const key = `${size}:${mtime}`;
  const cached = pkgCache.get(file);
  if (cached?.key === key) return cached.header;
  let header = null;
  try {
    const fd = fs.openSync(file, 'r');
    try {
      const buffer = Buffer.alloc(4096);
      header = parsePkgHeader(buffer.subarray(0, fs.readSync(fd, buffer, 0, buffer.length, 0)));
    } finally {
      fs.closeSync(fd);
    }
  } catch {
    header = null;
  }
  pkgCache.set(file, { key, header });
  return header;
}

/**
 * Partie d'un paquet Sony : 'update', 'dlc', null (jeu) d'après l'en-tête [pkg] s'il est lu, sinon
 * d'après l'identifiant de contenu ; undefined si rien ne permet de le dire.
 */
function pkgKind(name, pkg) {
  const contentId = CONTENT_ID_RE.exec(name);
  const patchName = Boolean(contentId?.[3]) || /[_\s-]patch(?:[_\s-]|$)/i.test(name);
  switch (pkg?.type) {
    case PKG_VITA_DLC:
      return 'dlc';
    case PKG_VITA_APP:
      return pkg.patch || patchName ? 'update' : null;
    case PKG_GAME_DATA:
      return patchName || UPDATE_RE.test(name) ? 'update' : 'dlc';
    case PKG_GAME_EXEC:
      return null;
    default:
      break;
  }
  if (contentId) return patchName ? 'update' : /DLC|ADDCONT/i.test(contentId[2]) ? 'dlc' : null;
  return undefined;
}

const base = (fileName) => fileName.replace(/\.[^.]+$/, '');
const extension = (fileName) => (/\.([^.]+)$/.exec(fileName)?.[1] || '').toLowerCase();
const normalize = (text) => text.toLowerCase().replace(/[\s._-]+/g, ' ').trim();

/**
 * Partie d'un jeu d'après son nom de fichier (et l'en-tête [pkg] d'un paquet Sony) :
 * { kind: 'disc', index, key } (key : nom sans le numéro de disque), { kind: 'update' | 'dlc', … }
 * avec les clés de rattachement trouvées (wiiu, serial, title), ou null pour un jeu ordinaire.
 */
export function partInfo(fileName, pkg = null) {
  const name = base(fileName);
  const wiiu = WIIU_ID_RE.exec(name);
  if (wiiu && wiiu[1] !== '0') {
    return { kind: wiiu[1].toUpperCase() === 'E' ? 'update' : 'dlc', wiiu: wiiu[2].toLowerCase(), code: productCode(name), title: dlcTitle(name) };
  }
  if (extension(fileName) === 'pkg') {
    const kind = pkgKind(name, pkg);
    if (kind === null) return null;
    if (kind) return { kind, serial: serialOf(name), title: null };
  }
  const update = UPDATE_RE.test(name);
  const dlc = !update && DLC_RE.test(name);
  if (update || dlc) {
    const serial = extension(fileName) === 'pkg' ? serialOf(name) : null;
    return { kind: update ? 'update' : 'dlc', serial, code: productCode(name), title: dlcTitle(name) };
  }
  for (const re of DISC_RE) {
    const m = re.exec(name);
    if (m) {
      const n = /^\d+$/.test(m[1]) ? Number(m[1]) : m[1].toLowerCase().charCodeAt(0) - 96;
      return { kind: 'disc', index: n, key: normalize(name.replace(re, ' ')) };
    }
  }
  // Paquet PS3 / PSP / Vita sans mention : rattaché au jeu du même numéro de série s'il existe.
  if (extension(fileName) === 'pkg') {
    const serial = serialOf(name);
    if (serial) return { kind: 'dlc', serial, title: dlcTitle(name), weak: true };
  }
  return null;
}

/** Titre d'un DLC ou d'une mise à jour sans ses mentions, normalisé (comparé au début des titres des jeux). */
function dlcTitle(name) {
  return normalize(titleFromFileName(`${name.replace(UPDATE_RE, ' ').replace(DLC_RE, ' ')}.x`));
}

/**
 * Titre d'un DLC ou d'une mise à jour appartenant au jeu [game] : même titre, ou titre du jeu suivi
 * d'autre chose qu'un numéro de suite (« Mario Kart 8 Pack 1 » oui, « Star Sky 2 » ou « Swords &
 * Soldiers II » non : ce sont d'autres jeux).
 */
export function sameGameTitle(part, game) {
  if (part === game) return true;
  if (!part.startsWith(`${game} `)) return false;
  return !/^(?:\d|[ivx]+(?:\s|$)|\+)/.test(part.slice(game.length + 1));
}

/** Code produit Wii U ou GameCube entre crochets (« [ATZP7P] »), qui distingue deux jeux de titres voisins. */
function productCode(name) {
  return /\[([A-Z0-9]{6})\]/.exec(name)?.[1] || null;
}

function serialOf(name) {
  const m = SERIAL_RE.exec(name.toUpperCase());
  return m ? `${m[1]}${m[2]}` : null;
}

/**
 * Regroupement automatique d'une liste de fichiers [{ id, fileName, manual, pkg }] : renvoie, pour chaque
 * fichier non manuel, { parentId, kind, index } (parentId null : jeu à part entière). Les fichiers
 * manuels ne sont pas modifiés mais peuvent recevoir des parties s'ils ne sont pas eux-mêmes rattachés
 * ([manualParentId] : leur rattachement).
 */
export function autoGroups(files) {
  const result = new Map();
  const auto = files.filter((f) => !f.manual);
  for (const f of auto) result.set(f.id, { parentId: null, kind: null, index: null });
  const infos = new Map(auto.map((f) => [f.id, partInfo(f.fileName, f.pkg)]));

  // Disques : même nom sans le numéro, au moins deux fichiers ; le plus petit numéro est le jeu.
  // Un jeu réglé à la main (non rattaché) peut recevoir les autres disques, mais n'est pas déplacé.
  const discs = new Map();
  for (const f of files) {
    if (f.manual && f.manualParentId != null) continue;
    const info = f.manual ? partInfo(f.fileName, f.pkg) : infos.get(f.id);
    if (info?.kind === 'disc') discs.set(info.key, [...(discs.get(info.key) || []), { ...f, index: info.index }]);
  }
  for (const group of discs.values()) {
    if (group.length < 2) continue;
    group.sort((a, b) => a.index - b.index || a.fileName.length - b.fileName.length || a.fileName.localeCompare(b.fileName));
    for (const part of group.slice(1)) {
      if (!part.manual) result.set(part.id, { parentId: group[0].id, kind: 'disc', index: part.index });
    }
  }

  // Jeux pouvant recevoir des DLC : ni disque rattaché, ni DLC, ni rattachés à la main.
  const games = files.filter((f) => {
    if (f.manual) return f.manualParentId == null;
    const info = infos.get(f.id);
    return result.get(f.id).parentId === null && (!info || info.kind === 'disc');
  });
  const gameInfo = games.map((g) => {
    const name = base(g.fileName);
    const wiiu = WIIU_ID_RE.exec(name);
    return { id: g.id, wiiu: wiiu && wiiu[1] === '0' ? wiiu[2].toLowerCase() : null, serial: serialOf(name), code: productCode(name), title: normalize(titleFromFileName(g.fileName)) };
  });
  for (const f of auto) {
    const info = infos.get(f.id);
    if (!info || info.kind === 'disc') continue;
    const others = gameInfo.filter((g) => g.id !== f.id);
    const parent =
      (info.wiiu && others.find((g) => g.wiiu === info.wiiu)) ||
      (info.serial && others.find((g) => g.serial === info.serial)) ||
      (!info.weak && info.title &&
        others
          .filter((g) => g.title && sameGameTitle(info.title, g.title) && !(info.code && g.code && info.code !== g.code))
          .sort((a, b) => b.title.length - a.title.length)[0]);
    if (parent) result.set(f.id, { parentId: parent.id, kind: info.kind, index: null });
  }
  return result;
}

/** Applique le regroupement automatique aux fichiers d'un système. */
export function groupSystem(systemId) {
  const dir = systemDir(requireSystem(systemId));
  const rows = db.prepare('SELECT id, file_name, size, mtime, parent_id, group_mode FROM games WHERE system_id = ?').all(systemId);
  const groups = autoGroups(
    rows.map((r) => ({
      id: r.id,
      fileName: r.file_name,
      manual: r.group_mode === 'manual',
      manualParentId: r.parent_id,
      pkg: extension(r.file_name) === 'pkg' ? pkgHeader(path.join(dir, r.file_name), r.size, r.mtime) : null,
    })),
  );
  const current = new Map(rows.map((r) => [r.id, r]));
  const update = db.prepare('UPDATE games SET parent_id = ?, part_kind = ?, part_index = ? WHERE id = ?');
  transaction(() => {
    for (const [id, g] of groups) {
      const row = current.get(id);
      if (row.parent_id === g.parentId && (g.parentId === null || row.part_kind === g.kind)) continue;
      update.run(g.parentId, g.kind, g.index, id);
    }
  });
}

/**
 * Rattachement choisi dans l'interface web : { parentId, kind } rattache le fichier au jeu parentId
 * (même système) comme disque, mise à jour ou DLC ; { parentId: null } en fait un jeu à part entière ;
 * { auto: true } le rend au regroupement automatique.
 */
export function setGroup(id, input) {
  const row = db.prepare('SELECT * FROM games WHERE id = ?').get(Number(id));
  if (!row) throw new HttpError(404, 'errors.gameNotFound', { id });
  if (input?.auto) {
    db.prepare('UPDATE games SET group_mode = NULL WHERE id = ?').run(row.id);
    groupSystem(row.system_id);
    return;
  }
  const parentId = input?.parentId == null || input.parentId === '' ? null : Number(input.parentId);
  if (parentId === null) {
    db.prepare("UPDATE games SET parent_id = NULL, part_kind = NULL, part_index = NULL, group_mode = 'manual' WHERE id = ?").run(row.id);
    groupSystem(row.system_id);
    return;
  }
  const kind = input.kind || 'dlc';
  if (!PART_KINDS.includes(kind)) throw new HttpError(400, 'errors.unknownPartKind', { kind });
  const parent = db.prepare('SELECT * FROM games WHERE id = ?').get(parentId);
  if (!parent || parent.system_id !== row.system_id || parent.id === row.id) throw new HttpError(400, 'errors.invalidParent');
  if (parent.parent_id !== null) throw new HttpError(400, 'errors.parentIsPart');
  if (db.prepare('SELECT 1 FROM games WHERE parent_id = ?').get(row.id)) throw new HttpError(400, 'errors.gameHasParts');
  const index = kind === 'disc' ? Number(input.index) || partInfo(row.file_name)?.index || null : null;
  transaction(() => {
    db.prepare("UPDATE games SET parent_id = ?, part_kind = ?, part_index = ?, group_mode = 'manual' WHERE id = ?").run(parent.id, kind, index, row.id);
    // Le jeu qui reçoit la partie garde ce rôle aux scans suivants.
    db.prepare("UPDATE games SET group_mode = 'manual' WHERE id = ?").run(parent.id);
  });
}

/** Ordre des parties d'un jeu : disques (par numéro), mises à jour, DLC, puis par nom. */
export function compareParts(a, b) {
  return (
    PART_KINDS.indexOf(a.kind) - PART_KINDS.indexOf(b.kind) ||
    (a.index ?? 0) - (b.index ?? 0) ||
    a.fileName.localeCompare(b.fileName)
  );
}
