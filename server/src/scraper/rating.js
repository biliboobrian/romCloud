// Note d'un jeu en étoiles entières (0 à 5) : moyenne des notes trouvées par le scraping, chaque
// source comptant autant, arrondie à l'étoile.
//  - screenscraper : note de ScreenScraper (sur 20) ;
//  - launchbox : note de la communauté LaunchBox Games DB (sur 5, au moins 3 votes) ;
//  - press : notes de la presse relevées par Wikidata (Metacritic, GameRankings, Famitsu, IGN…).

export const round1 = (n) => Math.round(n * 10) / 10;

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
 * qualificatif P447) : moyenne de chaque site, puis des sites ; { rating (sur 5), count (sites) } ou null.
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
  return { rating: round1(average([...bySite.values()].map(average)) * 5), count: bySite.size };
}

/** Étoiles combinant les notes sur 5 [{ source, rating }] valides (moyenne des notes précises), null sans aucune. */
export function combineRatings(ratings) {
  const values = ratings.map((r) => r?.rating).filter((v) => Number.isFinite(v) && v >= 0 && v <= 5);
  return values.length ? stars(values.reduce((a, b) => a + b, 0) / values.length) : null;
}
