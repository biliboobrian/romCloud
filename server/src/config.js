import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// Charge un fichier .env minimaliste (KEY=VALUE) s'il existe, sans écraser l'environnement.
function loadDotEnv(file) {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (!m || line.trim().startsWith('#')) continue;
    const value = m[2].replace(/^(['"])(.*)\1$/, '$2');
    if (process.env[m[1]] === undefined) process.env[m[1]] = value;
  }
}
loadDotEnv(path.join(rootDir, '.env'));

const env = process.env;
const dataDir = path.resolve(rootDir, env.DATA_DIR || 'data');

export const config = {
  rootDir,
  port: Number(env.PORT || 8080),
  host: env.HOST || '0.0.0.0',
  dataDir,
  romsDir: path.resolve(dataDir, env.ROMS_DIR || 'roms'),
  mediaDir: path.join(dataDir, 'media'),
  dbFile: path.join(dataDir, 'romcloud.db'),
  // Clé des applications (Windows, Android, TV) : lecture seule (requêtes GET) si ADMIN_KEY est
  // définie, sinon accès complet. Envoyée par "Authorization: Bearer <clé>" (ou ?key=).
  apiKey: env.API_KEY || '',
  // Clé de l'interface web d'administration : accès complet (envoi, suppression, scraping…).
  // Sans elle, API_KEY sert aux deux, comme avant.
  adminKey: env.ADMIN_KEY || '',
  // Langue par défaut (fr | en) : journal du serveur et clients sans en-tête Accept-Language.
  language: (env.DEFAULT_LANGUAGE || 'en').toLowerCase(),
  // Taille max. d'un fichier envoyé depuis l'interface web (en Mo).
  maxUploadMb: Number(env.MAX_UPLOAD_MB || 16384),
  // Au-delà de cette taille, on ne calcule pas CRC/MD5 (ScreenScraper se rabat sur nom + taille).
  hashMaxMb: Number(env.HASH_MAX_MB || 1024),
  // Jeux scrapés en même temps par une tâche (1 à 16) ; ScreenScraper limite aussi le nombre de
  // requêtes simultanées selon le compte (maxthreads), respecté dès sa première réponse.
  scrapeConcurrency: Math.min(16, Math.max(1, Math.floor(Number(env.SCRAPE_CONCURRENCY) || 1))),
  daijishouBaseUrl:
    env.DAIJISHOU_BASE_URL || 'https://raw.githubusercontent.com/TapiocaFox/Daijishou/main/platforms/',
  screenscraper: {
    devId: env.SCREENSCRAPER_DEV_ID || '',
    devPassword: env.SCREENSCRAPER_DEV_PASSWORD || '',
    softName: env.SCREENSCRAPER_SOFTNAME || 'romcloud',
    user: env.SCREENSCRAPER_USER || '',
    password: env.SCREENSCRAPER_PASSWORD || '',
    // Langues / régions préférées pour les textes et médias, par ordre de priorité.
    languages: (env.SCRAPE_LANGUAGES || 'fr,en').split(',').map((s) => s.trim()).filter(Boolean),
    regions: (env.SCRAPE_REGIONS || 'fr,eu,wor,us,ss,jp').split(',').map((s) => s.trim()).filter(Boolean),
  },
};

export function screenscraperEnabled() {
  const s = config.screenscraper;
  return Boolean(s.devId && s.devPassword);
}

for (const dir of [config.dataDir, config.romsDir, config.mediaDir]) fs.mkdirSync(dir, { recursive: true });
