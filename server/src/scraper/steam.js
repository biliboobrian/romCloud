// Boutique Steam (sans compte ni clé) : source des jeux PC, dont les jeux récents ou indépendants
// absents de ScreenScraper, de LaunchBox et de Wikipedia. Jeu retrouvé par son identifiant Steam
// (AppID : choisi dans la fiche du jeu, ou lu dans les fichiers du jeu, « steam_appid.txt » et
// fichiers .ini des émulateurs Steam), sinon par son titre exact ; plusieurs jeux du même nom : le
// plus commenté. Titre, résumé dans la langue préférée, développeur, éditeur, genres, date de
// sortie, jaquette (capsule de la bibliothèque, au format portrait), captures et note des joueurs.

const STORE = 'https://store.steampowered.com';
const ASSETS = 'https://shared.akamai.steamstatic.com/store_item_assets/';

/** Langues de Steam (paramètre « l ») des codes de langue du scraping. */
const LANGUAGES = {
  fr: 'french', en: 'english', de: 'german', es: 'spanish', it: 'italian', pt: 'portuguese', nl: 'dutch',
  pl: 'polish', ru: 'russian', ja: 'japanese', ko: 'koreana', zh: 'schinese', sv: 'swedish', da: 'danish',
  fi: 'finnish', no: 'norwegian', cs: 'czech', hu: 'hungarian', tr: 'turkish',
};

/** Pays (prix et visibilité) d'une langue. */
const COUNTRIES = { fr: 'FR', de: 'DE', es: 'ES', it: 'IT', pt: 'PT', nl: 'NL', pl: 'PL', ja: 'JP', ko: 'KR', en: 'US' };

/** Avis des joueurs pris en compte à partir de ce nombre (note peu fiable en dessous). */
const MIN_REVIEWS = 10;

/** Fichiers du jeu où chercher son AppID (petits fichiers texte). */
export const APPID_FILE_RE = /(^|\/)(steam_appid\.txt|steam_emu\.ini|steam_api(64)?\.ini|codex\.ini|cream_api\.ini|smartsteamemu\.ini|ali213\.ini|tenoke\.ini|rune\.ini|onlinefix\.ini|configs\.app\.ini)$/i;

/** AppID d'un fichier du jeu (« 4297840 » seul, ou « AppId=4297840 » d'un .ini) ; null sinon. */
export function appIdFromText(name, text) {
  const s = String(text || '');
  if (/steam_appid\.txt$/i.test(name)) return /^\s*(\d{2,10})\s*$/.exec(s)?.[1] ?? null;
  return /^\s*app_?id\s*=\s*(\d{2,10})\s*$/im.exec(s)?.[1] ?? null;
}

/** Titre comparable : minuscules, sans marques (™ ®), accents ni ponctuation. */
export function normalizeTitle(title) {
  return String(title || '')
    .replace(/[™®©]/g, '')
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

async function json(url) {
  const res = await fetch(url, { headers: { 'User-Agent': 'RomCloud' }, signal: AbortSignal.timeout(20000) });
  if (!res.ok) throw new Error(`Steam : HTTP ${res.status}`);
  return res.json();
}

/** Fiches de la boutique (nom, résumé, date, avis, images) des jeux [appIds]. */
async function storeItems(appIds, language) {
  const input = {
    ids: appIds.map((appid) => ({ appid: Number(appid) })),
    context: { language: LANGUAGES[language] || 'english', country_code: COUNTRIES[language] || 'US' },
    data_request: { include_assets: true, include_basic_info: true, include_release: true, include_reviews: true, include_screenshots: true },
  };
  const data = await json(`https://api.steampowered.com/IStoreBrowseService/GetItems/v1/?input_json=${encodeURIComponent(JSON.stringify(input))}`);
  return (data?.response?.store_items || []).filter((item) => item.success === 1 && item.appid);
}

/** Jeux de la boutique dont le nom est exactement [title] (AppID). */
async function searchExact(title, language) {
  const data = await json(`${STORE}/api/storesearch/?term=${encodeURIComponent(title)}&l=${LANGUAGES[language] || 'english'}&cc=${COUNTRIES[language] || 'US'}`);
  const wanted = normalizeTitle(title);
  return (data?.items || []).filter((item) => item.type === 'app' && normalizeTitle(item.name) === wanted).map((item) => item.id);
}

/** Genres de la boutique (dans la langue [language]), « Action, Aventure » ; null sans eux. */
async function genresOf(appId, language) {
  const data = await json(`${STORE}/api/appdetails?appids=${appId}&l=${LANGUAGES[language] || 'english'}&filters=genres`).catch(() => null);
  const genres = data?.[appId]?.data?.genres?.map((g) => g.description).filter(Boolean);
  return genres?.length ? genres.join(', ') : null;
}

/** Adresse d'une image de la boutique (« steam/apps/… » ou nom de fichier de [assets]). */
function assetUrl(assets, file) {
  if (!file) return null;
  return `${ASSETS}${assets.asset_url_format.replace('${FILENAME}', file)}`;
}

/** Résultat du scraping d'une fiche de la boutique. */
export function steamMeta(item, genre) {
  const info = item.basic_info || {};
  const assets = item.assets || {};
  const names = (list) => (list || []).map((p) => p.name).filter(Boolean).join(', ') || null;
  const released = item.release?.steam_release_date || item.release?.original_release_date;
  const reviews = item.reviews?.summary_filtered;
  const shots = (item.screenshots?.all_ages_screenshots || [])
    .slice()
    .sort((a, b) => (a.ordinal ?? 0) - (b.ordinal ?? 0))
    .map((s) => `${ASSETS}${s.filename}`);
  return {
    appId: item.appid,
    title: item.name,
    description: info.short_description || null,
    developer: names(info.developers),
    publisher: names(info.publishers),
    genre,
    releaseDate: released ? new Date(released * 1000).toISOString().slice(0, 10) : null,
    // Note : part des avis positifs (sur 100), à partir de MIN_REVIEWS avis.
    rating: reviews?.review_count >= MIN_REVIEWS && Number.isFinite(reviews.percent_positive) ? { score: reviews.percent_positive, count: reviews.review_count } : null,
    media: {
      // Capsule de la bibliothèque Steam (600 × 900) : la jaquette ; en-tête de la boutique à défaut.
      boxart: assetUrl(assets, assets.library_capsule_2x || assets.library_capsule),
      coverFallback: assetUrl(assets, assets.header || assets.main_capsule),
      screenshot: shots[0] || null,
      screenshots: shots.slice(1),
    },
    url: `${STORE}/app/${item.appid}/`,
  };
}

/**
 * Jeu de la boutique Steam : par son [appId] s'il est connu, sinon par l'un des [titles] (nom
 * exact ; plusieurs jeux du même nom : le plus commenté). null s'il est introuvable.
 */
export async function scrapeSteam({ appId, titles = [], languages = ['en'] }) {
  const language = languages[0] || 'en';
  let item = null;
  if (appId) {
    [item] = await storeItems([appId], language);
  } else {
    for (const title of [...new Set(titles.filter(Boolean))]) {
      const ids = await searchExact(title, language);
      if (!ids.length) continue;
      const items = await storeItems(ids.slice(0, 10), language);
      item = items.sort((a, b) => (b.reviews?.summary_filtered?.review_count ?? 0) - (a.reviews?.summary_filtered?.review_count ?? 0))[0];
      if (item) break;
    }
  }
  if (!item) return null;
  return steamMeta(item, await genresOf(item.appid, language));
}
