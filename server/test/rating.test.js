import { test } from 'node:test';
import assert from 'node:assert/strict';

const { combineRatings, pressRating, reviewScore, stars } = await import('../src/scraper/rating.js');

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
  // (0,85 + 0,9) / 2 × 5 = 4,375
  assert.deepEqual(pressRating(claims), { rating: 4.4, count: 2 });
  assert.equal(pressRating({}), null);
  assert.equal(pressRating({ P444: [review('A', 'Q1')] }), null);
});

test('étoiles entières de 0 à 5', () => {
  assert.equal(stars(3.5), 4);
  assert.equal(stars(4.4), 4);
  assert.equal(stars('2'), 2);
  assert.equal(stars(7), 5);
  assert.equal(stars(-1), 0);
  assert.equal(stars(null), null);
  assert.equal(stars(''), null);
  assert.equal(stars('abc'), null);
});

test('note combinée en étoiles : moyenne des notes précises des sources, chacune comptant autant', () => {
  // (3,6 + 3,4 + 4,4) / 3 = 3,8 -> 4 étoiles (arrondir chaque source d'abord donnerait 4, 3, 4 -> 3,67)
  assert.equal(combineRatings([{ source: 'screenscraper', rating: 3.6 }, { source: 'launchbox', rating: 3.4 }, { source: 'press', rating: 4.4 }]), 4);
  assert.equal(combineRatings([{ source: 'launchbox', rating: 3.4 }]), 3);
  assert.equal(combineRatings([{ source: 'x', rating: null }, { source: 'y', rating: 9 }]), null);
  assert.equal(combineRatings([]), null);
});
