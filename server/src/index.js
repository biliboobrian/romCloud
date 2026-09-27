import os from 'node:os';
import path from 'node:path';
import express from 'express';
import { api } from './api.js';
import { config, screenscraperEnabled } from './config.js';
import { defaultLanguage, t, translateToken } from './i18n.js';
import { scanAll } from './library.js';

const app = express();
app.disable('x-powered-by');
app.use('/api', api);
app.use(express.static(path.join(config.rootDir, 'public')));

// Synchronise la base avec les dossiers au démarrage (ROMs copiées à la main, etc.).
try {
  scanAll();
} catch (err) {
  console.error(t(defaultLanguage, 'log.initialScanFailed', { error: translateToken(err.message, defaultLanguage) }));
}

app.listen(config.port, config.host, () => {
  const addresses = Object.values(os.networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => `http://${i.address}:${config.port}`);
  console.log(t(defaultLanguage, 'log.started', { port: config.port }));
  for (const a of addresses) console.log(`  → ${a}`);
  console.log(t(defaultLanguage, 'log.roms', { dir: config.romsDir }));
  console.log(t(defaultLanguage, config.apiKey ? 'log.apiKeyOn' : 'log.apiKeyOff'));
  console.log(t(defaultLanguage, screenscraperEnabled() ? 'log.ssOn' : 'log.ssOff'));
});
