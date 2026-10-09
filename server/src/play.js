// Jeu à plusieurs par Internet entre les joueurs connectés à un profil (même serveur) :
//  - présence : chaque appareil connecté (application ou jeu ouvert) s'annonce toutes les 10 s, avec
//    la partie qu'il propose ; il reçoit en retour les autres appareils connectés, tous profils
//    confondus ;
//  - relais : les deux appareils ouvrent une connexion vers le serveur (requête HTTP « Upgrade »,
//    chemin /api/play/relay), qui fait passer les octets de l'une à l'autre. Rien à ouvrir sur les
//    box : les deux connexions partent des appareils. L'hôte attend un invité sur une connexion
//    ouverte d'avance (1 octet envoyé quand un invité arrive) ; l'invité n'obtient la sienne que si
//    un hôte attend. Même échange ensuite qu'en réseau local (demande, réponse, touches ou paquets).
import crypto from 'node:crypto';
import { HttpError } from './http-error.js';

export const PRESENCE_TTL_MS = 30_000;
/** Connexions d'hôte en attente d'un invité, au plus, par partie et canal. */
const MAX_WAITING = 4;

const presence = new Map(); // identifiant de l'appareil -> annonce

const text = (v, max) => (v === undefined || v === null ? '' : String(v).slice(0, max));
const SESSION_RE = /^[0-9a-f]{16,64}$/;
const CHANNEL_RE = /^[a-z]{1,16}$/;

function prune(now = Date.now()) {
  for (const [id, p] of presence) if (p.seenAt + PRESENCE_TTL_MS < now) presence.delete(id);
}

/** Partie proposée (même contenu qu'en réseau local, plus l'identifiant de la session de relais). */
function hostedGame(h) {
  if (!h || typeof h !== 'object') return null;
  const session = text(h.session, 64).toLowerCase();
  if (!SESSION_RE.test(session)) throw new HttpError(400, 'errors.playHosting');
  return {
    gameId: Number(h.gameId) || 0,
    systemId: text(h.systemId, 60),
    title: text(h.title, 200),
    fileName: text(h.fileName, 300),
    size: Number(h.size) || 0,
    core: text(h.core, 60),
    link: h.link ? text(h.link, 16) : null,
    session,
  };
}

/**
 * L'appareil s'annonce (identifiant, nom, plateforme, partie proposée, « en partie », aller-retour
 * mesuré jusqu'au serveur) ; renvoie les autres appareils connectés.
 */
export function announce(auth, body, client) {
  const id = text(body?.deviceId, 64);
  if (!/^[\w-]{8,64}$/.test(id)) throw new HttpError(400, 'errors.playDevice');
  prune();
  presence.set(id, {
    userId: auth.user.id,
    user: auth.user.username,
    name: text(body.name, 100) || client.device || 'RomCloud',
    platform: text(body.platform || client.platform, 30),
    hosting: hostedGame(body.hosting),
    busy: body.busy === true,
    rtt: Math.max(0, Math.min(5000, Number(body.rtt) || 0)),
    seenAt: Date.now(),
  });
  return { ttlSeconds: PRESENCE_TTL_MS / 1000, peers: peers(id) };
}

/** L'appareil quitte (application fermée, déconnexion). */
export function withdraw(auth, deviceId) {
  const p = presence.get(String(deviceId));
  if (p && p.userId === auth.user.id) presence.delete(String(deviceId));
}

/** Autres appareils connectés : { id, name, platform, user, hosting, busy, rtt }. */
export function peers(exceptId) {
  prune();
  return [...presence]
    .filter(([id]) => id !== exceptId)
    .map(([id, p]) => ({ id, name: p.name, platform: p.platform, user: p.user, hosting: p.hosting, busy: p.busy, rtt: p.rtt }));
}

/** Profil qui propose cette session (hôte), ou null. */
function sessionOwner(session) {
  prune();
  for (const p of presence.values()) if (p.hosting?.session === session) return p.userId;
  return null;
}

// ---------------------------------------------------------------------------
// Relais
// ---------------------------------------------------------------------------

const waiting = new Map(); // session|canal -> sockets d'hôte en attente

function refuse(socket, status, reason) {
  socket.end(`HTTP/1.1 ${status} ${reason}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
}

/**
 * Réponse 101. Requête WebSocket (Sec-WebSocket-Key) : réponse WebSocket valide, pour les proxys
 * inverses qui ne transmettent que ces montées (Cloudflare…) ; les octets passent ensuite tels quels.
 */
function accept(socket, key) {
  const head = key
    ? `Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${crypto.createHash('sha1').update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`).digest('base64')}\r\n`
    : 'Upgrade: romcloud-relay\r\nConnection: Upgrade\r\n';
  socket.write(`HTTP/1.1 101 Switching Protocols\r\n${head}\r\n`);
  socket.setNoDelay(true);
  socket.setKeepAlive(true, 20_000);
}

function waitingList(key) {
  const list = (waiting.get(key) || []).filter((s) => !s.destroyed);
  waiting.set(key, list);
  return list;
}

/** Relie deux connexions : chaque octet reçu de l'une est envoyé à l'autre. */
function join(host, guest) {
  host.write(Buffer.from([1]));  // un invité est arrivé
  host.pipe(guest);
  guest.pipe(host);
  const close = () => {
    host.destroy();
    guest.destroy();
  };
  host.on('close', close);
  guest.on('close', close);
  host.on('error', close);
  guest.on('error', close);
}

/**
 * Requête « Upgrade » du serveur HTTP. [authorize] (req) : profil connecté { user } ou null (clé
 * d'API et session vérifiées comme pour /api). Renvoie faux si la requête n'est pas pour le relais.
 */
export function handleUpgrade(req, socket, authorize) {
  const url = new URL(req.url, 'http://relay');
  if (url.pathname !== '/api/play/relay') return false;
  socket.on('error', () => socket.destroy());
  const auth = authorize(req);
  if (!auth) return refuse(socket, 401, 'Unauthorized'), true;
  const session = String(url.searchParams.get('session') || '').toLowerCase();
  const channel = String(url.searchParams.get('channel') || 'play');
  const role = url.searchParams.get('role');
  if (!SESSION_RE.test(session) || !CHANNEL_RE.test(channel) || (role !== 'host' && role !== 'guest')) {
    return refuse(socket, 400, 'Bad Request'), true;
  }
  const key = `${session}|${channel}`;
  const wsKey = req.headers['sec-websocket-key'] || null;
  const list = waitingList(key);
  if (role === 'host') {
    // Seul le profil qui propose la partie attend ses invités.
    if (sessionOwner(session) !== auth.user.id) return refuse(socket, 403, 'Forbidden'), true;
    if (list.length >= MAX_WAITING) return refuse(socket, 429, 'Too Many Requests'), true;
    accept(socket, wsKey);
    // En attente, la connexion est lue (rien n'est attendu) : sa fermeture par l'hôte est vue tout
    // de suite, sans quoi un invité pourrait être relié à une connexion morte.
    socket.resume();
    socket.once('end', () => socket.destroy());
    list.push(socket);
    socket.on('close', () => waitingList(key));
    return true;
  }
  const host = list.shift();
  if (!host) return refuse(socket, 404, 'Not Found'), true;
  accept(socket, wsKey);
  join(host, socket);
  return true;
}

/** Identifiant de session de relais (hôte). */
export const newSession = () => crypto.randomBytes(16).toString('hex');

/** Essais : tout oublier. */
export function reset() {
  presence.clear();
  for (const list of waiting.values()) for (const s of list) s.destroy();
  waiting.clear();
}
