const { test } = require('node:test');
const assert = require('node:assert/strict');
const Criteria = require('../src/renderer/criteria');

const game = (id, fields = {}) => ({ id, title: `Jeu ${id}`, fileName: `${id}.md`, details: {}, ...fields });
const sor2 = game(1, { genre: "Beat 'em Up, Action", releaseDate: '1992-12-20', players: '1-2', rating: 4.6, publisher: 'Sega', details: { regions: ['Europe'], cooperative: true } });
const sonic = game(2, { genre: 'Platform', releaseDate: '1991', players: '1', rating: 4.2, publisher: 'Sega, Tectoy', details: { regions: ['World'] } });
const micro = game(3, { genre: 'Racing', releaseDate: '1994-07', players: '1-4', rating: 3.1, publisher: 'Codemasters', details: { modes: ['Mode coopératif'] } });
const unknown = game(4);
const games = [sor2, sonic, micro, unknown];
const ids = (c) => games.filter((g) => Criteria.matches(g, c)).map((g) => g.id);

test('chaque critère filtre la liste, plusieurs se cumulent', () => {
  assert.deepEqual(ids({}), [1, 2, 3, 4]);
  assert.deepEqual(ids({ genre: "beat 'em up" }), [1]);
  assert.deepEqual(ids({ decade: 1990 }), [1, 2, 3]);
  assert.deepEqual(ids({ players: 'solo' }), [2]);
  assert.deepEqual(ids({ players: 'multi' }), [1, 3]);
  assert.deepEqual(ids({ players: 'four' }), [3]);
  assert.deepEqual(ids({ players: 'coop' }), [1, 3]);
  assert.deepEqual(ids({ region: 'World' }), [2]);
  assert.deepEqual(ids({ minRating: 4 }), [1, 2]);
  assert.deepEqual(ids({ publisher: 'Sega', players: 'multi' }), [1]);
  assert.equal(Criteria.count({ publisher: 'Sega', players: 'multi', genre: undefined }), 2);
});

test('valeurs proposées d’après les jeux de la liste', () => {
  const f = Criteria.facets(games);
  assert.deepEqual(f.genre, ["Beat 'em Up", 'Platform', 'Racing']);
  assert.deepEqual(f.decade, [1990]);
  assert.deepEqual(f.players, ['solo', 'multi', 'four', 'coop']);
  assert.deepEqual(f.region, ['Europe', 'World']);
  assert.deepEqual(f.minRating, [3, 4]);
  assert.deepEqual(f.publisher, ['Sega', 'Codemasters']);
  assert.equal(Criteria.isEmpty(f), false);
  assert.equal(Criteria.isEmpty(Criteria.facets([unknown])), true);
});
