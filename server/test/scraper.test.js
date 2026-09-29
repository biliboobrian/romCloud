import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-test-${process.pid}`);
process.env.SCREENSCRAPER_DEV_ID = 'dev';
process.env.SCREENSCRAPER_DEV_PASSWORD = 'pw';

const { scrapeScreenScraper, QuotaError } = await import('../src/scraper/screenscraper.js');
const { libretroName, normalize, titleFromLibretroName } = await import('../src/scraper/libretro.js');
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
