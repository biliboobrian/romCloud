// Version des jeux PC (jeux en dossiers), affichée dans les fiches : les applications signalent un
// jeu téléchargé dont les fichiers ont changé sur le serveur (nouvelle version) et le mettent à jour.
// Devinée, tant qu'elle n'a pas été choisie dans la fiche du jeu :
//  1. dans le nom du dossier (« Jeu v1.2.3-GOG », « Jeu.Build.12345678-TENOKE », « Jeu (1.0.4) ») ;
//  2. sinon dans l'exécutable du jeu (ressource de version de Windows : « ProductVersion », sinon
//     « FileVersion »), sauf pour les jeux Unity et Unreal Engine, dont l'exécutable porte la version
//     du moteur (« 2022.3.10.1 », « 4.27.2.0 ») et pas celle du jeu.
// Recherche en tâche de fond après le scan, refaite quand les fichiers du jeu changent.
import fs from 'node:fs';
import path from 'node:path';
import { db } from './db.js';
import { readPackManifest, packExtension, packFormat } from './folder-games.js';
import { packedStream } from './folder-pack.js';

/** Section des ressources (.rsrc) lue au plus (icônes comprises). */
const MAX_RESOURCES = 64 * 1024 * 1024;
/** Exécutable compressé décompressé au plus pour y lire sa version. */
const MAX_PACKED_EXE = 256 * 1024 * 1024;

/** Versions sans intérêt (valeurs par défaut des outils de compilation). */
const DEFAULT_VERSIONS = new Set(['0', '1', '1.0']);

/**
 * Version lisible : espaces retirés, zéros de fin au-delà de deux nombres retirés (« 1.2.0.0 » ->
 * « 1.2 », « 1.0.3.0 » -> « 1.0.3 ») ; null pour une version par défaut (« 1.0.0.0 », « 0.0.0.0 »).
 */
export function cleanVersion(value) {
  let v = String(value ?? '').replace(/\0/g, '').trim().replace(/\s+/g, ' ');
  if (!/\d/.test(v) || v.length > 40) return null;
  const numeric = /^(\d+(?:[.,]\d+)*)$/.exec(v.replace(/,\s*/g, '.'));
  if (numeric) {
    const parts = numeric[1].split('.');
    while (parts.length > 2 && /^0+$/.test(parts.at(-1))) parts.pop();
    v = parts.join('.');
    if (DEFAULT_VERSIONS.has(v.replace(/\.0+$/, '')) || /^0+(\.0+)*$/.test(v)) return null;
  }
  return v;
}

/** Version dans le nom du dossier d'un jeu (« Jeu v1.2.3-GOG », « Jeu.Build.12345 »), ou null. */
export function versionFromName(name) {
  const s = String(name || '');
  const v = /(?:^|[\s._\-([])v(?:er(?:sion)?)?[\s._]?(\d+(?:\.\d+)+[a-z]?\d*|\d+[a-z]?)(?=$|[\s_\-)\]]|\.[A-Za-z])/i.exec(s);
  if (v) return cleanVersion(v[1]) ?? v[1];
  const build = /(?:^|[\s._\-([])build[\s._]?(\d{3,})(?=$|[\s._\-)\]])/i.exec(s);
  if (build) return `build ${build[1]}`;
  const bracket = /[([](\d+(?:\.\d+){1,3})[)\]]/.exec(s);
  return bracket ? (cleanVersion(bracket[1]) ?? bracket[1]) : null;
}

/** Données de la section des ressources (.rsrc) d'un exécutable Windows [exe] (Buffer), ou null. */
function resourceSection(readAt) {
  const dos = readAt(0, 64);
  if (dos.length < 64 || dos.readUInt16LE(0) !== 0x5a4d) return null; // « MZ »
  const pe = dos.readUInt32LE(0x3c);
  const header = readAt(pe, 24);
  if (header.length < 24 || header.readUInt32LE(0) !== 0x4550) return null; // « PE\0\0 »
  const sections = header.readUInt16LE(6);
  const optional = header.readUInt16LE(20);
  const table = readAt(pe + 24 + optional, sections * 40);
  for (let i = 0; i < sections && (i + 1) * 40 <= table.length; i++) {
    const name = table.toString('latin1', i * 40, i * 40 + 8).replace(/\0+$/, '');
    if (name !== '.rsrc') continue;
    const size = Math.min(table.readUInt32LE(i * 40 + 16), MAX_RESOURCES);
    return readAt(table.readUInt32LE(i * 40 + 20), size);
  }
  return null;
}

/** Chaîne de version [key] (« ProductVersion ») de la table des chaînes des ressources, ou null. */
function versionString(rsrc, key) {
  const needle = Buffer.from(`${key}\0`, 'utf16le');
  const at = rsrc.indexOf(needle);
  if (at < 0) return null;
  let i = at + needle.length;
  // Alignement sur 4 octets : zéros avant la valeur.
  while (i + 1 < rsrc.length && rsrc.readUInt16LE(i) === 0 && i < at + needle.length + 8) i += 2;
  let end = i;
  while (end + 1 < rsrc.length && rsrc.readUInt16LE(end) !== 0 && end - i < 128) end += 2;
  return end > i ? rsrc.toString('utf16le', i, end) : null;
}

/** Version fixe (VS_FIXEDFILEINFO, « 1.2.3.4 ») des ressources : produit, sinon fichier ; ou null. */
function fixedVersion(rsrc) {
  const at = rsrc.indexOf(Buffer.from([0xbd, 0x04, 0xef, 0xfe]));
  if (at < 0 || at + 24 > rsrc.length) return null;
  const quad = (ms, ls) => [ms >>> 16, ms & 0xffff, ls >>> 16, ls & 0xffff].join('.');
  return cleanVersion(quad(rsrc.readUInt32LE(at + 16), rsrc.readUInt32LE(at + 20))) ?? cleanVersion(quad(rsrc.readUInt32LE(at + 8), rsrc.readUInt32LE(at + 12)));
}

/** Version d'un exécutable Windows lue par [readAt] (position, longueur -> Buffer), ou null. */
export function exeVersionFrom(readAt) {
  const rsrc = resourceSection(readAt);
  if (!rsrc) return null;
  for (const key of ['ProductVersion', 'FileVersion']) {
    const v = cleanVersion(versionString(rsrc, key));
    if (v) return v;
  }
  return fixedVersion(rsrc);
}

/** Lecture d'un Buffer [buf] à la façon d'un fichier (position, longueur). */
const bufferReader = (buf) => (pos, len) => buf.subarray(Math.max(0, pos), Math.max(0, pos) + Math.max(0, len));

/** Version de l'exécutable [rel] du dossier de jeu [dir] (en clair ou compressé), ou null. */
export async function exeVersion(dir, rel) {
  const file = path.join(dir, ...rel.split('/'));
  if (fs.existsSync(file)) {
    const fd = fs.openSync(file, 'r');
    try {
      return exeVersionFrom((pos, len) => {
        const buf = Buffer.alloc(Math.max(0, len));
        const n = fs.readSync(fd, buf, 0, buf.length, pos);
        return buf.subarray(0, n);
      });
    } finally {
      fs.closeSync(fd);
    }
  }
  const packed = readPackManifest(dir)[rel];
  if (!packed || packed.size > MAX_PACKED_EXE) return null;
  const format = packFormat(packed.format);
  const chunks = [];
  for await (const chunk of packedStream(`${file}${packExtension(format)}`, format)) chunks.push(chunk);
  return exeVersionFrom(bufferReader(Buffer.concat(chunks)));
}

/** Jeu Unity ou Unreal Engine : l'exécutable porte la version du moteur, pas celle du jeu. */
export function isEngineExe(files, exe) {
  const paths = new Set(files.map((f) => f.path.toLowerCase()));
  const exeDir = exe.includes('/') ? exe.slice(0, exe.lastIndexOf('/') + 1).toLowerCase() : '';
  if (paths.has(`${exeDir}unityplayer.dll`) || paths.has('unityplayer.dll')) return true;
  return /-shipping\.exe$/i.test(exe) || [...paths].some((p) => p.startsWith('engine/binaries/') || p.startsWith('engine/content/'));
}

/** Version devinée d'un jeu en dossier [dir] (nom [name], exécutable [exe], fichiers [files]), ou null. */
export async function detectVersion(dir, name, exe, files) {
  const fromName = versionFromName(name);
  if (fromName) return fromName;
  if (!exe || isEngineExe(files, exe)) return null;
  return exeVersion(dir, exe).catch(() => null);
}

let running = null;
let again = false;

/**
 * Versions des jeux en dossiers à deviner (jamais devinées, ou fichiers changés depuis), en tâche de
 * fond, l'une après l'autre ; [listFiles] : fichiers d'un dossier (folderFiles), [dirOf] : dossier d'un jeu.
 */
export function queueVersionDetection({ listFiles, dirOf }) {
  if (running) {
    again = true;
    return running;
  }
  running = (async () => {
    do {
      again = false;
      const rows = db
        .prepare('SELECT * FROM games WHERE is_folder = 1 AND version_chosen = 0 AND (version_mtime IS NULL OR version_mtime != mtime)')
        .all();
      for (const row of rows) {
        let version = null;
        try {
          const dir = dirOf(row);
          version = await detectVersion(dir, row.file_name, row.exe, listFiles(dir));
        } catch {
          // Dossier illisible : pas de version.
        }
        db.prepare('UPDATE games SET version = ?, version_mtime = ? WHERE id = ? AND version_chosen = 0').run(version, row.mtime, row.id);
      }
    } while (again);
  })().finally(() => {
    running = null;
  });
  return running;
}
