// Traductions des messages du serveur (erreurs de l'API, messages enregistrés, journal).
//
// Les messages destinés aux clients sont manipulés sous forme de jeton « i18n:<clé>|<variables JSON> »
// (voir token()). Un jeton peut être stocké (erreur de scraping en base, libellé de tâche) puis
// traduit au moment de la réponse, dans la langue du client (en-tête Accept-Language).
import { config } from './config.js';

export const LANGUAGES = ['fr', 'en'];

const messages = {
  en: {
    'errors.apiKey': 'Invalid or missing API key',
    'errors.adminKey': 'This key only gives read access: use the administration key (ADMIN_KEY)',
    'errors.routeNotFound': 'Unknown route',
    'errors.internal': 'Internal error',
    'errors.fileTooLarge': 'File too large',
    'errors.systemNotFound': 'Unknown system: {id}',
    'errors.gameNotFound': 'Unknown game: {id}',
    'errors.invalidRegex': 'Invalid regular expression: {re}',
    'errors.unknownPlatform': 'Unknown platform: {platform} (android, androidtv, windows)',
    'errors.invalidFolder': 'Invalid folder name (letters, digits, space, . _ -)',
    'errors.invalidId': 'Invalid identifier (a-z, 0-9, . _ -)',
    'errors.nameRequired': 'The name is required',
    'errors.titleRequired': 'The title is required',
    'errors.systemExists': 'The system “{id}” already exists',
    'errors.folderInUse': 'The folder “{folder}” is already in use',
    'errors.imageFormat': 'Unsupported image format (PNG, JPEG, WebP or GIF)',
    'errors.imageMissing': 'Missing image',
    'errors.downloadFailed': 'Download failed: {url} ({status})',
    'errors.invalidPlatform': 'Invalid Daijishou platform file',
    'errors.invalidFileName': 'Invalid file name',
    'errors.noSystemImage': 'This system has no image',
    'errors.noPlatform': 'No platform specified',
    'errors.fileNotFound': 'File not found on the server',
    'errors.unknownMediaType': 'Unknown media type',
    'errors.noMedia': 'No media',
    'errors.unknownSource': 'Unknown source: {source}',
    'errors.unknownJob': 'Unknown job',
    'errors.noGames': 'No game specified',
    'errors.gamesNotInSystem': 'Games not in this system: {ids}',
    'errors.invalidBiosPath': 'Invalid BIOS path: {path}',
    'errors.biosNotFound': 'Unknown BIOS: {id}',
    'errors.biosZipUnreadable': 'The archive sent for {path} is empty or unreadable',
    'errors.biosZipTooLarge': 'The archive sent for {path} is too large',
    'errors.biosHashMismatch': '“{name}” refused: it is not the expected BIOS for {path} (MD5 {md5}, SHA1 {sha1}; expected: {expected})',
    'errors.noFiles': 'No file sent',
    'errors.invalidApk': '“{name}” is not a readable APK ({detail})',
    'errors.apkNotFound': 'Unknown APK: {id}',
    'errors.invalidUsername': 'Invalid user name (3 to 32 letters, digits, . _ -)',
    'errors.passwordTooShort': 'The password must have at least {n} characters',
    'errors.usernameTaken': 'This user name is already taken',
    'errors.badCredentials': 'Wrong user name or password',
    'errors.tooManyAttempts': 'Too many failed attempts: try again in a few minutes',
    'errors.accountDisabled': 'This account is disabled',
    'errors.signedOut': 'Signed out: sign in again',
    'errors.pairExpired': 'This code has expired: show a new QR code on the TV',
    'errors.streamTvOnly': 'Only an Android TV can receive a streamed game',
    'errors.streamReceiver': 'Invalid TV address, port or key',
    'errors.pairSecret': 'Invalid pairing request',
    'errors.invalidDuration': 'Invalid duration',
    'errors.invalidSaveKind': 'Unknown save type',
    'errors.invalidCore': 'Invalid core name',
    'errors.emptySave': 'Empty save',
    'errors.saveNotFound': 'No online save for this game',
    'errors.messageRequired': 'The message is required',
    'errors.userNotFound': 'Unknown user',
    'errors.sessionNotFound': 'Unknown session',
    'scrape.libretroNotConfigured': 'Libretro system name not configured',
    'scrape.ssNotConfigured': 'ScreenScraper credentials not configured',
    'scrape.ssQuota': 'ScreenScraper quota reached ({status}): {detail}',
    'scrape.ssError': 'ScreenScraper {status}: {detail}',
    'scrape.ssUnreadable': 'Unreadable ScreenScraper response: {detail}',
    'jobs.newGames': '{system}: {count} new game(s)',
    'jobs.systemGames': '{system}: {count} game(s)',
    'messages.nothingToScrape': 'No game to scrape',
    'log.started': 'RomCloud started on port {port}',
    'log.roms': 'ROMs: {dir}',
    'log.apiKeyOn': 'API key: enabled',
    'log.apiKeyOff': 'API key: disabled (server open on the network)',
    'log.adminKeyOn': 'Administration key: enabled (API key: read only)',
    'log.adminKeyOff': 'Administration key: not set (the API key gives full access)',
    'log.ssOn': 'ScreenScraper: configured',
    'log.ssOff': 'ScreenScraper: not configured (Libretro only)',
    'log.initialScanFailed': 'Initial scan failed: {error}',
  },
  fr: {
    'errors.apiKey': 'Clé d’API invalide ou manquante',
    'errors.adminKey': 'Cette clé ne donne accès qu’en lecture : utilisez la clé d’administration (ADMIN_KEY)',
    'errors.routeNotFound': 'Route inconnue',
    'errors.internal': 'Erreur interne',
    'errors.fileTooLarge': 'Fichier trop volumineux',
    'errors.systemNotFound': 'Système inconnu : {id}',
    'errors.gameNotFound': 'Jeu inconnu : {id}',
    'errors.invalidRegex': 'Expression régulière invalide : {re}',
    'errors.unknownPlatform': 'Plateforme inconnue : {platform} (android, androidtv, windows)',
    'errors.invalidFolder': 'Nom de dossier invalide (lettres, chiffres, espace, . _ -)',
    'errors.invalidId': 'Identifiant invalide (a-z, 0-9, . _ -)',
    'errors.nameRequired': 'Le nom est obligatoire',
    'errors.titleRequired': 'Le titre est obligatoire',
    'errors.systemExists': 'Le système « {id} » existe déjà',
    'errors.folderInUse': 'Le dossier « {folder} » est déjà utilisé',
    'errors.imageFormat': 'Format d’image non supporté (PNG, JPEG, WebP ou GIF)',
    'errors.imageMissing': 'Image manquante',
    'errors.downloadFailed': 'Échec du téléchargement de {url} ({status})',
    'errors.invalidPlatform': 'Fichier plateforme Daijishou invalide',
    'errors.invalidFileName': 'Nom de fichier invalide',
    'errors.noSystemImage': 'Aucune image pour ce système',
    'errors.noPlatform': 'Aucune plateforme indiquée',
    'errors.fileNotFound': 'Fichier introuvable sur le serveur',
    'errors.unknownMediaType': 'Type de média inconnu',
    'errors.noMedia': 'Aucun média',
    'errors.unknownSource': 'Source inconnue : {source}',
    'errors.unknownJob': 'Tâche inconnue',
    'errors.noGames': 'Aucun jeu indiqué',
    'errors.gamesNotInSystem': 'Jeux hors de ce système : {ids}',
    'errors.invalidBiosPath': 'Chemin de BIOS invalide : {path}',
    'errors.biosNotFound': 'BIOS inconnu : {id}',
    'errors.biosZipUnreadable': 'L’archive envoyée pour {path} est vide ou illisible',
    'errors.biosZipTooLarge': 'L’archive envoyée pour {path} est trop volumineuse',
    'errors.biosHashMismatch': '« {name} » refusé : ce n’est pas le BIOS attendu pour {path} (MD5 {md5}, SHA1 {sha1} ; attendu : {expected})',
    'errors.noFiles': 'Aucun fichier envoyé',
    'errors.invalidApk': '« {name} » n’est pas un APK lisible ({detail})',
    'errors.apkNotFound': 'APK inconnu : {id}',
    'errors.invalidUsername': 'Nom d’utilisateur invalide (3 à 32 lettres, chiffres, . _ -)',
    'errors.passwordTooShort': 'Le mot de passe doit faire au moins {n} caractères',
    'errors.usernameTaken': 'Ce nom d’utilisateur est déjà pris',
    'errors.badCredentials': 'Nom d’utilisateur ou mot de passe incorrect',
    'errors.tooManyAttempts': 'Trop de tentatives échouées : réessayez dans quelques minutes',
    'errors.accountDisabled': 'Ce compte est désactivé',
    'errors.signedOut': 'Session terminée : reconnectez-vous',
    'errors.pairExpired': 'Ce code a expiré : affichez un nouveau QR code sur la TV',
    'errors.streamTvOnly': 'Seule une Android TV peut recevoir un jeu diffusé',
    'errors.streamReceiver': 'Adresse, port ou clé de la TV invalide',
    'errors.pairSecret': 'Demande de connexion invalide',
    'errors.invalidDuration': 'Durée invalide',
    'errors.invalidSaveKind': 'Type de sauvegarde inconnu',
    'errors.invalidCore': 'Nom de cœur invalide',
    'errors.emptySave': 'Sauvegarde vide',
    'errors.saveNotFound': 'Aucune sauvegarde en ligne pour ce jeu',
    'errors.messageRequired': 'Le message est obligatoire',
    'errors.userNotFound': 'Utilisateur inconnu',
    'errors.sessionNotFound': 'Session inconnue',
    'scrape.libretroNotConfigured': 'Nom de système Libretro non configuré',
    'scrape.ssNotConfigured': 'Identifiants ScreenScraper non configurés',
    'scrape.ssQuota': 'Quota ScreenScraper atteint ({status}) : {detail}',
    'scrape.ssError': 'ScreenScraper {status} : {detail}',
    'scrape.ssUnreadable': 'Réponse ScreenScraper illisible : {detail}',
    'jobs.newGames': '{system} : {count} nouveau(x) jeu(x)',
    'jobs.systemGames': '{system} : {count} jeu(x)',
    'messages.nothingToScrape': 'Aucun jeu à scraper',
    'log.started': 'RomCloud démarré sur le port {port}',
    'log.roms': 'ROMs : {dir}',
    'log.apiKeyOn': 'Clé d’API : activée',
    'log.apiKeyOff': 'Clé d’API : désactivée (serveur ouvert sur le réseau)',
    'log.adminKeyOn': 'Clé d’administration : activée (clé d’API : lecture seule)',
    'log.adminKeyOff': 'Clé d’administration : non définie (la clé d’API donne un accès complet)',
    'log.ssOn': 'ScreenScraper : configuré',
    'log.ssOff': 'ScreenScraper : non configuré (Libretro seulement)',
    'log.initialScanFailed': 'Scan initial impossible : {error}',
  },
};

export const defaultLanguage = LANGUAGES.includes(config.language) ? config.language : 'en';

/** Traduit une clé ; repli sur l'anglais puis sur la clé elle-même. */
export function t(lang, key, vars = {}) {
  const text = messages[lang]?.[key] ?? messages.en[key] ?? key;
  return text.replace(/\{(\w+)\}/g, (m, name) => (vars[name] !== undefined ? String(vars[name]) : m));
}

const PREFIX = 'i18n:';

/** Jeton de message traduisible, stockable tel quel (base, tâches) et traduit à la réponse. */
export function token(key, vars) {
  return PREFIX + key + (vars && Object.keys(vars).length ? `|${JSON.stringify(vars)}` : '');
}

export function translateToken(value, lang) {
  if (typeof value !== 'string' || !value.startsWith(PREFIX)) return value;
  const body = value.slice(PREFIX.length);
  const sep = body.indexOf('|');
  const key = sep < 0 ? body : body.slice(0, sep);
  let vars = {};
  if (sep >= 0) {
    try {
      vars = JSON.parse(body.slice(sep + 1));
    } catch {
      /* variables illisibles : message sans substitution */
    }
  }
  return t(lang, key, vars);
}

/** Traduit tous les jetons contenus dans une réponse JSON (objets et tableaux imbriqués). */
export function localize(value, lang) {
  if (typeof value === 'string') return translateToken(value, lang);
  if (Array.isArray(value)) return value.map((v) => localize(v, lang));
  if (value && typeof value === 'object' && value.constructor === Object) {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = localize(v, lang);
    return out;
  }
  return value;
}

/** Langue du client : en-tête Accept-Language (ex. « fr-FR,fr;q=0.9,en;q=0.8 »), sinon langue par défaut. */
export function requestLanguage(req) {
  const header = req.get('accept-language') || '';
  for (const part of header.split(',')) {
    const code = part.split(';')[0].trim().slice(0, 2).toLowerCase();
    if (LANGUAGES.includes(code)) return code;
  }
  return defaultLanguage;
}

/** Erreur dont le message est un jeton traduisible. */
export class I18nError extends Error {
  constructor(key, vars) {
    super(token(key, vars));
    this.key = key;
    this.vars = vars;
  }
}
