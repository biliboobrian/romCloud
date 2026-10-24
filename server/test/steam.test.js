import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-steam-test-${process.pid}`);
delete process.env.SCREENSCRAPER_DEV_ID;
delete process.env.SCREENSCRAPER_DEV_PASSWORD;

const { appIdFromText, normalizeTitle, scrapeSteam } = await import('../src/scraper/steam.js');
const { titleFromFileName, scanSystem, getGameRow } = await import('../src/library.js');
const { createSystem } = await import('../src/systems.js');
const { scrapeGame } = await import('../src/scraper/index.js');
const { config } = await import('../src/config.js');

/** Fiche de la boutique (IStoreBrowseService/GetItems). */
const item = (appid, name, reviews) => ({
  success: 1,
  appid,
  name,
  reviews: reviews ? { summary_filtered: { review_count: reviews, percent_positive: 94 } } : undefined,
  basic_info: { short_description: `Résumé de ${name} ${appid}`, developers: [{ name: `Studio ${appid}` }], publishers: [{ name: 'Éditeur' }] },
  release: { steam_release_date: 1784041178 },
  assets: { asset_url_format: `steam/apps/${appid}/\${FILENAME}?t=1`, library_capsule: 'abc/library_capsule.jpg', header: 'def/header.jpg' },
  screenshots: { all_ages_screenshots: [{ filename: `steam/apps/${appid}/s2.jpg`, ordinal: 2 }, { filename: `steam/apps/${appid}/s1.jpg`, ordinal: 1 }] },
});

const ITEMS = { 4297840: item(4297840, 'Rock Bottom', 1990), 4315730: item(4315730, 'Rock Bottom', 3) };

/** Boutique Steam simulée ; renvoie les adresses demandées. */
function mockSteam() {
  const calls = [];
  globalThis.fetch = async (url) => {
    const u = String(url);
    calls.push(u);
    if (u.includes('/api/storesearch/')) {
      return Response.json({ items: [{ type: 'app', id: 4315730, name: 'Rock Bottom' }, { type: 'app', id: 4297840, name: 'Rock Bottom™' }, { type: 'app', id: 1, name: 'A Lighthouse on Rock Bottom' }] });
    }
    if (u.includes('IStoreBrowseService/GetItems')) {
      const input = JSON.parse(decodeURIComponent(new URL(u).searchParams.get('input_json')));
      return Response.json({ response: { store_items: input.ids.map(({ appid }) => ITEMS[appid] || { appid, success: 2 }) } });
    }
    if (u.includes('/api/appdetails')) {
      const id = new URL(u).searchParams.get('appids');
      return Response.json({ [id]: { success: true, data: { genres: [{ description: 'Action' }, { description: 'Aventure' }] } } });
    }
    if (u.includes('steamstatic.com')) return new Response(Buffer.from([0x89, 0x50, 0x4e, 0x47]), { headers: { 'Content-Type': 'image/png' } });
    return new Response('introuvable', { status: 404 });
  };
  return calls;
}

test('AppID lu dans les fichiers du jeu, titres comparés sans marque ni ponctuation', () => {
  assert.equal(appIdFromText('steam_appid.txt', ' 4297840\r\n'), '4297840');
  assert.equal(appIdFromText('steam_settings/steam_appid.txt', 'pas un nombre'), null);
  assert.equal(appIdFromText('Bin/steam_emu.ini', '[Settings]\r\nAppId=4297840\r\n'), '4297840');
  assert.equal(appIdFromText('CODEX.ini', '[Settings]\nAppID = 620\n'), '620');
  assert.equal(normalizeTitle('Rock Bottom™'), normalizeTitle('rock bottom'));
  assert.equal(normalizeTitle('Baldur’s Gate 3'), 'baldur s gate 3');
});

test('nom de dossier d’un jeu PC : points et groupe de release retirés', () => {
  assert.equal(titleFromFileName('Rock.Bottom-TENOKE', { folder: true }), 'Rock Bottom');
  assert.equal(titleFromFileName('Rock.Bottom.v1.0.3-TENOKE', { folder: true }), 'Rock Bottom');
  assert.equal(titleFromFileName('Baldurs.Gate.3-GOG', { folder: true }), 'Baldurs Gate 3');
  assert.equal(titleFromFileName('Rock.Bottom', { folder: true }), 'Rock Bottom');
  // Titres qui ressemblent à un groupe de release : gardés.
  for (const title of ['Half-Life', 'X-COM', 'Dark Rune', 'Matrix Reloaded', 'Mr. Robot', 'SUPERHOT-MCCS']) {
    assert.equal(titleFromFileName(title, { folder: true }), title);
  }
});

test('Steam : nom exact, le jeu le plus commenté parmi ceux du même nom ; par AppID sans recherche', async () => {
  let calls = mockSteam();
  const found = await scrapeSteam({ titles: ['Rock Bottom'], languages: ['fr', 'en'] });
  assert.equal(found.appId, 4297840); // 1 990 avis contre 3
  assert.equal(found.title, 'Rock Bottom');
  assert.equal(found.developer, 'Studio 4297840');
  assert.equal(found.genre, 'Action, Aventure');
  assert.equal(found.releaseDate, '2026-07-14');
  assert.deepEqual(found.rating, { score: 94, count: 1990 });
  assert.equal(found.media.boxart, 'https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/4297840/abc/library_capsule.jpg?t=1');
  assert.equal(found.media.screenshot, 'https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/4297840/s1.jpg');
  assert.ok(calls.some((u) => u.includes('l=french')));

  calls = mockSteam();
  const byId = await scrapeSteam({ appId: '4315730', titles: ['Rock Bottom'], languages: ['fr'] });
  assert.equal(byId.appId, 4315730);
  assert.equal(byId.rating, null); // moins de 10 avis
  assert.ok(!calls.some((u) => u.includes('storesearch')));

  mockSteam();
  assert.equal(await scrapeSteam({ titles: ['Rock'], languages: ['fr'] }), null); // pas de nom exact
});

test('jeu PC scrapé par Steam grâce à son steam_appid.txt (nom de release)', async () => {
  createSystem({ id: 'windows', name: 'PC Windows' });
  const dir = path.join(config.romsDir, 'windows', 'Rock.Bottom-TENOKE');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'RockBottom.exe'), 'MZ');
  fs.writeFileSync(path.join(dir, 'steam_appid.txt'), '4315730');
  const { added } = scanSystem('windows');
  assert.equal(getGameRow(added[0]).title, 'Rock Bottom');

  const calls = mockSteam();
  const game = await scrapeGame(added[0]);
  assert.equal(game.scrapeStatus, 'ok');
  assert.match(game.scrapeSource, /steam/);
  assert.equal(game.title, 'Rock Bottom');
  assert.equal(game.developer, 'Studio 4315730'); // le jeu de l'AppID, pas le plus commenté
  assert.equal(game.releaseDate, '2026-07-14');
  assert.equal(game.hasBoxart, true);
  assert.equal(game.screenshotCount, 2);
  assert.ok(game.details.links.some((l) => l.label === 'Steam' && l.url.includes('/app/4315730/')));
  assert.ok(!calls.some((u) => u.includes('storesearch')));
});
