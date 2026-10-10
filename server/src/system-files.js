// Fichiers nécessaires aux émulateurs en dehors des fiches des cœurs libretro : clés, firmwares de
// console et fichiers de données, rangés avec les BIOS du système (data/bios/<système>/<chemin>) et
// téléchargés par les applications comme eux. Certains ont une source sur Internet : serveurs de
// mise à jour officiels du constructeur, ou dépôt libre ; la dernière version y est cherchée.
// Les clés et les BIOS sous droits d'auteur n'ont pas de source : ils doivent être extraits de sa
// propre console et envoyés sur le serveur.
import { HttpError } from './http-error.js';

const USER_AGENT = 'Mozilla/5.0 (RomCloud)';
// Listes de Sony en HTTP : leur HTTPS utilise une autorité de certification propre à Sony, refusée
// par Node ; les fichiers sont ensuite contrôlés par leur taille annoncée.

/**
 * Fichiers par système (identifiant ou nom court) : `kind` (bios, keys, firmware, data), `required`,
 * `folder` (dossier rempli par l'envoi d'un .zip), émulateurs concernés, description, et `source`
 * (clé de SOURCES) quand le fichier peut être récupéré sur Internet.
 */
const FILES = {
  switch: [
    {
      path: 'switch/prod.keys', kind: 'keys', required: true, emulators: ['Eden', 'Ryujinx', 'Citron'],
      description: { fr: 'Clés de la console (à extraire de votre Switch)', en: 'Console keys (dumped from your Switch)' },
    },
    {
      path: 'switch/title.keys', kind: 'keys', required: false, emulators: ['Eden', 'Ryujinx', 'Citron'],
      description: { fr: 'Clés des jeux achetés (à extraire de votre Switch)', en: 'Keys of purchased games (dumped from your Switch)' },
    },
    {
      // Gardé en .zip (pas extrait) : seul format accepté par Eden pour Android, aussi lu par Ryujinx.
      path: 'switch/firmware.zip', kind: 'firmware', required: false, emulators: ['Eden', 'Ryujinx', 'Citron'],
      description: {
        fr: 'Firmware de la console (.zip des fichiers .nca, à extraire de votre Switch), à installer depuis l’émulateur',
        en: 'Console firmware (.zip of the .nca files, dumped from your Switch), to install from the emulator',
      },
    },
  ],
  '3ds': [
    {
      path: '3ds/aes_keys.txt', kind: 'keys', required: false, emulators: ['Azahar'],
      description: { fr: 'Clés AES (à extraire de votre 3DS) : jeux et logiciels système chiffrés', en: 'AES keys (dumped from your 3DS): encrypted games and system software' },
    },
  ],
  wiiu: [
    {
      path: 'wiiu/keys.txt', kind: 'keys', required: false, emulators: ['Cemu'],
      description: { fr: 'Clés des jeux chiffrés (.wud, .wux), une par ligne', en: 'Keys of encrypted games (.wud, .wux), one per line' },
    },
  ],
  ps3: [
    {
      path: 'ps3/PS3UPDAT.PUP', kind: 'firmware', required: true, emulators: ['RPCS3', 'aPS3e'], source: 'sony-ps3',
      description: { fr: 'Firmware de la PS3 (installé automatiquement par RomCloud pour Windows)', en: 'PS3 firmware (installed automatically by RomCloud for Windows)' },
    },
  ],
  vita: [
    {
      path: 'vita/PSVUPDAT.PUP', kind: 'firmware', required: true, emulators: ['Vita3K'], source: 'sony-vita',
      description: { fr: 'Firmware de la PS Vita, à installer depuis Vita3K', en: 'PS Vita firmware, to install from Vita3K' },
    },
    {
      path: 'vita/PSP2UPDAT.PUP', kind: 'firmware', required: false, emulators: ['Vita3K'], source: 'sony-vita-fonts',
      description: { fr: 'Polices de la PS Vita (paquet de données système), à installer depuis Vita3K', en: 'PS Vita fonts (system data package), to install from Vita3K' },
    },
  ],
  xbox: [
    {
      path: 'xbox/mcpx_1.0.bin', kind: 'bios', required: true, emulators: ['xemu'],
      description: { fr: 'ROM de démarrage MCPX (à extraire de votre Xbox)', en: 'MCPX boot ROM (dumped from your Xbox)' },
    },
    {
      path: 'xbox/Complex_4627.bin', kind: 'bios', required: true, emulators: ['xemu'],
      description: { fr: 'BIOS de la Xbox (à extraire de votre Xbox)', en: 'Xbox BIOS (dumped from your Xbox)' },
    },
    {
      path: 'xbox/xbox_hdd.qcow2', kind: 'data', required: true, emulators: ['xemu'],
      description: { fr: 'Disque dur de la Xbox (image qcow2)', en: 'Xbox hard disk (qcow2 image)' },
    },
  ],
};

/** Sources des fichiers attendus par les fiches libretro (chemin en minuscules -> source). */
const CORE_FILE_SOURCES = {
  'pcsx2/resources/gameindex.yaml': 'pcsx2-gameindex',
};

async function fetchText(url) {
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT }, signal: AbortSignal.timeout(15000) });
  if (!res.ok) throw new HttpError(502, 'errors.downloadFailed', { url, status: res.status });
  return res.text();
}

/** Mise à jour complète de la PS3 : « …SystemSoftwareVersion=4.9300;CDN=http://…/PS3UPDAT.PUP;… ». */
export function parsePs3UpdateList(text) {
  for (const line of text.split(/\r?\n/)) {
    if (!/PS3UPDAT\.PUP/i.test(line)) continue;
    const version = /SystemSoftwareVersion=([\d.]+)/.exec(line)?.[1];
    const url = /CDN=([^;]+)/.exec(line)?.[1];
    if (version && url) return { version: version.replace(/0+$/, '').replace(/\.$/, ''), url };
  }
  return null;
}

/**
 * Liste de mise à jour de la PS Vita : firmware complet (« update_type="full" ») ou paquet de
 * données système (polices, « spkg_type="systemdata" »).
 */
export function parseVitaUpdateList(xml, part) {
  const attribute = (tag, name) => new RegExp(`\\b${name}="([^"]*)"`).exec(tag)?.[1] ?? null;
  const image = (block) => {
    const m = /<image\b([^>]*)>([^<]+)<\/image>/.exec(block || '');
    return m ? { attributes: m[1], url: m[2].trim(), size: Number(attribute(m[1], 'size')) || null } : null;
  };
  if (part === 'full') {
    const version = /<version\b[^>]*\blabel="([^"]+)"/.exec(xml)?.[1];
    const full = image(/<update_data\b[^>]*update_type="full"[^>]*>([\s\S]*?)<\/update_data>/.exec(xml)?.[1]);
    return version && full ? { version, url: full.url, size: full.size } : null;
  }
  const data = image(/<recovery\b[^>]*spkg_type="systemdata"[^>]*>([\s\S]*?)<\/recovery>/.exec(xml)?.[1]);
  const version = data && attribute(data.attributes, 'spkg_version');
  return data && version ? { version, url: data.url, size: data.size } : null;
}

const VITA_LIST = 'http://fus01.psp2.update.playstation.net/update/psp2/list/eu/psp2-updatelist.xml';

/** Sources sur Internet : nom, page d'information et dernière version ({ version, url }). */
export const SOURCES = {
  'sony-ps3': {
    name: 'PlayStation (serveur de mise à jour officiel)',
    page: 'https://www.playstation.com/support/hardware/ps3/system-software/',
    latest: async () => parsePs3UpdateList(await fetchText('http://fus01.ps3.update.playstation.net/update/ps3/list/eu/ps3-updatelist.txt')),
  },
  'sony-vita': {
    name: 'PlayStation (serveur de mise à jour officiel)',
    page: 'https://www.playstation.com/support/hardware/psvita/system-software/',
    latest: async () => parseVitaUpdateList(await fetchText(VITA_LIST), 'full'),
  },
  'sony-vita-fonts': {
    name: 'PlayStation (serveur de mise à jour officiel)',
    page: 'https://vita3k.org/quickstart.html',
    latest: async () => parseVitaUpdateList(await fetchText(VITA_LIST), 'systemdata'),
  },
  'pcsx2-gameindex': {
    name: 'GitHub libretro/ps2 (base de jeux de PCSX2, GPL)',
    page: 'https://github.com/libretro/ps2/blob/master/bin/resources/GameIndex.yaml',
    latest: async () => {
      const commits = JSON.parse(await fetchText('https://api.github.com/repos/libretro/ps2/commits?path=bin/resources/GameIndex.yaml&per_page=1'));
      const commit = Array.isArray(commits) ? commits[0] : null;
      if (!commit?.sha) return null;
      const date = String(commit.commit?.committer?.date || '').slice(0, 10);
      return { version: `${date} (${commit.sha.slice(0, 7)})`, url: `https://raw.githubusercontent.com/libretro/ps2/${commit.sha}/bin/resources/GameIndex.yaml` };
    },
  },
};

/** Fichiers supplémentaires d'un système (description dans la langue [language]). */
export function systemFiles(system, language = 'en') {
  const list = FILES[system.id] || FILES[system.shortname] || [];
  return list.map((f) => ({
    path: f.path,
    kind: f.kind,
    required: f.required,
    folder: Boolean(f.folder),
    emulators: f.emulators,
    description: f.description[language] || f.description.en,
    source: f.source || null,
  }));
}

/** Source d'un fichier attendu par une fiche libretro, ou null. */
export function coreFileSource(filePath) {
  return CORE_FILE_SOURCES[String(filePath).toLowerCase()] || null;
}

// Dernières versions, gardées 6 h (une requête par source).
const latestCache = new Map();
const LATEST_TTL = 6 * 3600_000;

/** Oublie les dernières versions connues (tests). */
export function clearLatestCache() {
  latestCache.clear();
}

/** Dernière version d'une source ({ version, url } ou null), avec le cache ; [force] : relue. */
export async function latestVersion(sourceId, { force = false } = {}) {
  const source = SOURCES[sourceId];
  if (!source) return null;
  const cached = latestCache.get(sourceId);
  if (!force && cached && Date.now() - cached.at < LATEST_TTL) return cached.value;
  try {
    const value = await source.latest();
    latestCache.set(sourceId, { at: Date.now(), value });
    return value;
  } catch {
    // Source injoignable : dernière réponse connue, nouvel essai dans 10 min.
    latestCache.set(sourceId, { at: Date.now() - LATEST_TTL + 600_000, value: cached?.value ?? null });
    return cached?.value ?? null;
  }
}
