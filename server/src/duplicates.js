// Détection des doublons dans un système :
//  - « identiques » : même contenu (même taille puis même MD5), quel que soit le nom ;
//  - « similaires » : même jeu (titre normalisé) mais fichiers différents (région, révision…).
// Pour chaque groupe, un fichier à conserver est proposé.
import { config } from './config.js';
import { db } from './db.js';
import { HttpError } from './http-error.js';
import { deleteGame, ensureHashes, rowToGame } from './library.js';
import { requireSystem } from './systems.js';

// Marques de copie ajoutées par les systèmes de fichiers ou les navigateurs.
const COPY_MARK_RE = /(\s*\((?:\d{1,2}|copy|copie)\))|(\s*[-_]\s*(?:copy|copie)(?:\s*\(\d+\))?)|(^(?:copy|copie) (?:of|de) )/i;
const BAD_DUMP_RE = /\((?:beta|proto|prototype|demo|sample|pirate|hack|unl)[^)]*\)|\[(?:b|h|o|t|f)\d*\]/i;
const REVISION_RE = /\((?:rev|v)\s*([\d.]+)\)/i;

// Codes de région ScreenScraper (SCRAPE_REGIONS) -> mots des noms No-Intro / TOSEC.
const REGION_WORDS = {
  fr: ['france', 'fr'],
  eu: ['europe', 'eur', 'eu'],
  wor: ['world'],
  us: ['usa', 'us'],
  jp: ['japan', 'jpn', 'jp'],
  ss: [],
};

export function hasCopyMark(fileName) {
  return COPY_MARK_RE.test(fileName.replace(/\.[^.]+$/, ''));
}

/** Titre de regroupement : minuscules, sans tags (région, révision…), sans marque de copie ni ponctuation. */
export function normalizeTitle(title) {
  return title
    .replace(/\.[a-z0-9]{1,4}$/i, '')
    .replace(COPY_MARK_RE, ' ')
    .replace(/\s*[([][^)\]]*[)\]]/g, ' ')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/^the\s+|,\s*the\b/g, '')
    .replace(/[^a-z0-9]+/g, '');
}

function regionRank(fileName) {
  const tags = [...fileName.matchAll(/\(([^)]*)\)/g)].map((m) => m[1].toLowerCase().split(/\s*,\s*/)).flat();
  const order = config.screenscraper.regions;
  for (let i = 0; i < order.length; i++) {
    if ((REGION_WORDS[order[i]] || []).some((w) => tags.includes(w))) return i;
  }
  return order.length;
}

/**
 * Classement des fichiers d'un groupe, du plus au moins intéressant à conserver.
 * Critères successifs : pas de marque de copie, pas de dump défectueux/démo, région préférée
 * (SCRAPE_REGIONS), révision la plus récente, déjà scrapé, ajouté le plus tôt.
 */
export function compareForKeep(a, b) {
  const criteria = [
    (g) => (hasCopyMark(g.fileName) ? 1 : 0),
    (g) => (BAD_DUMP_RE.test(g.fileName) ? 1 : 0),
    (g) => regionRank(g.fileName),
    (g) => -Number(g.fileName.match(REVISION_RE)?.[1] ?? 0),
    (g) => (g.scrapeStatus === 'ok' ? 0 : 1),
    (g) => g.id,
  ];
  for (const c of criteria) {
    const d = c(a) - c(b);
    if (d !== 0) return d;
  }
  return 0;
}

function group(games, reason) {
  const sorted = [...games].sort(compareForKeep);
  return { reason, keepId: sorted[0].id, games: sorted };
}

/**
 * Regroupe les jeux fournis. `hashOf(game)` renvoie le MD5 (ou null si non calculable).
 * Un fichier présent dans un groupe « identiques » n'est pas répété dans « similaires »
 * (seul le fichier conservé y représente le groupe).
 */
export async function findDuplicateGroups(games, hashOf) {
  // 1. Identiques : candidats de même taille, confirmés par MD5.
  const bySize = new Map();
  for (const g of games) bySize.set(g.size, [...(bySize.get(g.size) || []), g]);
  const exact = [];
  for (const sameSize of bySize.values()) {
    if (sameSize.length < 2) continue;
    const byHash = new Map();
    for (const g of sameSize) {
      const hash = await hashOf(g);
      if (!hash) continue;
      byHash.set(hash, [...(byHash.get(hash) || []), g]);
    }
    for (const list of byHash.values()) if (list.length > 1) exact.push(group(list, 'identical'));
  }

  // 2. Similaires : même titre normalisé, en ignorant les copies déjà couvertes ci-dessus.
  const redundant = new Set(exact.flatMap((g) => g.games.filter((x) => x.id !== g.keepId).map((x) => x.id)));
  const byTitle = new Map();
  for (const g of games) {
    if (redundant.has(g.id)) continue;
    const key = normalizeTitle(g.title || g.fileName) || normalizeTitle(g.fileName);
    if (!key) continue;
    byTitle.set(key, [...(byTitle.get(key) || []), g]);
  }
  const similar = [...byTitle.values()].filter((l) => l.length > 1).map((l) => group(l, 'similar'));

  const byTitleAsc = (a, b) => a.games[0].title.localeCompare(b.games[0].title, 'fr');
  return { identical: exact.sort(byTitleAsc), similar: similar.sort(byTitleAsc) };
}

/** Doublons d'un système (calcule et mémorise les MD5 manquants des fichiers de même taille). */
export async function systemDuplicates(systemId) {
  requireSystem(systemId);
  const rows = db.prepare('SELECT * FROM games WHERE system_id = ?').all(systemId);
  const rowById = new Map(rows.map((r) => [r.id, r]));
  const games = rows.map(rowToGame);
  let unhashed = 0;
  const result = await findDuplicateGroups(games, async (g) => {
    const row = await ensureHashes(rowById.get(g.id));
    if (!row.md5) unhashed++;
    return row.md5 || null;
  });
  return {
    ...result,
    // Fichiers trop gros pour être comparés (HASH_MAX_MB) : seulement dans « similaires ».
    unhashed,
    hashMaxMb: config.hashMaxMb,
  };
}

/** Supprime (fichier + fiche + médias) les jeux indiqués, qui doivent appartenir au système. */
export function deleteGamesOfSystem(systemId, ids) {
  requireSystem(systemId);
  if (!Array.isArray(ids) || !ids.length) throw new HttpError(400, 'Aucun jeu indiqué');
  const owned = new Set(
    db.prepare('SELECT id FROM games WHERE system_id = ?').all(systemId).map((r) => r.id),
  );
  const invalid = ids.filter((id) => !owned.has(Number(id)));
  if (invalid.length) throw new HttpError(400, `Jeux hors de ce système : ${invalid.join(', ')}`);
  let freed = 0;
  for (const id of ids) {
    freed += db.prepare('SELECT size FROM games WHERE id = ?').get(Number(id))?.size ?? 0;
    deleteGame(Number(id));
  }
  return { deleted: ids.length, freed };
}
