import { I18nError } from '../i18n.js';
import { compactDetails } from './details.js';
import { serialFromFileName, serialKey } from './serial.js';

// Scraper basé sur les miniatures Libretro (https://thumbnails.libretro.com) :
// gratuit et sans compte, fournit jaquette, capture et écran-titre (pas de texte).
// Les fiches « metadat » de libretro-database complètent par CRC, nom ou numéro de série :
// développeur, éditeur, genre, date, joueurs, ESRB, série, vibrations, stick analogique.

const BASE = 'https://thumbnails.libretro.com';
const DB = 'https://raw.githubusercontent.com/libretro/libretro-database/master';
// Emplacements possibles de la DAT d'un système, selon sa provenance (No-Intro, Redump, arcade…).
const DAT_DIRS = ['metadat/no-intro', 'metadat/redump', 'dat', 'metadat/fbneo-split', 'metadat/mame', 'metadat/tosec'];
const CACHE_MS = 6 * 3600_000;
const listingCache = new Map(); // "<system>/<type>" -> { at, names: string[] }
const datCache = new Map(); // "<system>" -> { at, index: { byRom, byCrc, meta } }
const metaCache = new Map(); // "<system>" -> { at, files: { <fichier>: index } }
// Fiches metadat/<dossier>/<système>.dat lues pour les informations détaillées.
const META_FILES = ['developer', 'publisher', 'genre', 'releaseyear', 'releasemonth', 'maxusers', 'esrb', 'franchise', 'serial', 'rumble', 'analog'];

/** Règle de nommage Libretro : ces caractères sont remplacés par "_". */
export function libretroName(name) {
  return name.replace(/[&*/:`<>?\\|"]/g, '_');
}

export function normalize(name) {
  return name
    .replace(/\.[a-z0-9]+$/i, '')
    .replace(/\s*[([][^)\]]*[)\]]/g, '')
    // Version à la TOSEC : "Bomberman Online v1.004 (2001)(Sega)…"
    .replace(/\s+v\d+(?:\.\d+)*\s*$/i, '')
    .toLowerCase()
    .replace(/^the\s+|,\s*the\b/g, '')
    .replace(/[^a-z0-9]+/g, '');
}

/** Chaque titre d'un nom à titres multiples ("Titre JP ~ Titre US"), normalisé. */
function alternateTitles(name) {
  const base = name.replace(/\.[a-z0-9]+$/i, '').replace(/\s*[([][^)\]]*[)\]]/g, '');
  return base.split(' ~ ').map(normalize).filter(Boolean);
}

const REGION_PRIORITY = ['france', 'europe', 'world', 'usa', 'japan'];

function regionScore(name) {
  const lower = name.toLowerCase();
  const i = REGION_PRIORITY.findIndex((r) => lower.includes(`(${r}`) || lower.includes(`, ${r}`));
  return i === -1 ? REGION_PRIORITY.length : i;
}

/** Titre lisible depuis un nom Libretro : "Legend of Zelda, The - A Link to the Past (USA)". */
export function titleFromLibretroName(name) {
  const title = name
    .replace(/\s*[([][^)\]]*[)\]]/g, '')
    .replace(/_ /g, ': ')
    .replace(/^(.+?), (The|A|An)\b/, '$2 $1')
    .replace(/\s+/g, ' ')
    .trim();
  return title || null;
}

async function listing(system, type) {
  const key = `${system}/${type}`;
  const cached = listingCache.get(key);
  if (cached && Date.now() - cached.at < CACHE_MS) return cached.names;
  const res = await fetch(`${BASE}/${encodeURIComponent(system)}/${type}/`);
  if (!res.ok) return [];
  const html = await res.text();
  const names = [...html.matchAll(/href="([^"]+\.png)"/g)].map((m) => decodeURIComponent(m[1]).replace(/\.png$/, ''));
  listingCache.set(key, { at: Date.now(), names });
  return names;
}

/**
 * Index d'une DAT Libretro : nom de ROM sans extension ("2020bb" -> "2020 Super Baseball (set 1)")
 * et CRC32 ("6F5C315D" -> "Rayman 2 (USA) (En,Fr,De,Es,It)").
 */
export function parseDat(text) {
  const byRom = new Map();
  const byCrc = new Map();
  let game = null;
  for (const line of text.split('\n')) {
    if (/^game \(/.test(line)) game = null;
    const name = line.match(/^\s+name "(.+)"\s*$/);
    if (name && !game) game = name[1];
    const rom = line.match(/^\s+rom \( name (?:"([^"]+)"|(\S+))/);
    if (rom && game) {
      const key = (rom[1] ?? rom[2]).replace(/\.[^.]+$/, '').toLowerCase();
      if (!byRom.has(key)) byRom.set(key, game);
      const crc = line.match(/ crc ([0-9a-f]{8})\b/i);
      if (crc && !byCrc.has(crc[1].toUpperCase())) byCrc.set(crc[1].toUpperCase(), game);
    }
  }
  return { byRom, byCrc };
}

/**
 * Fiches d'une DAT « metadat » (ou de la DAT principale) : champs de chaque jeu, indexés par
 * nom de jeu en minuscules (« comment » ou « name »), CRC32 et numéro de série.
 */
export function parseMetaDat(text) {
  const index = { byName: new Map(), byCrc: new Map(), bySerial: new Map(), byTitle: new Map() };
  let entry = null;
  const flush = () => {
    if (!entry) return;
    const serial = entry.serial || entry.fields.serial;
    if (entry.name && !index.byName.has(entry.name.toLowerCase())) index.byName.set(entry.name.toLowerCase(), entry);
    // Titre sans les tags : dernier recours (nom de fichier qui ne suit pas la DAT).
    const title = entry.name && normalize(entry.name);
    if (title && !index.byTitle.has(title)) index.byTitle.set(title, entry);
    if (entry.crc && !index.byCrc.has(entry.crc)) index.byCrc.set(entry.crc, entry);
    // Numéro de série sous sa forme compacte (« PCSB00040 ») : retrouvé quelle que soit son écriture.
    if (serial && !index.bySerial.has(serialKey(serial))) index.bySerial.set(serialKey(serial), entry);
  };
  for (const line of text.split('\n')) {
    if (/^game \(/.test(line)) {
      flush();
      entry = { name: null, crc: null, serial: null, fields: {} };
      continue;
    }
    if (!entry) continue;
    const rom = line.match(/^\s+rom \((.*)\)\s*$/);
    if (rom) {
      entry.crc ||= rom[1].match(/\bcrc ([0-9a-f]{8})\b/i)?.[1].toUpperCase() ?? null;
      entry.serial ||= rom[1].match(/\bserial "([^"]+)"/)?.[1] ?? null;
      continue;
    }
    const field = line.match(/^\s+(\w+) (?:"(.*)"|(\S+))\s*$/);
    if (!field) continue;
    const [, key, quoted, plain] = field;
    if ((key === 'comment' || key === 'name') && !entry.name) entry.name = quoted ?? plain;
    else if (!(key in entry.fields)) entry.fields[key] = quoted ?? plain;
  }
  flush();
  return index;
}

const emptyMeta = () => ({ byName: new Map(), byCrc: new Map(), bySerial: new Map(), byTitle: new Map() });

async function datIndex(system) {
  const cached = datCache.get(system);
  if (cached && Date.now() - cached.at < CACHE_MS) return cached.index;
  let index = { byRom: new Map(), byCrc: new Map(), meta: emptyMeta() };
  for (const dir of DAT_DIRS) {
    const res = await fetch(`${DB}/${dir}/${encodeURIComponent(system)}.dat`);
    if (!res.ok) continue;
    const text = await res.text();
    index = { ...parseDat(text), meta: parseMetaDat(text) };
    break;
  }
  datCache.set(system, { at: Date.now(), index });
  return index;
}

async function exists(url) {
  const res = await fetch(url, { method: 'HEAD' });
  return res.ok;
}

async function findImage(system, type, baseName) {
  const exact = libretroName(baseName);
  const exactUrl = `${BASE}/${encodeURIComponent(system)}/${type}/${encodeURIComponent(exact)}.png`;
  if (await exists(exactUrl)) return { url: exactUrl, matched: exact };

  // Pas de correspondance exacte : recherche approximative sur le titre sans les tags.
  const wanted = normalize(baseName);
  if (!wanted) return null;
  const names = await listing(system, type);
  let candidates = names.filter((n) => normalize(n) === wanted);
  if (!candidates.length) {
    // Titres alternatifs No-Intro : "Bare Knuckle ~ Streets of Rage (World)" -> "Streets of Rage (World)".
    const parts = new Set(alternateTitles(baseName));
    candidates = names.filter((n) => alternateTitles(n).some((p) => parts.has(p)));
  }
  if (!candidates.length) return null;
  candidates.sort((a, b) => regionScore(a) - regionScore(b) || a.length - b.length);
  const best = candidates[0];
  return { url: `${BASE}/${encodeURIComponent(system)}/${type}/${encodeURIComponent(best)}.png`, matched: best };
}

/**
 * @returns {Promise<null | { title?: string, media: { boxart?: string, screenshot?: string } }>}
 */
async function findImages(system, baseName) {
  const box = await findImage(system, 'Named_Boxarts', baseName);
  const snap = await findImage(system, 'Named_Snaps', baseName);
  // Écran-titre : couverture de repli quand la base n'a pas de jaquette (ex. Amstrad - GX4000).
  const title = box ? null : await findImage(system, 'Named_Titles', baseName);
  return box || snap || title ? { box, snap, title } : null;
}

export async function scrapeLibretro({ system, fileName, crc32 }) {
  if (!system.libretroName) throw new I18nError('scrape.libretroNotConfigured');
  const baseName = fileName.replace(/\.[^.]+$/, '');
  let found = await findImages(system.libretroName, baseName);
  // Numéro de release en tête ("0202 - Kirby - Power Paintbrush (E)") : on réessaie sans. Seulement en
  // repli, car certains titres commencent par un nombre ("1943 - The Battle of Midway").
  const unnumbered = baseName.replace(/^\d+\s+-\s+/, '');
  if (!found && unnumbered !== baseName) found = await findImages(system.libretroName, unnumbered);
  if (!found) {
    // Nom introuvable : la DAT du système donne le titre à partir du CRC (ROM renommée ou nom
    // No-Intro obsolète), ou du nom court façon MAME ("2020bb.zip").
    const { byRom, byCrc, meta } = await datIndex(system.libretroName);
    const gameName = (crc32 && byCrc.get(crc32.toUpperCase())) || byRom.get(baseName.toLowerCase());
    if (gameName) found = await findImages(system.libretroName, gameName);
    // Nom qui n'est pas un titre (identifiant de contenu PS Vita « EP0001-PCSB00040_00-… ») :
    // numéro de série du nom de fichier.
    const serial = serialFromFileName(fileName);
    const bySerial = serial && meta.bySerial.get(serialKey(serial))?.name;
    if (!found && bySerial) found = await findImages(system.libretroName, bySerial);
  }
  if (!found) return null;
  const { box, snap, title } = found;
  return {
    title: titleFromLibretroName((box ?? snap ?? title).matched),
    media: {
      boxart: box?.url ?? title?.url ?? snap?.url,
      screenshot: snap?.url ?? title?.url,
    },
  };
}

async function metaFiles(system) {
  const cached = metaCache.get(system);
  if (cached && Date.now() - cached.at < CACHE_MS) return cached.files;
  const files = {};
  await Promise.all(META_FILES.map(async (name) => {
    try {
      const res = await fetch(`${DB}/metadat/${name}/${encodeURIComponent(system)}.dat`);
      files[name] = res.ok ? parseMetaDat(await res.text()) : emptyMeta();
    } catch {
      files[name] = emptyMeta();
    }
  }));
  metaCache.set(system, { at: Date.now(), files });
  return files;
}

/**
 * Informations de libretro-database sur le jeu (identifié par CRC32, sinon par son nom de
 * fichier No-Intro / Redump) ; null si le système n'a pas de nom Libretro ou si rien n'est trouvé.
 */
export async function libretroMetadata({ system, fileName, crc32 }) {
  if (!system.libretroName) return null;
  const base = fileName.replace(/\.[^.]+$/, '').toLowerCase();
  const crc = crc32 ? crc32.toUpperCase() : null;
  const { meta: main } = await datIndex(system.libretroName);
  const fileSerial = serialFromFileName(fileName);
  const game = (crc && main.byCrc.get(crc)) || main.byName.get(base) || main.byTitle.get(normalize(fileName))
    || (fileSerial && main.bySerial.get(serialKey(fileSerial))) || null;
  const names = [...new Set([game?.name?.toLowerCase(), base].filter(Boolean))];
  const serial = game?.serial || game?.fields.serial || fileSerial;
  const files = await metaFiles(system.libretroName);
  const find = (index) =>
    (crc && index.byCrc.get(crc)) || names.map((n) => index.byName.get(n)).find(Boolean) || (serial && index.bySerial.get(serialKey(serial))) || null;
  const value = (file, key) => find(files[file])?.fields[key] ?? null;

  // Année : fiche « releaseyear », sinon la DAT principale (TOSEC la donne souvent).
  const releaseYear = value('releaseyear', 'releaseyear') || game?.fields.releaseyear || null;
  const month = value('releasemonth', 'releasemonth');
  const users = Number(value('maxusers', 'users'));
  const esrb = value('esrb', 'esrb_rating');
  const meta = {
    title: game?.name ? titleFromLibretroName(game.name) : null,
    developer: value('developer', 'developer'),
    publisher: value('publisher', 'publisher'),
    genre: value('genre', 'genre'),
    releaseDate: releaseYear ? (month ? `${releaseYear}-${String(month).padStart(2, '0')}` : releaseYear) : null,
    players: users > 1 ? `1-${users}` : users === 1 ? '1' : null,
    details: compactDetails({
      series: value('franchise', 'franchise'),
      ageRatings: esrb ? [{ type: 'ESRB', text: esrb }] : [],
      serial: serial || find(files.serial)?.serial || value('serial', 'serial'),
      regions: game?.fields.region ? [game.fields.region] : [],
      rumble: value('rumble', 'rumble') === '1' ? true : null,
      analog: value('analog', 'analog') === '1' ? true : null,
    }),
  };
  const found = Object.entries(meta).some(([k, v]) => (k === 'details' ? Object.keys(v).length : v));
  return found ? meta : null;
}
