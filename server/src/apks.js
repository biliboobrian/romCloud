// APK des émulateurs Android : fichiers stockés dans data/apks/, proposés par les applications
// quand un émulateur n'est pas installé (à la place ou en plus du Play Store).
// Un seul APK par paquet : un nouvel envoi remplace le précédent.
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';
import { db } from './db.js';
import { HttpError } from './http-error.js';
import { readApkInfo } from './apk-manifest.js';
import { hashFile } from './library.js';
import { listSystems } from './systems.js';

export const apksDir = () => path.join(config.dataDir, 'apks');

const rowToApk = (r) => ({
  id: r.id,
  packageName: r.package_name,
  label: r.label,
  versionName: r.version_name,
  versionCode: r.version_code,
  size: r.size,
  md5: r.md5,
  fileName: r.file_name,
  addedAt: r.added_at,
});

/** Paquet Android d'un modèle d'émulateur (option -n paquet/activité ou -p paquet). */
export function packageOfPlayer(player) {
  const args = player.amStartArguments || '';
  const m = /(?:^|\s)-n\s+([\w.]+)\//.exec(args) || /(?:^|\s)-p\s+([\w.]+)/.exec(args);
  return m ? m[1] : null;
}

/**
 * Nom d'émulateur lisible à partir du nom d'un modèle Daijishou, de la forme
 * « <système> - <émulateur> - <cœur> » : « psx - RetroArch 64 - swanstation » -> « RetroArch 64 »,
 * « psp - PPSSPP » -> « PPSSPP », « Flycast (dev build) » -> « Flycast ».
 */
export function emulatorName(playerName) {
  const parts = String(playerName || '').split(' - ').map((p) => p.trim()).filter(Boolean);
  if (parts.length > 1 && /^[a-z0-9_]+$/.test(parts[0])) parts.shift();
  return (parts[0] || '').replace(/\s*\(.*\)\s*$/, '').trim();
}

/**
 * Émulateurs utilisés par les systèmes du serveur, regroupés par paquet :
 * [{ packageName, names: [noms des modèles], systems: [{ id, name }], apkId }].
 */
export function emulatorPackages() {
  const byPackage = new Map();
  for (const system of listSystems()) {
    for (const player of system.players) {
      const pkg = packageOfPlayer(player);
      if (!pkg) continue;
      const entry = byPackage.get(pkg) || { packageName: pkg, names: [], systems: [] };
      const name = emulatorName(player.name) || pkg;
      if (!entry.names.some((n) => n.toLowerCase() === name.toLowerCase())) entry.names.push(name);
      if (!entry.systems.some((s) => s.id === system.id)) entry.systems.push({ id: system.id, name: system.name });
      byPackage.set(pkg, entry);
    }
  }
  const apks = new Map(listApks().map((a) => [a.packageName, a.id]));
  return [...byPackage.values()]
    .map((e) => ({ ...e, apkId: apks.get(e.packageName) ?? null }))
    // APK présents d'abord, puis les émulateurs utilisés par le plus de systèmes.
    .sort((a, b) => (a.apkId == null) - (b.apkId == null) || b.systems.length - a.systems.length || a.names[0].localeCompare(b.names[0]));
}

/** Nom lisible par défaut : celui des modèles d'émulateurs qui utilisent ce paquet. */
function defaultLabel(packageName, fileName) {
  const known = emulatorPackages().find((e) => e.packageName === packageName);
  return known?.names[0] || path.basename(fileName, path.extname(fileName));
}

export function listApks() {
  return db.prepare('SELECT * FROM apks ORDER BY label COLLATE NOCASE').all().map(rowToApk);
}

export function requireApk(id) {
  const row = db.prepare('SELECT * FROM apks WHERE id = ?').get(Number(id));
  if (!row) throw new HttpError(404, 'errors.apkNotFound', { id });
  return row;
}

export const apkFilePath = (row) => path.join(apksDir(), `${row.package_name}.apk`);

/** Enregistre un APK envoyé (fichier temporaire) : paquet et version lus dans son manifeste. */
export async function addApk(tempPath, originalName) {
  let info;
  try {
    info = readApkInfo(tempPath);
  } catch (err) {
    fs.rmSync(tempPath, { force: true });
    throw new HttpError(400, 'errors.invalidApk', { name: originalName, detail: err.message });
  }
  const dest = path.join(apksDir(), `${info.packageName}.apk`);
  fs.mkdirSync(apksDir(), { recursive: true });
  fs.rmSync(dest, { force: true });
  fs.renameSync(tempPath, dest);
  const { md5 } = await hashFile(dest);
  const size = fs.statSync(dest).size;
  const existing = db.prepare('SELECT label FROM apks WHERE package_name = ?').get(info.packageName);
  db.prepare(
    `INSERT INTO apks (package_name, label, version_name, version_code, size, md5, file_name) VALUES (?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(package_name) DO UPDATE SET version_name = excluded.version_name, version_code = excluded.version_code,
       size = excluded.size, md5 = excluded.md5, file_name = excluded.file_name, added_at = datetime('now')`,
  ).run(info.packageName, existing?.label || defaultLabel(info.packageName, originalName), info.versionName, info.versionCode, size, md5, path.basename(originalName));
  return rowToApk(db.prepare('SELECT * FROM apks WHERE package_name = ?').get(info.packageName));
}

export function updateApk(id, { label }) {
  const row = requireApk(id);
  const value = String(label ?? '').trim();
  if (!value) throw new HttpError(400, 'errors.nameRequired');
  db.prepare('UPDATE apks SET label = ? WHERE id = ?').run(value, row.id);
  return rowToApk(requireApk(id));
}

export function deleteApk(id) {
  const row = requireApk(id);
  fs.rmSync(apkFilePath(row), { force: true });
  db.prepare('DELETE FROM apks WHERE id = ?').run(row.id);
}
