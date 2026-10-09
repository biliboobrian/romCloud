// Note d'un jeu en étoiles entières (0 à 5) à partir des notes trouvées par le scraping :
//  - screenscraper : note de ScreenScraper (sur 20) ;
//  - launchbox : note de la communauté LaunchBox Games DB (sur 5, au moins 3 votes) ;
//  - press : notes de la presse relevées par Wikidata (Metacritic, GameRankings, Famitsu, IGN…, sur 100).
// Chaque source se tasse à sa façon (communauté LaunchBox : médiane 3,5/5 et seulement 6 % des jeux
// au-dessus de 4,5) : un simple arrondi ne donnerait presque jamais 5 étoiles. Chaque note est donc
// convertie en étoiles avec le barème de sa source, puis les étoiles des sources sont moyennées.

export const round1 = (n) => Math.round(n * 10) / 10;

/**
 * Barèmes : note minimale de la source pour 2, 3, 4 et 5 étoiles (1 étoile en dessous). Visent à
 * peu près la même répartition pour chaque source : 5 étoiles pour les ~15 % meilleurs jeux, 4 pour
 * les ~25 % suivants, 3 pour ~35 %, 2 pour ~15 %, 1 pour les ~10 % restants (communauté LaunchBox,
 * 82 500 jeux notés en octobre 2026 : 10 % sous 2,4, 25 % sous 3,0, 62 % sous 3,7, 87 % sous 4,2).
 * Presse : échelle de Metacritic (75 : « généralement favorable », 85 et plus : les grands classiques).
 */
export const SCALES = {
  screenscraper: [8, 11, 14, 17],
  launchbox: [2.4, 3.0, 3.7, 4.2],
  press: [45, 60, 75, 85],
};

/** Étoiles (1 à 5) d'une note [score] de la source [source] selon son barème ; null sans barème ou sans note. */
export function sourceStars(source, score) {
  const scale = SCALES[source];
  if (!scale || !Number.isFinite(score)) return null;
  return 1 + scale.filter((min) => score >= min).length;
}

/** Nombre d'étoiles entières (0 à 5) d'une note sur 5 ; null si ce n'est pas un nombre. */
export function stars(rating) {
  const n = Number(rating);
  return rating === null || rating === undefined || rating === '' || !Number.isFinite(n) ? null : Math.min(5, Math.max(0, Math.round(n)));
}

/** Note d'une critique (« 93/100 », « 36/40 », « 8,5/10 », « 85% ») entre 0 et 1 ; null si illisible (« A- »…). */
export function reviewScore(text) {
  const s = String(text ?? '').trim().replace(/,/g, '.');
  let m = s.match(/^(\d+(?:\.\d+)?)\s*\/\s*(\d+(?:\.\d+)?)$/);
  if (m) {
    const [value, max] = [Number(m[1]), Number(m[2])];
    return max > 0 && value <= max ? value / max : null;
  }
  m = s.match(/^(\d+(?:\.\d+)?)\s*%$/);
  if (m) return Number(m[1]) <= 100 ? Number(m[1]) / 100 : null;
  return null;
}

/**
 * Notes de la presse dans les déclarations Wikidata (P444 « note de critique », site en
 * qualificatif P447) : moyenne de chaque site, puis des sites ; { score (sur 100), count (sites) } ou null.
 */
export function pressRating(claims) {
  const bySite = new Map();
  for (const claim of claims?.P444 || []) {
    if (claim.rank === 'deprecated') continue;
    const score = reviewScore(claim.mainsnak?.datavalue?.value);
    if (score === null) continue;
    const site = claim.qualifiers?.P447?.[0]?.datavalue?.value?.id || '';
    bySite.set(site, [...(bySite.get(site) || []), score]);
  }
  if (!bySite.size) return null;
  const average = (list) => list.reduce((a, b) => a + b, 0) / list.length;
  return { score: round1(average([...bySite.values()].map(average)) * 100), count: bySite.size };
}

/**
 * Notes des sources [{ source, score, count }] avec leurs étoiles ([rating]), et note du jeu :
 * moyenne des étoiles des sources, arrondie (une demi-étoile compte pour une entière : 4 et 5 -> 5).
 */
export function combineRatings(list) {
  const ratings = list
    .map((r) => ({ ...r, rating: sourceStars(r.source, r.score) }))
    .filter((r) => r.rating !== null);
  const rating = ratings.length ? Math.round(ratings.reduce((a, r) => a + r.rating, 0) / ratings.length) : null;
  return { rating, ratings };
}
