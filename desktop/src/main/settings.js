// Réglages de l'application (fichier JSON dans le dossier de données de l'utilisateur).
const fs = require('node:fs');
const path = require('node:path');
const { app } = require('electron');

const LANGUAGES = ['fr', 'en'];

function defaults() {
  return {
    serverUrl: '',
    apiKey: '',
    romsDir: path.join(app.getPath('documents'), 'RomCloud'),
    retroarchPath: '',
    biosDir: '', // vide : dossier « system » de RetroArch, sinon Documents\RomCloud\bios
    language: 'system', // system | fr | en
    view: 'carousel', // list | carousel
    emulators: {}, // { [systemId]: { option: 'retroarch:<core>' | 'emu:<id>' | 'custom' | 'default', command: '' } }
    emulatorPaths: {}, // { [id émulateur du catalogue]: chemin de l'exécutable }
    emulatorArgs: {}, // { [id]: arguments personnalisés, vide = ceux du catalogue }
    emulatorsDetectedAt: '', // date de la dernière recherche des émulateurs sur le PC
    retroarchHelpDismissed: false,
  };
}

const file = () => path.join(app.getPath('userData'), 'settings.json');
let current = null;

function load() {
  if (current) return current;
  try {
    current = { ...defaults(), ...JSON.parse(fs.readFileSync(file(), 'utf8')) };
  } catch {
    current = defaults();
  }
  return current;
}

function save(patch) {
  const next = { ...load(), ...patch };
  if (patch.serverUrl !== undefined) next.serverUrl = normalizeUrl(patch.serverUrl);
  if (patch.romsDir !== undefined) next.romsDir = patch.romsDir.trim() || defaults().romsDir;
  if (patch.biosDir !== undefined) next.biosDir = patch.biosDir.trim();
  fs.mkdirSync(path.dirname(file()), { recursive: true });
  fs.writeFileSync(file(), JSON.stringify(next, null, 2));
  current = next;
  return current;
}

/** Dossier des BIOS : choisi, sinon le dossier « system » de RetroArch, sinon Documents\RomCloud\bios. */
function biosDir() {
  const s = load();
  if (s.biosDir) return s.biosDir;
  if (s.retroarchPath) return path.join(path.dirname(s.retroarchPath), 'system');
  return path.join(app.getPath('documents'), 'RomCloud', 'bios');
}

/** "192.168.1.10:8080" -> "http://192.168.1.10:8080" */
function normalizeUrl(input) {
  const trimmed = String(input || '').trim().replace(/\/+$/, '');
  if (!trimmed) return '';
  return /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
}

/** Langue effective (« fr » ou « en ») : choix de l'utilisateur ou langue de Windows. */
function language() {
  const chosen = load().language;
  if (LANGUAGES.includes(chosen)) return chosen;
  const system = (app.getLocale() || 'en').slice(0, 2).toLowerCase();
  return LANGUAGES.includes(system) ? system : 'en';
}

module.exports = { load, save, normalizeUrl, language, biosDir, LANGUAGES };
