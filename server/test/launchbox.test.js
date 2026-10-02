import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';
import { Readable } from 'node:stream';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-launchbox-test-${process.pid}`);

const { decodeXml, launchboxMeta, parseExport, pickImage } = await import('../src/scraper/launchbox.js');
const { siteLinks } = await import('../src/scraper/wikipedia.js');

test('export LaunchBox lu par morceaux, éléments coupés entre deux morceaux', async () => {
  const xml = `<?xml version="1.0"?><LaunchBox>
  <Game><Name>Burnin' Rubber</Name><DatabaseID>126355</DatabaseID><Platform>Amstrad GX4000</Platform><Genres /></Game>
  <GameAlternateName><AlternateName>Burnin&apos; Rubber &amp; Co</AlternateName><DatabaseID>126355</DatabaseID></GameAlternateName>
  <GameImage><DatabaseID>126355</DatabaseID><FileName>a.jpg</FileName><Type>Box - Front</Type></GameImage>
</LaunchBox>`;
  // Morceaux de 7 caractères : chaque élément est coupé plusieurs fois.
  const chunks = xml.match(/[\s\S]{1,7}/g);
  const seen = [];
  await parseExport(Readable.from(chunks.map((c) => Buffer.from(c))), (type, fields) => seen.push([type, fields]));
  assert.deepEqual(seen, [
    ['Game', { Name: "Burnin' Rubber", DatabaseID: '126355', Platform: 'Amstrad GX4000' }],
    ['GameAlternateName', { AlternateName: "Burnin' Rubber & Co", DatabaseID: '126355' }],
    ['GameImage', { DatabaseID: '126355', FileName: 'a.jpg', Type: 'Box - Front' }],
  ]);
  assert.equal(decodeXml('&#233;t&#xE9; &lt;3&gt; &unknown;'), 'été <3> &unknown;');
});

test('image de la région préférée, sinon sans région, sinon la première', () => {
  const images = [
    { type: 'Box - Front', region: 'Japan', file: 'jp.jpg' },
    { type: 'Box - Front', region: null, file: 'any.jpg' },
    { type: 'Box - Front', region: 'Europe', file: 'eu.jpg' },
    { type: 'Screenshot - Gameplay', region: 'Japan', file: 'snap.png' },
  ];
  assert.equal(pickImage(images, 'Box - Front', ['fr', 'eu', 'us']), 'https://images.launchbox-app.com/eu.jpg');
  assert.equal(pickImage(images, 'Box - Front', ['fr', 'us']), 'https://images.launchbox-app.com/any.jpg');
  assert.equal(pickImage(images, 'Screenshot - Gameplay', ['fr']), 'https://images.launchbox-app.com/snap.png');
  assert.equal(pickImage(images, 'Screenshot - Game Title', ['fr']), null);
});

test('fiche LaunchBox : textes, joueurs, note, titres alternatifs, coop, ESRB, liens', () => {
  const data = {
    Name: 'Streets of Rage 2',
    Overview: 'Axel and Blaze…',
    ReleaseDate: '1992-12-20T00:00:00-05:00',
    Developer: 'Ancient',
    Publisher: 'Sega',
    Genres: "Beat 'em Up; Action",
    MaxPlayers: '2',
    Cooperative: 'true',
    ESRB: 'T - Teen',
    CommunityRating: '4.6153',
    CommunityRatingCount: '120',
    VideoURL: 'https://www.youtube.com/watch?v=x',
  };
  const names = [{ name: 'Streets of Rage 2' }, { name: 'Bare Knuckle II' }];
  const meta = launchboxMeta(694, data, names, [{ type: 'Screenshot - Game Title', region: null, file: 't.png' }], ['fr']);
  assert.equal(meta.releaseDate, '1992-12-20');
  assert.equal(meta.genre, "Beat 'em Up, Action");
  assert.equal(meta.players, '1-2');
  assert.equal(meta.rating, 4.6);
  // Pas de jaquette ni de capture : l'écran-titre sert aux deux.
  assert.equal(meta.media.boxart, 'https://images.launchbox-app.com/t.png');
  assert.equal(meta.media.screenshot, 'https://images.launchbox-app.com/t.png');
  assert.deepEqual(meta.details.otherTitles, [{ region: null, text: 'Bare Knuckle II' }]);
  assert.equal(meta.details.cooperative, true);
  assert.deepEqual(meta.details.ageRatings, [{ type: 'ESRB', text: 'T - Teen' }]);
  assert.deepEqual(meta.details.links.map((l) => l.label), ['LaunchBox', 'YouTube']);
  // Trop peu de votes : pas de note.
  assert.equal(launchboxMeta(1, { ...data, CommunityRatingCount: '1' }, [], [], []).rating, null);
});

test('liens vers les sites de jeux rétro d’après les identifiants Wikidata', () => {
  const claim = (value) => [{ mainsnak: { datavalue: { value } } }];
  const links = siteLinks({ P11688: claim('6633'), P4769: claim('632801'), P9075: claim('Streets_of_Rage_2'), P646: claim('/m/x') });
  assert.deepEqual(links, [
    { label: 'MobyGames', url: 'https://www.mobygames.com/game/6633/' },
    { label: 'GameFAQs', url: 'https://gamefaqs.gamespot.com/-/632801-' },
    { label: 'StrategyWiki', url: 'https://strategywiki.org/wiki/Streets_of_Rage_2' },
  ]);
});
