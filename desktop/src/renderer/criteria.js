// Recherche avancée d'une liste de jeux (même logique que l'application Android) : genre principal,
// décennie, joueurs, région, note minimale et éditeur. Chargé par la page (window.RomCloudCriteria)
// et par les tests (module CommonJS).
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.RomCloudCriteria = factory();
})(typeof self !== 'undefined' ? self : this, () => {
  const KEYS = ['genre', 'decade', 'players', 'region', 'minRating', 'publisher'];
  const PLAYERS = ['solo', 'multi', 'four', 'coop'];
  const MAX_PUBLISHERS = 30;

  const first = (text) => String(text || '').split(/[,/;]/)[0].trim() || null;
  const mainGenre = (g) => first(g.genre);
  const mainPublisher = (g) => first(g.publisher);
  const decade = (g) => (/^\d{4}/.test(g.releaseDate || '') ? Math.floor(Number(g.releaseDate.slice(0, 4)) / 10) * 10 : null);
  /** « 1-4 » -> 4, « 2 » -> 2 ; null si inconnu. */
  const maxPlayers = (g) => {
    const numbers = String(g.players || '').match(/\d+/g);
    return numbers ? Math.max(...numbers.map(Number)) : null;
  };
  const isCoop = (g) => g.details?.cooperative === true || (g.details?.modes || []).some((m) => /coop/i.test(m));
  const regions = (g) => g.details?.regions || [];

  function matchesPlayers(g, wanted) {
    const max = maxPlayers(g) || 0;
    if (wanted === 'solo') return max === 1;
    if (wanted === 'multi') return max >= 2;
    if (wanted === 'four') return max >= 4;
    if (wanted === 'coop') return isCoop(g);
    return true;
  }

  const same = (a, b) => String(a || '').toLowerCase() === String(b || '').toLowerCase();

  /** Le jeu correspond-il à tous les critères choisis (valeur absente = indifférent) ? */
  function matches(g, c) {
    return (!c.genre || same(mainGenre(g), c.genre))
      && (c.decade == null || decade(g) === c.decade)
      && (!c.players || matchesPlayers(g, c.players))
      && (!c.region || regions(g).includes(c.region))
      && (c.minRating == null || (g.rating || 0) >= c.minRating)
      && (!c.publisher || same(mainPublisher(g), c.publisher));
  }

  /** Nombre de critères choisis. */
  const count = (c) => KEYS.filter((k) => c[k] != null && c[k] !== '').length;

  /** Valeurs les plus fréquentes d'abord, puis par ordre alphabétique. */
  function byFrequency(values) {
    const counts = new Map();
    for (const v of values) if (v) counts.set(v, (counts.get(v) || 0) + 1);
    return [...counts.entries()]
      .sort((a, b) => b[1] - a[1] || a[0].toLowerCase().localeCompare(b[0].toLowerCase()))
      .map(([v]) => v);
  }

  /** Valeurs proposées pour chaque critère, d'après les jeux de la liste. */
  function facets(games) {
    return {
      genre: byFrequency(games.map(mainGenre)),
      decade: [...new Set(games.map(decade).filter((d) => d != null))].sort((a, b) => a - b),
      players: PLAYERS.filter((p) => games.some((g) => matchesPlayers(g, p))),
      region: byFrequency(games.flatMap(regions)),
      minRating: [3, 4].filter((r) => games.some((g) => (g.rating || 0) >= r)),
      publisher: byFrequency(games.map(mainPublisher)).slice(0, MAX_PUBLISHERS),
    };
  }

  const isEmpty = (f) => KEYS.every((k) => !f[k].length);

  return { KEYS, PLAYERS, matches, count, facets, isEmpty, maxPlayers, isCoop };
});
