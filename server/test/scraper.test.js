import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-test-${process.pid}`);
process.env.SCREENSCRAPER_DEV_ID = 'dev';
process.env.SCREENSCRAPER_DEV_PASSWORD = 'pw';

const { scrapeScreenScraper, QuotaError } = await import('../src/scraper/screenscraper.js');
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
  assert.equal(meta.rating, 4.5);
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

test('Noms de fichiers', () => {
  assert.equal(libretroName('Q*bert: The Game'), 'Q_bert_ The Game');
  assert.equal(titleFromFileName('Super Mario World (USA) [!].sfc'), 'Super Mario World');
  assert.equal(titleFromFileName('Zelda_no_Densetsu.nes'), 'Zelda no Densetsu');
  assert.equal(titleFromFileName('Bomberman Online v1.004 (2001)(Sega)(NTSC)(US)[!].zip'), 'Bomberman Online');
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
