// Lobbies du multijoueur : un joueur connecté à un profil crée un lobby (il en est l'hôte), les autres
// le rejoignent comme invités. L'hôte choisit le jeu et la façon de jouer en réseau (netplay ou
// liaison entre consoles), lance la partie, puis chacun la rejoint avec le mécanisme habituel (réseau
// local ou relais du serveur, voir play.js). Gardés en mémoire, comme la présence : un membre dont
// l'appareil ne s'annonce plus est retiré, et le lobby disparaît avec son hôte.
import crypto from 'node:crypto';
import { HttpError } from './http-error.js';
import { players, presenceOf } from './play.js';

const MODES = ['netplay', 'link'];
const STATUSES = ['waiting', 'playing'];
/** Lobbies ouverts au plus (protection). */
const MAX_LOBBIES = 100;
/**
 * Membre dont l'appareil ne s'annonce plus : gardé ce délai avant d'être retiré (passage de
 * l'application au jeu sous Android, coupure du Wi-Fi…).
 */
export const LOBBY_GRACE_MS = 90_000;

const lobbies = new Map(); // identifiant -> lobby

const text = (v, max) => (v === undefined || v === null ? '' : String(v).slice(0, max));

/** Appareil connecté de ce profil (annoncé par la présence), sinon refus. */
function device(auth, deviceId) {
  const id = text(deviceId, 64);
  const p = presenceOf(id);
  if (!p) throw new HttpError(409, 'errors.playNotPresent');
  if (p.userId !== auth.user.id) throw new HttpError(403, 'errors.playDevice');
  return { id, ...p };
}

/** Jeu du lobby (null : pas encore choisi) ; players : nombre de joueurs maximal du jeu. */
function gameOf(g) {
  if (!g || typeof g !== 'object') return null;
  return {
    gameId: Number(g.gameId) || 0,
    systemId: text(g.systemId, 60),
    title: text(g.title, 200),
    fileName: text(g.fileName, 300),
    size: Number(g.size) || 0,
    players: Math.max(0, Math.min(16, Number(g.players) || 0)),
  };
}

const modesOf = (list) => [...new Set((Array.isArray(list) ? list : []).filter((m) => MODES.includes(m)))];

/**
 * Systèmes que l'appareil sait jouer en réseau avec un émulateur intégré (moteur de Windows,
 * LibretroDroid) : liste envoyée en créant ou en rejoignant un lobby ; null : non précisée (version
 * précédente de l'application, aucune restriction).
 */
const systemsOf = (list) => (Array.isArray(list) ? [...new Set(list.map((s) => text(s, 60)).filter(Boolean))] : null);

/** Systèmes jouables par tous les membres (null : aucune restriction connue). */
function commonSystems(lobby) {
  let common = null;
  for (const m of lobby.members) {
    if (!m.systems) continue;
    common = common ? common.filter((s) => m.systems.includes(s)) : [...m.systems];
  }
  return common;
}

/**
 * Membres dont l'appareil ne s'annonce plus depuis [LOBBY_GRACE_MS] : retirés ; lobby sans son hôte :
 * dissous.
 */
function prune(now = Date.now()) {
  for (const [id, lobby] of lobbies) {
    lobby.members = lobby.members.filter((m) => {
      if (presenceOf(m.id)) m.seenAt = now;
      return now - m.seenAt <= LOBBY_GRACE_MS;
    });
    if (!lobby.members.some((m) => m.id === lobby.host)) lobbies.delete(id);
  }
}

function view(lobby) {
  return {
    id: lobby.id,
    host: lobby.host,
    game: lobby.game,
    mode: lobby.mode,
    modes: lobby.modes,
    started: lobby.started,
    createdAt: lobby.createdAt,
    members: lobby.members.map((m) => ({ id: m.id, name: m.name, user: m.user, platform: m.platform, status: m.status, host: m.id === lobby.host })),
    // Jeux possibles : seulement ces systèmes (émulateur intégré disponible chez tous les membres).
    systems: commonSystems(lobby),
  };
}

function requireLobby(id) {
  prune();
  const lobby = lobbies.get(String(id));
  if (!lobby) throw new HttpError(404, 'errors.lobbyNotFound');
  return lobby;
}

function requireHost(lobby, dev) {
  if (lobby.host !== dev.id) throw new HttpError(403, 'errors.lobbyHostOnly');
}

/** Quitte tous les lobbies (un appareil n'est que dans un lobby) ; celui qu'il animait est dissous. */
function leaveAll(deviceId) {
  for (const [id, lobby] of lobbies) {
    if (lobby.host === deviceId) lobbies.delete(id);
    else lobby.members = lobby.members.filter((m) => m.id !== deviceId);
  }
}

const member = (dev, systems) => ({ id: dev.id, name: dev.name, user: dev.user, platform: dev.platform, status: 'waiting', seenAt: Date.now(), systems });

/** Joueurs connectés (pastilles) et lobbies ouverts ; [now] : essais. */
export function list(now = Date.now()) {
  prune(now);
  return { players: players(), lobbies: [...lobbies.values()].sort((a, b) => a.createdAt - b.createdAt).map(view) };
}

/** Nouveau lobby, vierge ou avec un jeu ([body.game], [body.modes]) : le créateur en est l'hôte. */
export function create(auth, body = {}) {
  const dev = device(auth, body.deviceId);
  prune();
  leaveAll(dev.id);
  if (lobbies.size >= MAX_LOBBIES) throw new HttpError(429, 'errors.lobbyLimit');
  const modes = modesOf(body.modes);
  const systems = systemsOf(body.systems);
  const game = gameOf(body.game);
  if (game && systems && !systems.includes(game.systemId)) throw new HttpError(400, 'errors.lobbyGameUnavailable');
  const lobby = {
    id: crypto.randomBytes(6).toString('hex'),
    host: dev.id,
    game,
    modes,
    mode: modes.includes(body.mode) ? body.mode : modes[0] ?? null,
    started: false,
    createdAt: Date.now(),
    members: [member(dev, systems)],
  };
  lobbies.set(lobby.id, lobby);
  return view(lobby);
}

/** Rejoint le lobby comme invité (quitte celui où l'appareil était). */
export function join(auth, id, body = {}) {
  const dev = device(auth, body.deviceId);
  const lobby = requireLobby(id);
  const systems = systemsOf(body.systems);
  const self = lobby.members.find((m) => m.id === dev.id);
  if (self) {
    if (systems) self.systems = systems;
  } else {
    // Jeu du lobby sans émulateur intégré sur cet appareil : il ne pourrait pas jouer.
    if (lobby.game && systems && !systems.includes(lobby.game.systemId)) throw new HttpError(409, 'errors.lobbyGameUnsupported');
    leaveAll(dev.id);
    lobby.members.push(member(dev, systems));
  }
  return view(lobby);
}

/** Quitte le lobby ; l'hôte qui part le dissout. */
export function leave(auth, id, body = {}) {
  const dev = device(auth, body.deviceId);
  const lobby = requireLobby(id);
  if (lobby.host === dev.id) lobbies.delete(lobby.id);
  else lobby.members = lobby.members.filter((m) => m.id !== dev.id);
}

/**
 * Hôte seulement : jeu ([body.game], null : aucun) et façons de jouer possibles ([body.modes]), ou
 * seulement la façon choisie ([body.mode]). Nouveau jeu : partie à relancer, tous en attente.
 */
export function update(auth, id, body = {}) {
  const dev = device(auth, body.deviceId);
  const lobby = requireLobby(id);
  requireHost(lobby, dev);
  if ('game' in body) {
    const game = gameOf(body.game);
    const systems = commonSystems(lobby);
    if (game && systems && !systems.includes(game.systemId)) throw new HttpError(400, 'errors.lobbyGameUnavailable');
    lobby.game = game;
    lobby.modes = modesOf(body.modes);
    lobby.mode = lobby.modes.includes(body.mode) ? body.mode : lobby.modes[0] ?? null;
    lobby.started = false;
    for (const m of lobby.members) m.status = 'waiting';
  } else if (body.mode !== undefined) {
    if (!lobby.modes.includes(body.mode)) throw new HttpError(400, 'errors.lobbyMode');
    lobby.mode = body.mode;
  }
  return view(lobby);
}

/**
 * Statut du membre : « waiting » (en attente) ou « playing » (en jeu). Celui de l'hôte dit si la
 * partie est lancée (les invités peuvent alors la rejoindre).
 */
export function setStatus(auth, id, body = {}) {
  const dev = device(auth, body.deviceId);
  const lobby = requireLobby(id);
  const self = lobby.members.find((m) => m.id === dev.id);
  if (!self) throw new HttpError(404, 'errors.lobbyNotFound');
  if (!STATUSES.includes(body.status)) throw new HttpError(400, 'errors.lobbyStatus');
  if (body.status === 'playing' && !lobby.game) throw new HttpError(400, 'errors.lobbyNoGame');
  self.status = body.status;
  if (lobby.host === dev.id) lobby.started = body.status === 'playing';
  return view(lobby);
}

/** Hôte seulement : supprime le lobby (les membres retournent à l'écran d'accueil). */
export function dissolve(auth, id, deviceId) {
  const dev = device(auth, deviceId);
  const lobby = requireLobby(id);
  requireHost(lobby, dev);
  lobbies.delete(lobby.id);
}

/** Essais : tout oublier. */
export function reset() {
  lobbies.clear();
}
