import os from 'node:os';
import path from 'node:path';
import express from 'express';
import { api } from './api.js';
import { config, screenscraperEnabled } from './config.js';
import { scanAll } from './library.js';

const app = express();
app.disable('x-powered-by');
app.use('/api', api);
app.use(express.static(path.join(config.rootDir, 'public')));

// Synchronise la base avec les dossiers au démarrage (ROMs copiées à la main, etc.).
try {
  scanAll();
} catch (err) {
  console.error('Scan initial impossible :', err.message);
}

app.listen(config.port, config.host, () => {
  const addresses = Object.values(os.networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal)
    .map((i) => `http://${i.address}:${config.port}`);
  console.log(`RomCloud démarré sur le port ${config.port}`);
  for (const a of addresses) console.log(`  → ${a}`);
  console.log(`ROMs : ${config.romsDir}`);
  console.log(`Clé d'API : ${config.apiKey ? 'activée' : 'désactivée (serveur ouvert sur le réseau)'}`);
  console.log(`ScreenScraper : ${screenscraperEnabled() ? 'configuré' : 'non configuré (Libretro seulement)'}`);
});
