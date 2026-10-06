// Connexion au serveur RomCloud : hors ligne dès qu'une requête n'aboutit pas, de nouveau en ligne
// à la première réponse (quel que soit son code). Hors ligne, le serveur est sondé régulièrement ;
// au retour de la connexion, les abonnés sont prévenus (envoi des sauvegardes en attente, interface).
const settings = require('./settings');

const PROBE_INTERVAL = 15000;

let online = true;
let timer = null;
const listeners = [];

const isOnline = () => online;

/** Fonction appelée à chaque changement : (enLigne). */
function onChange(fn) {
  listeners.push(fn);
}

function set(value) {
  if (value === online) return;
  online = value;
  clearInterval(timer);
  timer = online ? null : setInterval(probe, PROBE_INTERVAL);
  for (const fn of listeners) {
    try {
      fn(online);
    } catch (err) {
      console.error(err);
    }
  }
}

const markOnline = () => set(true);
const markOffline = () => set(false);

/** Le serveur répond-il ? Met l'état à jour et le renvoie (sans serveur configuré : inchangé). */
async function probe() {
  const { serverUrl } = settings.load();
  if (!serverUrl) return online;
  try {
    await fetch(`${serverUrl}/api/info`, { signal: AbortSignal.timeout(5000) });
    markOnline();
  } catch {
    markOffline();
  }
  return online;
}

module.exports = { isOnline, onChange, markOnline, markOffline, probe };
