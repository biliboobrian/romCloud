import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';
import zlibModule from 'node:zlib';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-test-${process.pid}`);
process.env.SCREENSCRAPER_DEV_ID = 'dev';
process.env.SCREENSCRAPER_DEV_PASSWORD = 'pw';

const { scrapeScreenScraper, screenscraperMaxThreads, QuotaError } = await import('../src/scraper/screenscraper.js');
const { scrapeConcurrency } = await import('../src/jobs.js');
const { libretroName, normalize, parseDat, scrapeLibretro, titleFromLibretroName } = await import('../src/scraper/libretro.js');
const { titleFromFileName } = await import('../src/library.js');

const sample = {
  response: {
    jeu: {
      id: '3',
      noms: [
        { region: 'us', text: 'Super Mario World' },
        { region: 'jp', text: 'Super Mario World: Super Mario Bros. 4' },
      ],
      synopsis: [
        { langue: 'en', text: 'English synopsis' },
        { langue: 'fr', text: 'Synopsis français' },
      ],
      dates: [{ region: 'eu', text: '1992-04-11' }],
      editeur: { text: 'Nintendo' },
      developpeur: { text: 'Nintendo EAD' },
      joueurs: { text: '1-2' },
      note: { text: '18' },
      genres: [{ noms: [{ langue: 'en', text: 'Platform' }, { langue: 'fr', text: 'Plateforme' }] }],
      medias: [
        { type: 'box-2D', region: 'us', url: 'https://x/box-us.png' },
        { type: 'box-2D', region: 'eu', url: 'https://x/box-eu.png' },
        { type: 'ss', region: 'wor', url: 'https://x/ss.png' },
      ],
    },
  },
};

function mockFetch(status, body) {
  const calls = [];
  globalThis.fetch = async (url) => {
    calls.push(String(url));
    return new Response(typeof body === 'string' ? body : JSON.stringify(body), { status });
  };
  return calls;
}

test('ScreenScraper : langue et région préférées', async () => {
  const calls = mockFetch(200, sample);
  const meta = await scrapeScreenScraper({
    system: { screenscraperId: 4 },
    fileName: 'Super Mario World (USA).sfc',
    size: 524288,
    crc32: 'b19ed489',
  });
  assert.equal(meta.title, 'Super Mario World');
  assert.equal(meta.description, 'Synopsis français');
  assert.equal(meta.genre, 'Plateforme');
  assert.equal(meta.ratingScore, 18);
  assert.equal(meta.media.boxart, 'https://x/box-eu.png');
  assert.equal(meta.media.screenshot, 'https://x/ss.png');
  const url = new URL(calls[0]);
  assert.equal(url.searchParams.get('systemeid'), '4');
  assert.equal(url.searchParams.get('crc'), 'B19ED489');
});

test('ScreenScraper : jeu introuvable', async () => {
  mockFetch(404, 'Erreur : Rom/Iso/Dossier non trouvée !');
  assert.equal(await scrapeScreenScraper({ system: {}, fileName: 'x.sfc', size: 1 }), null);
});

test('ScreenScraper : quota dépassé', async () => {
  mockFetch(430, 'Quota dépassé');
  await assert.rejects(scrapeScreenScraper({ system: {}, fileName: 'x.sfc', size: 1 }), QuotaError);
});

test('ScreenScraper : trop de requêtes simultanées réessayé, maxthreads du compte retenu', async () => {
  const replies = [[429, 'Trop de threads'], [200, { response: { ssuser: { maxthreads: '4' }, jeu: sample.response.jeu } }]];
  let calls = 0;
  globalThis.fetch = async () => {
    const [status, body] = replies[calls++];
    return new Response(typeof body === 'string' ? body : JSON.stringify(body), { status });
  };
  const meta = await scrapeScreenScraper({ system: {}, fileName: 'Super Mario World (USA).sfc', size: 1 });
  assert.equal(calls, 2);
  assert.equal(meta.title, 'Super Mario World');
  assert.equal(screenscraperMaxThreads(), 4);
});

test('Scraping en parallèle : réglage borné par le compte ScreenScraper', () => {
  assert.equal(scrapeConcurrency('auto', { configured: 8, ssEnabled: true, ssMax: 4 }), 4);
  assert.equal(scrapeConcurrency('auto', { configured: 8, ssEnabled: true, ssMax: null }), 8); // avant la 1re réponse
  assert.equal(scrapeConcurrency('auto', { configured: 8, ssEnabled: false, ssMax: 1 }), 8); // sans ScreenScraper
  assert.equal(scrapeConcurrency('libretro', { configured: 8, ssEnabled: true, ssMax: 1 }), 8);
  assert.equal(scrapeConcurrency('screenscraper', { configured: 2, ssEnabled: true, ssMax: 4 }), 2);
});

test('Noms de fichiers', () => {
  assert.equal(libretroName('Q*bert: The Game'), 'Q_bert_ The Game');
  assert.equal(titleFromFileName('Super Mario World (USA) [!].sfc'), 'Super Mario World');
  assert.equal(titleFromFileName('Zelda_no_Densetsu.nes'), 'Zelda no Densetsu');
  assert.equal(titleFromFileName('Bomberman Online v1.004 (2001)(Sega)(NTSC)(US)[!].zip'), 'Bomberman Online');
  // Nom de release Switch : version, région et groupe retirés.
  assert.equal(
    titleFromFileName('The Legend of Zelda Tears of the Kingdom v1.1.0 Eur SuperXCi - CLC.xci'),
    'The Legend of Zelda Tears of the Kingdom',
  );
  assert.equal(titleFromFileName('Super Mario Odyssey [0100000000010000][v0].nsp'), 'Super Mario Odyssey');
  assert.equal(
    normalize('Bomberman Online v1.004 (2001)(Sega)(NTSC)(US)[!].zip'),
    normalize('Bomberman Online (USA)'),
  );
  assert.equal(titleFromLibretroName('Bomberman Online (USA)'), 'Bomberman Online');
  assert.equal(
    titleFromLibretroName('Legend of Zelda, The - A Link to the Past (USA)'),
    'The Legend of Zelda - A Link to the Past',
  );
  assert.equal(titleFromLibretroName('Q_bert_ The Game (USA)'), 'Q_bert: The Game');
});

test('Libretro : titre alternatif No-Intro ("Titre JP ~ Titre US")', async () => {
  const listingHtml = '<a href="Streets%20of%20Rage%20(World).png">x</a><a href="Streets%20of%20Rage%202%20(World).png">x</a>';
  globalThis.fetch = async (url, opts) => {
    if (opts?.method === 'HEAD') return new Response(null, { status: 404 });
    return new Response(String(url).endsWith('/') ? listingHtml : '', { status: 200 });
  };
  const result = await scrapeLibretro({
    system: { libretroName: 'Sega - Game Gear' },
    fileName: 'Bare Knuckle ~ Streets of Rage (World).zip',
  });
  assert.equal(result.title, 'Streets of Rage');
  assert.match(result.media.boxart, /Named_Boxarts\/Streets%20of%20Rage%20\(World\)\.png$/);
});

test('Libretro : nom court MAME retrouvé via la DAT du système', async () => {
  const dat = [
    'clrmamepro (',
    '\tname "SNK - Neo Geo"',
    ')',
    '',
    'game (',
    '\tname "2020 Super Baseball (set 1)"',
    '\tdescription "2020 Super Baseball (set 1)"',
    '\trom ( name "2020bb.neo" size 7081984 crc 8041AD52 )',
    ')',
    'game (',
    '\tname "2020 Super Baseball (set 2)"',
    '\trom ( name 2020bba.zip size 1 crc 00000000 )',
    ')',
  ].join('\n');
  const { byRom, byCrc } = parseDat(dat);
  assert.equal(byRom.get('2020bb'), '2020 Super Baseball (set 1)');
  assert.equal(byRom.get('2020bba'), '2020 Super Baseball (set 2)');
  assert.equal(byCrc.get('8041AD52'), '2020 Super Baseball (set 1)');

  const listingHtml = '<a href="2020%20Super%20Baseball%20(set%201).png">x</a>';
  globalThis.fetch = async (url, opts) => {
    url = String(url);
    if (opts?.method === 'HEAD') return new Response(null, { status: url.includes('2020%20Super') ? 200 : 404 });
    if (url.endsWith('.dat')) return new Response(url.includes('/dat/') ? dat : 'Not Found', { status: url.includes('/dat/') ? 200 : 404 });
    return new Response(url.endsWith('/') ? listingHtml : '', { status: 200 });
  };
  const result = await scrapeLibretro({ system: { libretroName: 'SNK - Neo Geo' }, fileName: '2020bb.zip' });
  assert.equal(result.title, '2020 Super Baseball');
  assert.match(result.media.boxart, /Named_Boxarts\/2020%20Super%20Baseball%20\(set%201\)\.png$/);
});

test('Libretro : numéro de release en tête du nom', async () => {
  const listingHtml = '<a href="Kirby%20-%20Power%20Paintbrush%20(Europe)%20(En,Fr,De,Es,It).png">x</a>';
  globalThis.fetch = async (url, opts) => {
    if (opts?.method === 'HEAD') return new Response(null, { status: 404 });
    return new Response(String(url).endsWith('/') ? listingHtml : '', { status: String(url).endsWith('.dat') ? 404 : 200 });
  };
  const result = await scrapeLibretro({
    system: { libretroName: 'Nintendo - Nintendo DS' },
    fileName: '0202 - Kirby - Power Paintbrush (E)(Legacy).nds',
  });
  assert.equal(result.title, 'Kirby - Power Paintbrush');
});

test('Libretro : ROM retrouvée par son CRC quand le nom a changé', async () => {
  const dat = 'game (\n\tname "Rayman 2 (USA) (En,Fr,De,Es,It)"\n\trom ( name "Rayman 2 (USA) (En,Fr,De,Es,It).gbc" size 4194304 crc 6F5C315D )\n)\n';
  const listingHtml = '<a href="Rayman%202%20(USA)%20(En,Fr,De,Es,It).png">x</a>';
  globalThis.fetch = async (url, opts) => {
    url = String(url);
    if (opts?.method === 'HEAD') return new Response(null, { status: url.includes('Rayman%202%20(USA)') ? 200 : 404 });
    if (url.endsWith('.dat')) return new Response(dat, { status: 200 });
    return new Response(url.endsWith('/') ? listingHtml : '', { status: 200 });
  };
  const result = await scrapeLibretro({
    system: { libretroName: 'Nintendo - Game Boy Color (CRC)' },
    fileName: 'Rayman 2 - The Great Escape (Europe) (En,Fr,De,Es,It).gbc',
    crc32: '6f5c315d',
  });
  assert.equal(result.title, 'Rayman 2');
});

test('ScreenScraper : informations détaillées', async () => {
  const jeu = {
    ...sample.response.jeu,
    familles: [{ noms: [{ langue: 'fr', text: 'Super Mario' }] }],
    modes: [{ noms: [{ langue: 'fr', text: '1 joueur' }] }, { noms: [{ langue: 'fr', text: 'Coopératif' }] }],
    classifications: [{ type: 'PEGI', text: '3' }, { type: 'ESRB', text: 'E' }],
    resolution: '256x224',
    rotation: '0',
    rom: { romserial: 'SNSP-MW', beta: '0', proto: '1', regions: { regions_fr: ['Europe'] }, langues: { langues_fr: ['Anglais', 'Français'] } },
  };
  mockFetch(200, { response: { jeu } });
  const { details } = await scrapeScreenScraper({ system: {}, fileName: 'x.sfc', size: 1 });
  assert.deepEqual(details.otherTitles, [{ region: 'jp', text: 'Super Mario World: Super Mario Bros. 4' }]);
  assert.deepEqual(details.releaseDates, [{ region: 'eu', text: '1992-04-11' }]);
  assert.equal(details.series, 'Super Mario');
  assert.deepEqual(details.modes, ['1 joueur', 'Coopératif']);
  assert.deepEqual(details.ageRatings, [{ type: 'PEGI', text: '3' }, { type: 'ESRB', text: 'E' }]);
  assert.deepEqual(details.regions, ['Europe']);
  assert.deepEqual(details.languages, ['Anglais', 'Français']);
  assert.equal(details.serial, 'SNSP-MW');
  assert.deepEqual(details.romFlags, ['Proto']);
  assert.equal(details.resolution, '256x224');
  assert.equal(details.rotation, undefined); // 0° : rien à signaler
  assert.equal(details.links[0].url, 'https://www.screenscraper.fr/gameinfos.php?gameid=3');
});

const { fileNameDetails, mergeDetails } = await import('../src/scraper/details.js');
const { parseMetaDat } = await import('../src/scraper/libretro.js');

test('informations tirées du nom de fichier', () => {
  assert.deepEqual(fileNameDetails('Gran Turismo (Europe) (En,Fr,De,Es,It).chd'), {
    regions: ['Europe'],
    languages: ['En', 'Fr', 'De', 'Es', 'It'],
  });
  assert.deepEqual(fileNameDetails('Final Fantasy VII (USA) (Disc 2) (Rev 1).chd'), { regions: ['USA'], romFlags: ['Disc 2', 'Rev 1'] });
  assert.deepEqual(fileNameDetails('Mother 3 (Japan) [T+Eng1.3].gba'), { regions: ['Japan'], romFlags: ['Translation Eng'] });
  assert.deepEqual(fileNameDetails('Tetris.gb'), {});
});

test('fusion des informations : la première source prime, liens cumulés', () => {
  const merged = mergeDetails(
    { serial: 'A', links: [{ label: 'ScreenScraper', url: 'u1' }], modes: [] },
    { serial: 'B', modes: ['Solo'], links: [{ label: 'Wikipedia', url: 'u2' }, { label: 'ScreenScraper', url: 'u1' }] },
  );
  assert.deepEqual(merged, { serial: 'A', links: [{ label: 'ScreenScraper', url: 'u1' }, { label: 'Wikipedia', url: 'u2' }], modes: ['Solo'] });
});

test('fiches metadat libretro : par nom, CRC et numéro de série', () => {
  const index = parseMetaDat([
    'clrmamepro (',
    '\tname "Sony - PlayStation"',
    ')',
    '',
    'game (',
    '\tcomment "Ape Escape (France)"',
    '\trumble 1',
    '\trom ( serial "SCES-02028" )',
    ')',
    'game (',
    '\tname "Gran Turismo (Europe) (En,Fr,De,Es,It)"',
    '\tregion "Europe"',
    '\tserial "SCES-00984"',
    '\trom ( name "Gran Turismo.bin" size 1 crc 392ab7b5 md5 x )',
    ')',
  ].join('\n'));
  assert.equal(index.bySerial.get('SCES02028').fields.rumble, '1');
  assert.equal(index.byName.get('ape escape (france)').fields.rumble, '1');
  const gt = index.byCrc.get('392AB7B5');
  assert.equal(gt.name, 'Gran Turismo (Europe) (En,Fr,De,Es,It)');
  assert.equal(gt.fields.region, 'Europe');
  assert.equal(index.bySerial.get('SCES00984'), gt);
});

const { zipEntries, zipMainEntry } = await import('../src/scraper/rom-identity.js');

/** Archive .zip minimale (fichiers stockés sans compression). */
function makeZip(files) {
  const zlib = zlibModule;
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, content] of files) {
    const data = Buffer.from(content);
    const nameBuf = Buffer.from(name);
    const crc = zlib.crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(offset, 42);
    locals.push(local, nameBuf, data);
    centrals.push(central, nameBuf);
    offset += 30 + nameBuf.length + data.length;
  }
  const dir = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(files.length, 8);
  eocd.writeUInt16LE(files.length, 10);
  eocd.writeUInt32LE(dir.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, dir, eocd]);
}

test('ROM d\'une archive .zip : nom, taille et CRC lus sans décompression', async () => {
  const fs = await import('node:fs');
  const dir = path.join(os.tmpdir(), `romcloud-zip-${process.pid}`);
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, "Baba's Palace (Dragon).zip");
  fs.writeFileSync(file, makeZip([['lisez-moi.txt', 'notes notes notes notes notes'], ['roms/Baba.cpr', 'CPR-DATA'], ['vide/', '']]));
  assert.equal(zipEntries(file).length, 2);
  assert.deepEqual(zipMainEntry(file), { name: 'Baba.cpr', size: 8, crc32: (await import('node:zlib')).crc32(Buffer.from('CPR-DATA')).toString(16).padStart(8, '0') });
  assert.equal(zipMainEntry(path.join(dir, 'absent.zip')), null);
  assert.equal(zipMainEntry(path.join(dir, 'jeu.sfc')), null);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('ScreenScraper : système créé à la main, identifiant pris d\'après son nom court', async () => {
  const calls = mockFetch(404, 'non trouvé');
  await scrapeScreenScraper({ system: { id: 'gx4000', shortname: 'gx4000', screenscraperId: null }, fileName: 'Baba.cpr', size: 8 });
  assert.equal(new URL(calls[0]).searchParams.get('systemeid'), '87');
});

const { identifyArcade, isArcadeSystem, parseArcadeXml } = await import('../src/scraper/arcade.js');

test('jeu d\'arcade renommé : retrouvé par les CRC des fichiers de l\'archive', () => {
  const index = parseArcadeXml([
    '<game name="fatfury1" romof="neogeo">',
    '\t<description>Fatal Fury - King of Fighters / Garou Densetsu &amp; co</description>',
    '\t<year>1992</year>',
    '\t<manufacturer>SNK</manufacturer>',
    '\t<rom name="033-p1.p1" size="524288" crc="47ebdc2f"/>',
    '\t<rom name="033-s1.s1" size="131072" crc="3c3bdf8c"/>',
    '\t<rom name="sp-s2.sp1" merge="sp-s2.sp1" size="131072" crc="9036d879"/>',
    '</game>',
    '<game name="fatfury1b" cloneof="fatfury1" romof="fatfury1">',
    '\t<description>Fatal Fury (bootleg)</description>',
    '\t<rom name="033-p1.p1" size="524288" crc="47ebdc2f"/>',
    '\t<rom name="033-s1.s1" size="131072" crc="3c3bdf8c"/>',
    '\t<rom name="boot.bin" size="16" crc="12345678"/>',
    '</game>',
  ].join('\n'));
  const game = identifyArcade([{ crc32: '47EBDC2F' }, { crc32: '3c3bdf8c' }], index);
  assert.equal(game.name, 'fatfury1'); // le jeu exact plutôt que le clone qui ajoute une ROM
  assert.equal(game.description, 'Fatal Fury - King of Fighters / Garou Densetsu & co');
  assert.equal(game.year, '1992');
  // Seul le BIOS partagé (« merge ») correspond : rien.
  assert.equal(identifyArcade([{ crc32: '9036d879' }, { crc32: 'aaaaaaaa' }], index), null);
  assert.equal(isArcadeSystem({ libretroName: 'SNK - Neo Geo' }), true);
  assert.equal(isArcadeSystem({ libretroName: 'Nintendo - Game Boy', shortname: 'gb' }), false);
});

const { serialFromFileName, serialKey } = await import('../src/scraper/serial.js');

test('numéro de série PlayStation lu dans le nom de fichier', () => {
  // Identifiant de contenu PS Vita (nom d'un dump NoNpDrm / VPK).
  assert.equal(serialFromFileName('EP0001-PCSB00040_00-ASPHALTINJECTION.vpk'), 'PCSB-00040');
  assert.equal(serialFromFileName('EP0001-PCSB00040 00-ASPHALTINJECTION.zip'), 'PCSB-00040');
  assert.equal(serialFromFileName('UP9000-PCSA00011_00-UNCHARTED0000001'), 'PCSA-00011');
  // Numéro seul, avec ou sans tiret, entre crochets ; format PS2.
  assert.equal(serialFromFileName('PCSE00120.vpk'), 'PCSE-00120');
  assert.equal(serialFromFileName('Lumines [ULES-00151].iso'), 'ULES-00151');
  assert.equal(serialFromFileName('SLUS_200.62.iso'), 'SLUS-20062');
  // Pas de numéro de série.
  assert.equal(serialFromFileName('Super Mario World (USA).sfc'), null);
  assert.equal(serialFromFileName('1943 - The Battle of Midway.zip'), null);
  assert.equal(serialKey('PCSB-00040'), serialKey('pcsb00040'));
});

test('titre provisoire d’un identifiant de contenu PS Vita : son libellé', () => {
  assert.equal(titleFromFileName('EP0001-PCSB00040_00-ASPHALTINJECTION.vpk'), 'ASPHALTINJECTION');
});

test('titre d’un homebrew : auteur, version et article rejeté', () => {
  assert.equal(titleFromFileName('Adventures of Gus and Rob V2, The by Mickey McMurray.ngc'), 'The Adventures of Gus and Rob');
  assert.equal(titleFromFileName('Legend of Zelda, The.nes'), 'The Legend of Zelda');
  // « by » suivi d'un seul mot : partie du titre.
  assert.equal(titleFromFileName('Stand by Me.gb'), 'Stand by Me');
});

test('ScreenScraper : recherche par titre (homebrew), titre identique seulement', async () => {
  const { searchScreenScraper } = await import('../src/scraper/screenscraper.js');
  const jeux = [
    { id: '7', noms: [{ region: 'wor', text: 'Gus and Rob Deluxe' }] },
    { id: '8', noms: [{ region: 'wor', text: 'Adventures of Gus and Rob, The' }], medias: [{ type: 'box-2D', region: 'wor', url: 'https://x/gus.png' }] },
  ];
  const calls = mockFetch(200, { response: { jeux } });
  const meta = await searchScreenScraper({ system: { id: 'ngpc', shortname: 'ngpc' }, title: 'The Adventures of Gus and Rob' });
  assert.equal(meta.title, 'Adventures of Gus and Rob, The');
  assert.equal(meta.media.boxart, 'https://x/gus.png');
  const url = new URL(calls[0]);
  assert.match(url.pathname, /jeuRecherche/);
  assert.equal(url.searchParams.get('recherche'), 'The Adventures of Gus and Rob');
  // Aucun nom identique : rien plutôt qu'un autre jeu.
  mockFetch(200, { response: { jeux: [jeux[0]] } });
  assert.equal(await searchScreenScraper({ system: {}, title: 'The Adventures of Gus and Rob' }), null);
});
