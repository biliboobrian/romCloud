// Jeux d'arcade (Neo Geo, CPS, MAME…) renommés : « fatal fury.zip » au lieu de « fatfury1.zip ».
// Les bases connaissent le nom court du jeu, pas le nom donné au fichier, et le CRC d'une archive
// recompressée ne correspond à rien. Le jeu est retrouvé d'après les CRC des fichiers de l'archive,
// comparés à ceux de la DAT XML de FinalBurn Neo (libretro-database).

const XML_URL = 'https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/fbneo-split/'
  + encodeURIComponent('FinalBurn Neo (ClrMame Pro XML, Arcade only).dat');
const CACHE_MS = 24 * 3600_000;
let cache = null; // { at, index }
let pending = null;

/** Systèmes dont les jeux sont des archives d'arcade (nom Libretro ou nom court). */
export function isArcadeSystem(system) {
  const names = `${system.libretroName || ''} ${system.shortname || ''} ${system.id || ''}`;
  return /arcade|neo ?geo|\bmame\b|fbneo|fba|\bcps[123]?\b|naomi|atomiswave/i.test(names);
}

const decode = (s) =>
  s.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');

/**
 * DAT XML : jeux par nom court ({ name, description, year, manufacturer, cloneOf, romCount }) et,
 * pour chaque CRC de ROM propre au jeu (hors fichiers « merge » partagés comme le BIOS), les jeux
 * qui la contiennent.
 */
export function parseArcadeXml(text) {
  const games = new Map();
  const byCrc = new Map();
  let game = null;
  for (const line of text.split('\n')) {
    const start = line.match(/<game name="([^"]+)"(?:[^>]*\scloneof="([^"]+)")?/);
    if (start) {
      game = { name: start[1], description: null, year: null, manufacturer: null, cloneOf: start[2] || null, romCount: 0 };
      games.set(game.name, game);
      continue;
    }
    if (!game) continue;
    let m;
    if ((m = line.match(/<description>(.*)<\/description>/))) game.description = decode(m[1]);
    else if ((m = line.match(/<year>(.*)<\/year>/))) game.year = decode(m[1]);
    else if ((m = line.match(/<manufacturer>(.*)<\/manufacturer>/))) game.manufacturer = decode(m[1]);
    else if (/<rom\s/.test(line) && !/\smerge="/.test(line)) {
      const crc = line.match(/\scrc="([0-9a-f]{8})"/i)?.[1].toLowerCase();
      if (!crc) continue;
      game.romCount++;
      const list = byCrc.get(crc);
      if (list) list.push(game.name);
      else byCrc.set(crc, [game.name]);
    } else if (/<\/game>/.test(line)) {
      game = null;
    }
  }
  return { games, byCrc };
}

/**
 * Jeu dont les ROM correspondent le mieux aux fichiers de l'archive ([entries] : { crc32 }) :
 * au moins la moitié des fichiers doivent être reconnus ; à égalité, le jeu au plus petit nombre
 * de ROM (le jeu exact plutôt qu'une version qui en ajoute), puis le jeu original plutôt qu'un clone.
 */
export function identifyArcade(entries, index) {
  const counts = new Map();
  for (const entry of entries) {
    for (const name of index.byCrc.get(String(entry.crc32).toLowerCase()) || []) counts.set(name, (counts.get(name) || 0) + 1);
  }
  let best = null;
  for (const [name, count] of counts) {
    const game = index.games.get(name);
    if (!best || count > best.count
      || (count === best.count && (game.romCount < best.game.romCount
        || (game.romCount === best.game.romCount && !game.cloneOf && best.game.cloneOf)))) {
      best = { game, count };
    }
  }
  if (!best || best.count < Math.max(1, Math.ceil(entries.length / 2))) return null;
  return best.game;
}

async function arcadeIndex() {
  if (cache && Date.now() - cache.at < CACHE_MS) return cache.index;
  pending ||= (async () => {
    const res = await fetch(XML_URL);
    if (!res.ok) throw new Error(`FBNeo DAT: HTTP ${res.status}`);
    const index = parseArcadeXml(await res.text());
    cache = { at: Date.now(), index };
    return index;
  })().finally(() => {
    pending = null;
  });
  return pending;
}

/** Jeu d'arcade correspondant aux fichiers d'une archive, ou null. */
export async function findArcadeGame(entries) {
  if (entries.length < 2) return null;
  return identifyArcade(entries, await arcadeIndex());
}
