import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { DatabaseSync } from 'node:sqlite';
import { config } from '../config.js';
import { readZipEntries } from '../zip.js';
import { compactDetails } from './details.js';
import { normalize } from './libretro.js';

// LaunchBox Games DB (https://gamesdb.launchbox-app.com) : base communautaire de jeux rétro,
// gratuite et sans compte, exportée chaque jour en un seul fichier (Metadata.zip, ~110 Mo).
// Elle donne résumé (en anglais), développeur, éditeur, genres, date, nombre de joueurs,
// coopération, classification ESRB, note de la communauté, vidéo, titres alternatifs et images.
// L'export est téléchargé au plus une fois par semaine et importé dans data/launchbox/launchbox.db
// (seulement les plateformes connues ci-dessous) ; un jeu y est ensuite cherché par son titre.

const EXPORT_URL = 'https://gamesdb.launchbox-app.com/Metadata.zip';
const IMAGES = 'https://images.launchbox-app.com';
const REFRESH_MS = 7 * 24 * 3600_000;
const dir = path.join(config.dataDir, 'launchbox');
const dbFile = path.join(dir, 'launchbox.db');

/** Plateformes LaunchBox de chaque système Daijishou (identifiant de la plateforme). */
export const LAUNCHBOX_PLATFORMS = {
  '3do': ['3DO Interactive Multiplayer'],
  '3ds': ['Nintendo 3DS'],
  amiga: ['Commodore Amiga', 'Commodore Amiga CD32'],
  appleii: ['Apple II', 'Apple IIGS'],
  arcadia: ['Emerson Arcadia 2001'],
  arduboy: ['Arduboy'],
  atari2600: ['Atari 2600'],
  atari5200: ['Atari 5200'],
  atari7800: ['Atari 7800'],
  atarist: ['Atari ST'],
  atomiswave: ['Sammy Atomiswave'],
  bbcmicro: ['BBC Microcomputer System'],
  c64: ['Commodore 64'],
  cdi: ['Philips CD-i'],
  channelf: ['Fairchild Channel F'],
  coleco: ['ColecoVision'],
  cpc: ['Amstrad CPC'],
  cps1: ['Arcade'],
  cps2: ['Arcade'],
  cps3: ['Arcade'],
  dos: ['MS-DOS'],
  dreamcast: ['Sega Dreamcast'],
  elektor: ['Elektor TV Games Computer'],
  fbneo: ['Arcade'],
  fds: ['Nintendo Famicom Disk System', 'Nintendo Entertainment System'],
  g7400: ['Philips Videopac+'],
  gamegear: ['Sega Game Gear'],
  gb: ['Nintendo Game Boy'],
  gba: ['Nintendo Game Boy Advance'],
  gbc: ['Nintendo Game Boy Color'],
  gc: ['Nintendo GameCube'],
  genesis: ['Sega Genesis'],
  genesismsu: ['Sega Genesis'],
  gw: ['Nintendo Game & Watch'],
  gx4000: ['Amstrad GX4000'],
  intellivision: ['Mattel Intellivision'],
  jaguar: ['Atari Jaguar'],
  jaguarcd: ['Atari Jaguar CD'],
  lynx: ['Atari Lynx'],
  mame: ['Arcade'],
  master: ['Sega Master System'],
  megaduck: ['Mega Duck'],
  model3: ['Sega Model 3'],
  msx: ['Microsoft MSX', 'Microsoft MSX2', 'Microsoft MSX2+'],
  n64: ['Nintendo 64'],
  naomi: ['Sega Naomi', 'Sega Naomi 2'],
  nds: ['Nintendo DS'],
  ndsi: ['Nintendo DS'],
  neogeo: ['SNK Neo Geo AES', 'SNK Neo Geo MVS', 'Arcade'],
  neogeocd: ['SNK Neo Geo CD'],
  nes: ['Nintendo Entertainment System'],
  ngage: ['Nokia N-Gage'],
  ngp: ['SNK Neo Geo Pocket'],
  ngpc: ['SNK Neo Geo Pocket Color'],
  odyssey2: ['Magnavox Odyssey 2'],
  pc88: ['NEC PC-8801'],
  pc98: ['NEC PC-9801'],
  pcfx: ['NEC PC-FX'],
  pet: ['Commodore PET'],
  pico: ['Sega Pico'],
  pico8: ['PICO-8'],
  plus4: ['Commodore Plus 4'],
  pokemini: ['Nintendo Pokemon Mini'],
  ps2: ['Sony Playstation 2'],
  ps3: ['Sony Playstation 3'],
  psp: ['Sony PSP'],
  pspminis: ['Sony PSP Minis'],
  psx: ['Sony Playstation'],
  satellaview: ['Nintendo Satellaview'],
  saturn: ['Sega Saturn'],
  scummvm: ['ScummVM'],
  sega32x: ['Sega 32X'],
  segacd: ['Sega CD'],
  sg1000: ['Sega SG-1000'],
  snes: ['Super Nintendo Entertainment System'],
  snesmsu1: ['Super Nintendo Entertainment System'],
  stv: ['Sega ST-V'],
  supergrafx: ['PC Engine SuperGrafx'],
  supervision: ['Watara Supervision'],
  switch: ['Nintendo Switch'],
  tg16: ['NEC TurboGrafx-16'],
  tgcd: ['NEC TurboGrafx-CD'],
  triforce: ['Sega Triforce'],
  uzebox: ['Uzebox'],
  vc4000: ['Interton VC 4000'],
  vectrex: ['GCE Vectrex'],
  vic20: ['Commodore VIC-20'],
  virtualboy: ['Nintendo Virtual Boy'],
  vita: ['Sony Playstation Vita'],
  wasm4: ['WASM-4'],
  wii: ['Nintendo Wii'],
  wiiu: ['Nintendo Wii U'],
  wiiware: ['Nintendo Wii'],
  ws: ['WonderSwan'],
  wsc: ['WonderSwan Color'],
  x1: ['Sharp X1'],
  x68000: ['Sharp X68000'],
  xbox: ['Microsoft Xbox'],
  xbox360: ['Microsoft Xbox 360'],
  zx81: ['Sinclair ZX-81'],
  zxspectrum: ['Sinclair ZX Spectrum'],
};

const KNOWN_PLATFORMS = new Set(Object.values(LAUNCHBOX_PLATFORMS).flat());
// Images gardées : jaquette, capture de jeu, écran-titre (repli de l'une ou l'autre).
const IMAGE_TYPES = new Set(['Box - Front', 'Screenshot - Gameplay', 'Screenshot - Game Title']);
// Région d'image LaunchBox de chaque code de région préféré (SCRAPE_REGIONS).
const IMAGE_REGIONS = { fr: ['France'], eu: ['Europe'], wor: ['World'], us: ['North America', 'United States'], jp: ['Japan'], ss: [] };

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };

export function decodeXml(text) {
  return text.replace(/&(#x[0-9a-f]+|#\d+|\w+);/gi, (m, e) => {
    if (e[0] === '#') return String.fromCodePoint(e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : Number(e.slice(1)));
    return ENTITIES[e] ?? m;
  });
}

/** Champs d'un élément (<Name>…</Name> -> { Name: … }) ; les éléments vides sont ignorés. */
export function xmlFields(block) {
  const fields = {};
  for (const [, key, value] of block.matchAll(/<(\w+)>([\s\S]*?)<\/\1>/g)) fields[key] = decodeXml(value.trim());
  return fields;
}

/**
 * Lit l'export par morceaux et appelle onElement(type, champs) pour chaque <Game>,
 * <GameAlternateName> et <GameImage> (le fichier XML décompressé fait ~500 Mo).
 */
export async function parseExport(stream, onElement) {
  let buffer = '';
  const element = /<(Game|GameAlternateName|GameImage)>([\s\S]*?)<\/\1>/g;
  const consume = (final) => {
    element.lastIndex = 0;
    let end = 0;
    let m;
    while ((m = element.exec(buffer))) {
      onElement(m[1], xmlFields(m[2]));
      end = element.lastIndex;
    }
    buffer = final ? '' : buffer.slice(end);
  };
  stream.setEncoding('utf8');
  for await (const chunk of stream) {
    buffer += chunk;
    // Seulement des éléments complets : le reste attend le morceau suivant.
    if (buffer.length > 4 * 1024 * 1024) consume(false);
  }
  consume(true);
}

/** Flux décompressé de Metadata.xml dans l'archive téléchargée. */
function metadataStream(zipFile) {
  const entry = readZipEntries(zipFile).find((e) => e.name === 'Metadata.xml');
  if (!entry || entry.method !== 8) throw new Error('LaunchBox: Metadata.xml introuvable dans l’export');
  const header = Buffer.alloc(30);
  const fd = fs.openSync(zipFile, 'r');
  try {
    fs.readSync(fd, header, 0, 30, entry.offset);
  } finally {
    fs.closeSync(fd);
  }
  const start = entry.offset + 30 + header.readUInt16LE(26) + header.readUInt16LE(28);
  return fs.createReadStream(zipFile, { start, end: start + entry.compressedSize - 1 }).pipe(zlib.createInflateRaw());
}

/** Importe l'export dans une nouvelle base (remplace l'ancienne une fois terminée). */
export async function importExport(zipFile) {
  fs.mkdirSync(dir, { recursive: true });
  const tmp = `${dbFile}.tmp`;
  fs.rmSync(tmp, { force: true });
  const db = new DatabaseSync(tmp);
  db.exec(`
    PRAGMA journal_mode = OFF;
    PRAGMA synchronous = OFF;
    CREATE TABLE games (id INTEGER PRIMARY KEY, platform TEXT NOT NULL, data TEXT NOT NULL);
    CREATE TABLE names (norm TEXT NOT NULL, id INTEGER NOT NULL, name TEXT NOT NULL, region TEXT);
    CREATE TABLE images (id INTEGER NOT NULL, type TEXT NOT NULL, region TEXT, file TEXT NOT NULL);
  `);
  const insertGame = db.prepare('INSERT OR IGNORE INTO games (id, platform, data) VALUES (?, ?, ?)');
  const insertName = db.prepare('INSERT INTO names (norm, id, name, region) VALUES (?, ?, ?, ?)');
  const insertImage = db.prepare('INSERT INTO images (id, type, region, file) VALUES (?, ?, ?, ?)');
  db.exec('BEGIN');
  try {
    await parseExport(metadataStream(zipFile), (type, f) => {
      const id = Number(f.DatabaseID);
      if (!id) return;
      if (type === 'Game') {
        if (!KNOWN_PLATFORMS.has(f.Platform) || !f.Name) return;
        const { Platform, DatabaseID, ...data } = f;
        insertGame.run(id, Platform, JSON.stringify(data));
        insertName.run(normalize(f.Name), id, f.Name, null);
      } else if (type === 'GameAlternateName' && f.AlternateName) {
        insertName.run(normalize(f.AlternateName), id, f.AlternateName, f.Region || null);
      } else if (type === 'GameImage' && IMAGE_TYPES.has(f.Type) && f.FileName) {
        insertImage.run(id, f.Type, f.Region || null, f.FileName);
      }
    });
    // Titres alternatifs et images des plateformes non importées : inutiles.
    db.exec('DELETE FROM names WHERE id NOT IN (SELECT id FROM games)');
    db.exec('DELETE FROM images WHERE id NOT IN (SELECT id FROM games)');
    db.exec('COMMIT');
    db.exec('CREATE INDEX names_norm ON names(norm); CREATE INDEX images_id ON images(id);');
  } catch (err) {
    db.exec('ROLLBACK');
    db.close();
    fs.rmSync(tmp, { force: true });
    throw err;
  }
  db.close();
  closeDb();
  fs.renameSync(tmp, dbFile);
}

let database = null;
let refreshing = null;

function closeDb() {
  database?.close();
  database = null;
}

const isFresh = (file) => fs.existsSync(file) && Date.now() - fs.statSync(file).mtimeMs < REFRESH_MS;

/** Télécharge et importe l'export s'il manque ou date de plus d'une semaine (un seul à la fois). */
async function refresh() {
  if (isFresh(dbFile)) return;
  refreshing ||= (async () => {
    fs.mkdirSync(dir, { recursive: true });
    const zipFile = path.join(dir, 'Metadata.zip');
    console.log('[launchbox] Téléchargement de la base LaunchBox Games DB…');
    const res = await fetch(EXPORT_URL);
    if (!res.ok) throw new Error(`LaunchBox: HTTP ${res.status}`);
    fs.writeFileSync(`${zipFile}.part`, Buffer.from(await res.arrayBuffer()));
    fs.renameSync(`${zipFile}.part`, zipFile);
    console.log('[launchbox] Import…');
    await importExport(zipFile);
    fs.rmSync(zipFile, { force: true });
    console.log('[launchbox] Base prête.');
  })().finally(() => {
    refreshing = null;
  });
  try {
    await refreshing;
  } catch (err) {
    // Ancienne base encore utilisable : on la garde jusqu'au prochain essai.
    if (!fs.existsSync(dbFile)) throw err;
    console.warn(`[launchbox] Mise à jour impossible : ${err.message}`);
    fs.utimesSync(dbFile, new Date(), new Date()); // pas de nouvel essai avant une semaine
  }
}

async function openDb() {
  await refresh();
  database ||= new DatabaseSync(dbFile, { readOnly: true });
  return database;
}

/** Image préférée d'un type : région préférée (SCRAPE_REGIONS), sinon sans région, sinon la première. */
export function pickImage(images, type, regions) {
  const list = images.filter((i) => i.type === type);
  if (!list.length) return null;
  const order = regions.flatMap((r) => IMAGE_REGIONS[r] || []);
  const rank = (i) => {
    const k = order.indexOf(i.region);
    return k >= 0 ? k : i.region ? order.length + 1 : order.length;
  };
  return `${IMAGES}/${encodeURIComponent([...list].sort((a, b) => rank(a) - rank(b))[0].file)}`;
}

/** Nombre de joueurs LaunchBox (« 4 ») au format des autres sources (« 1-4 »). */
const playersOf = (max) => {
  const n = Number(max);
  return n > 1 ? `1-${n}` : n === 1 ? '1' : null;
};

/** Fiche RomCloud d'un jeu LaunchBox (données de la base locale). */
export function launchboxMeta(id, data, names, images, regions) {
  const genres = (data.Genres || '').split(';').map((g) => g.trim()).filter(Boolean);
  const rating = Number(data.CommunityRating);
  const links = [{ label: 'LaunchBox', url: `https://gamesdb.launchbox-app.com/games/details/${id}` }];
  if (data.WikipediaURL) links.push({ label: 'Wikipedia', url: data.WikipediaURL });
  if (data.VideoURL) links.push({ label: /youtu\.?be/.test(data.VideoURL) ? 'YouTube' : 'Video', url: data.VideoURL });
  return {
    title: data.Name || null,
    description: data.Overview || null,
    releaseDate: data.ReleaseDate?.slice(0, 10) || data.ReleaseYear || null,
    developer: data.Developer || null,
    publisher: data.Publisher || null,
    genre: genres.slice(0, 3).join(', ') || null,
    players: playersOf(data.MaxPlayers),
    // Note de la communauté sur 5, seulement si assez de votes pour avoir un sens.
    rating: Number.isFinite(rating) && rating > 0 && Number(data.CommunityRatingCount) >= 3 ? Math.round(rating * 10) / 10 : null,
    ratingVotes: Number(data.CommunityRatingCount) || null,
    media: {
      boxart: pickImage(images, 'Box - Front', regions) || pickImage(images, 'Screenshot - Game Title', regions),
      screenshot: pickImage(images, 'Screenshot - Gameplay', regions) || pickImage(images, 'Screenshot - Game Title', regions),
    },
    details: compactDetails({
      otherTitles: names.filter((n) => n.name !== data.Name).map((n) => ({ region: null, text: n.name })),
      cooperative: data.Cooperative === 'true' ? true : null,
      ageRatings: data.ESRB && data.ESRB !== 'Not Rated' ? [{ type: 'ESRB', text: data.ESRB }] : [],
      links,
    }),
  };
}

/**
 * Jeu LaunchBox d'un système d'après ses titres possibles (titre trouvé par une autre source,
 * titre tiré du nom de fichier…) ; null si le système n'a pas de plateforme LaunchBox ou si le
 * jeu est introuvable. Le premier titre qui correspond l'emporte.
 */
export async function scrapeLaunchBox({ system, titles, regions = config.screenscraper.regions }) {
  const platforms = LAUNCHBOX_PLATFORMS[system.id] || LAUNCHBOX_PLATFORMS[system.shortname];
  if (!platforms) return null;
  const db = await openDb();
  const find = db.prepare(
    `SELECT g.id, g.platform, g.data FROM names n JOIN games g ON g.id = n.id
     WHERE n.norm = ? AND g.platform IN (${platforms.map(() => '?').join(', ')})`,
  );
  const namesOf = (id) => db.prepare('SELECT name, region FROM names WHERE id = ?').all(id);
  const meta = ({ id, data }) =>
    launchboxMeta(id, JSON.parse(data), namesOf(id), db.prepare('SELECT type, region, file FROM images WHERE id = ?').all(id), regions);
  // Plusieurs plateformes possibles (Neo Geo AES / MVS) : l'ordre de la liste départage.
  const byPlatform = (rows) => rows.sort((a, b) => platforms.indexOf(a.platform) - platforms.indexOf(b.platform));
  for (const title of titles) {
    const norm = title && normalize(title);
    if (!norm) continue;
    const rows = find.all(norm, ...platforms);
    if (rows.length) return meta(byPlatform(rows)[0]);
  }
  // « Titre - Sous-titre » introuvable (jeu d'une compilation nommé d'après l'un de ses jeux :
  // « Parodius - Fantastic Journey » pour « Parodius ») : le titre seul, si sa fiche cite le
  // sous-titre (résumé ou autre nom) ; sinon rien (« Tomb Raider - Chronicles » n'est pas « Tomb Raider »).
  for (const title of titles) {
    const split = splitSubtitle(title);
    if (!split) continue;
    const match = byPlatform(find.all(normalize(split.main), ...platforms))
      .find(({ id, data }) => citesSubtitle(JSON.parse(data).Overview, namesOf(id).map((n) => n.name), split.subtitle));
    if (match) return meta(match);
  }
  return null;
}

/** « Parodius - Fantastic Journey » -> { main: « Parodius », subtitle: « Fantastic Journey » } ; null sans sous-titre assez long. */
export function splitSubtitle(title) {
  const parts = String(title || '').split(/\s+-\s+/);
  if (parts.length < 2) return null;
  const subtitle = parts.slice(1).join(' ');
  return normalize(subtitle).length >= 6 ? { main: parts[0], subtitle } : null;
}

/** La fiche (résumé ou l'un de ses noms) cite-t-elle le sous-titre ? */
export function citesSubtitle(overview, names, subtitle) {
  const wanted = normalize(subtitle);
  return normalize(String(overview || '')).includes(wanted) || names.some((n) => normalize(n).includes(wanted));
}
