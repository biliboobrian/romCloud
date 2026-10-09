import { test } from 'node:test';
import assert from 'node:assert/strict';

const { combineRatings, pressRating, reviewScore, sourceStars, stars } = await import('../src/scraper/rating.js');

const review = (value, site, rank = 'normal') => ({
  rank,
  mainsnak: { datavalue: { value } },
  qualifiers: site ? { P447: [{ datavalue: { value: { id: site } } }] } : undefined,
});

test('notes de critique lues dans tous les formats chiffrés', () => {
  assert.equal(reviewScore('93/100'), 0.93);
  assert.equal(reviewScore('36/40'), 0.9);
  assert.equal(reviewScore('8,5/10'), 0.85);
  assert.equal(reviewScore('85%'), 0.85);
  assert.equal(reviewScore(' 4.5 / 5 '), 0.9);
  assert.equal(reviewScore('A-'), null);
  assert.equal(reviewScore('12/10'), null);
  assert.equal(reviewScore(undefined), null);
});

test('presse : moyenne par site puis entre les sites, notes dépréciées ou illisibles ignorées', () => {
  const claims = {
    P444: [
      review('90/100', 'Q150248'), // Metacritic, deux plateformes
      review('80/100', 'Q150248'),
      review('36/40', 'Q1414'), // Famitsu
      review('B+', 'Q1414'),
      review('2/10', 'Q1', 'deprecated'),
    ],
  };
  // (85 + 90) / 2 = 87,5
  assert.deepEqual(pressRating(claims), { score: 87.5, count: 2 });
  assert.equal(pressRating({}), null);
  assert.equal(pressRating({ P444: [review('A', 'Q1')] }), null);
});

test('étoiles selon le barème de chaque source', () => {
  // ScreenScraper sur 20 : 17 et plus -> 5 étoiles, 14 -> 4…
  assert.deepEqual([18, 17, 16.5, 14, 11, 8, 5].map((n) => sourceStars('screenscraper', n)), [5, 5, 4, 4, 3, 2, 1]);
  // Communauté LaunchBox sur 5 : 4,2 et plus -> 5 étoiles (les ~13 % meilleurs jeux).
  assert.deepEqual([4.6, 4.2, 4.1, 3.7, 3.0, 2.4, 1.8].map((n) => sourceStars('launchbox', n)), [5, 5, 4, 4, 3, 2, 1]);
  // Presse sur 100, échelle de Metacritic.
  assert.deepEqual([94, 85, 80, 75, 60, 45, 30].map((n) => sourceStars('press', n)), [5, 5, 4, 4, 3, 2, 1]);
  assert.equal(sourceStars('inconnue', 4), null);
  assert.equal(sourceStars('launchbox', null), null);
});

test('étoiles entières de 0 à 5 (note saisie à la main)', () => {
  assert.equal(stars(3.5), 4);
  assert.equal(stars('2'), 2);
  assert.equal(stars(7), 5);
  assert.equal(stars(-1), 0);
  assert.equal(stars(null), null);
  assert.equal(stars(''), null);
  assert.equal(stars('abc'), null);
});

test('note du jeu : moyenne des étoiles des sources, demi-étoile arrondie au-dessus', () => {
  // Super Mario World : ScreenScraper 18/20 (5), LaunchBox 4,4 (5), presse 94 (5).
  const smw = combineRatings([
    { source: 'screenscraper', score: 18 },
    { source: 'launchbox', score: 4.4, count: 900 },
    { source: 'press', score: 94, count: 1 },
  ]);
  assert.equal(smw.rating, 5);
  assert.deepEqual(smw.ratings.map((r) => r.rating), [5, 5, 5]);
  assert.equal(smw.ratings[1].count, 900);
  // 4 et 5 -> 4,5 -> 5 ; 4, 4 et 5 -> 4,33 -> 4.
  assert.equal(combineRatings([{ source: 'screenscraper', score: 15 }, { source: 'launchbox', score: 4.3 }]).rating, 5);
  assert.equal(combineRatings([{ source: 'screenscraper', score: 15 }, { source: 'launchbox', score: 4 }, { source: 'press', score: 90 }]).rating, 4);
  assert.deepEqual(combineRatings([]), { rating: null, ratings: [] });
  assert.deepEqual(combineRatings([{ source: 'launchbox', score: null }]), { rating: null, ratings: [] });
});
