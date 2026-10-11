// Import groupé des licences des jeux en .pkg depuis une liste TSV (format NoPayStation : « Title ID »,
// « Region », « Name », « PKG direct link », « zRIF » ou « RAP », « Content ID », …) :
//  - zrif : jeux PS Vita (licence proposée aux applications pour installer le .pkg dans Vita3K) ;
//  - rap : jeux et DLC PS3 (licence écrite dans RPCS3 avant le lancement).
import { db, transaction } from './db.js';
import { HttpError } from './http-error.js';
import { normalizeRap, normalizeZrif } from './library.js';
import { serialFromFileName, serialKey } from './scraper/serial.js';
import { requireSystem } from './systems.js';

/** Types de licence : colonne de la table games, en-tête de la liste, mise en forme (erreur si invalide). */
export const LICENSE_KINDS = {
  zrif: { column: 'zrif', header: 'zRIF', normalize: normalizeZrif },
  rap: { column: 'rap', header: 'RAP', normalize: normalizeRap },
};

function licenseKind(kind) {
  const def = LICENSE_KINDS[kind];
  if (!def) throw new HttpError(400, 'errors.unknownLicenseKind', { kind });
  return def;
}

// Identifiant de contenu complet (« EP0001-PCSB00079_00-RAYMANORIGINS000 ») dans un nom de fichier.
const CONTENT_ID = /[A-Z]{2}\d{4}-[A-Z]{4}\d{5}_\d{2}-[A-Z0-9]{16}/i;

/** Identifiant de contenu en majuscules lu dans [text] ; null s'il n'y en a pas. */
export const contentIdOf = (text) => String(text || '').match(CONTENT_ID)?.[0].toUpperCase() ?? null;

/**
 * Lignes de la liste TSV ayant une licence [kind] valide : { titleId, contentId, name, license }.
 * Colonnes repérées par leur en-tête ; licences absentes (« MISSING », vide) ou invalides ignorées.
 */
export function parseLicenseTsv(text, kind) {
  const def = licenseKind(kind);
  const lines = String(text || '').replace(/^﻿/, '').split(/\r?\n/).filter((l) => l.trim());
  if (!lines.length) throw new HttpError(400, 'errors.licenseListEmpty');
  const header = lines[0].split('\t').map((h) => h.trim().toLowerCase());
  const col = (name) => header.indexOf(name.toLowerCase());
  const cols = { titleId: col('title id'), name: col('name'), license: col(def.header), contentId: col('content id') };
  if (cols.license < 0 || (cols.titleId < 0 && cols.contentId < 0)) {
    throw new HttpError(400, 'errors.licenseListColumns', { column: def.header });
  }
  const entries = [];
  for (const line of lines.slice(1)) {
    const cells = line.split('\t');
    const cell = (i) => (i >= 0 ? (cells[i] ?? '').trim() : '');
    let license;
    try {
      license = def.normalize(cell(cols.license));
    } catch {
      license = null;
    }
    if (!license) continue;
    entries.push({
      titleId: serialKey(cell(cols.titleId)) || null,
      contentId: contentIdOf(cell(cols.contentId)),
      name: cell(cols.name) || null,
      license,
    });
  }
  return entries;
}

/**
 * Licences [kind] de la liste [text] enregistrées sur les jeux du système [systemId] : jeu ou DLC
 * reconnu par l'identifiant de contenu de son nom de fichier, sinon (jeu principal seulement) par son
 * Title ID (« PCSB00079 », « NPEB00113 »). Licence déjà différente gardée sauf si [replace].
 * Rapport : { entries, updated, unchanged, kept, notFound } (jeux : { id, title, fileName }).
 */
export function importLicenseList(systemId, kind, text, { replace = false } = {}) {
  requireSystem(systemId);
  const { column } = licenseKind(kind);
  const entries = parseLicenseTsv(text, kind);
  const byContent = new Map();
  const byTitle = new Map();
  for (const e of entries) {
    if (e.contentId && !byContent.has(e.contentId)) byContent.set(e.contentId, e);
    if (e.titleId && !byTitle.has(e.titleId)) byTitle.set(e.titleId, e);
  }
  const rows = db
    .prepare(`SELECT id, title, file_name, parent_id, ${column} AS license FROM games WHERE system_id = ? ORDER BY title COLLATE NOCASE, file_name`)
    .all(systemId);
  const report = { entries: entries.length, updated: [], unchanged: [], kept: [], notFound: [] };
  const save = db.prepare(`UPDATE games SET ${column} = ?, updated_at = datetime('now') WHERE id = ?`);
  transaction(() => {
    for (const row of rows) {
      const game = { id: row.id, title: row.title, fileName: row.file_name };
      const contentId = contentIdOf(row.file_name);
      const titleId = row.parent_id == null ? serialKey(serialFromFileName(row.file_name)) : '';
      const entry = (contentId && byContent.get(contentId)) || (titleId && byTitle.get(titleId)) || null;
      if (!entry) {
        // Mise à jour ou disque rattaché sans licence propre : pas signalée comme introuvable.
        if (row.parent_id == null) report.notFound.push(game);
        continue;
      }
      if (row.license === entry.license) report.unchanged.push(game);
      else if (row.license && !replace) report.kept.push(game);
      else {
        save.run(entry.license, row.id);
        report.updated.push(game);
      }
    }
  });
  return report;
}
