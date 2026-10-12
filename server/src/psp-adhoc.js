// Jeu ad hoc de la PSP par Internet (PPSSPP, mode relais « AEMU postoffice ») : les consoles ne
// s'échangent rien directement, tout passe par le serveur.
//  - mise en relation (adhocctl, port 27312 de PPSSPP) : connexion au réseau ad hoc du jeu, groupes,
//    recherche des groupes, arrivées et départs des autres consoles, messages ;
//  - « bureau de poste » (postoffice, port 27313 de PPSSPP) : une connexion par socket du jeu ; les
//    paquets PDP (datagrammes) sont remis à la console et au port visés, les connexions PTP (flux)
//    sont appariées (écoute, demande, acceptation) puis leurs données passent de l'une à l'autre.
// Chaque appareil relie ces deux ports locaux au serveur par des requêtes « Upgrade » (comme le
// relais), une par connexion ; chaque partie (session de l'hôte) a son propre réseau.
// Protocoles réimplémentés d'après leur description (PRO online pour adhocctl, aemu_postoffice),
// sans en reprendre le code.

/** Réseaux ad hoc ouverts : session -> { ctl, po }. Retiré quand plus aucune console n'y est reliée. */
const rooms = new Map();

function room(session) {
  let r = rooms.get(session);
  if (!r) {
    r = { ctl: new Adhocctl(), po: new PostOffice(), sockets: new Set() };
    rooms.set(session, r);
  }
  return r;
}

/** Connexion acceptée pour la session : suivie pour retirer le réseau une fois vide. */
function track(session, socket) {
  const r = room(session);
  // Connexion « Upgrade » du serveur HTTP gardée à moitié ouverte : la fin de l'appareil la ferme.
  socket.once('end', () => socket.destroy());
  r.sockets.add(socket);
  socket.once('close', () => {
    r.sockets.delete(socket);
    if (!r.sockets.size && rooms.get(session) === r) rooms.delete(session);
  });
  return r;
}

/** Connexion de mise en relation (adhocctl) de la session. */
export function attachAdhocctl(session, socket) {
  track(session, socket).ctl.attach(socket);
}

/** Connexion du « bureau de poste » de la session. */
export function attachPostoffice(session, socket) {
  track(session, socket).po.attach(socket);
}

/** Essais : tout oublier. */
export function reset() {
  for (const r of rooms.values()) for (const s of r.sockets) s.destroy();
  rooms.clear();
}

/** Réseaux ouverts (essais). */
export const openRooms = () => rooms.size;

const macKey = (buf) => buf.subarray(0, 6).toString('hex');

/** Chaîne C d'un champ de taille fixe (jusqu'au premier octet nul). */
function cString(buf) {
  const end = buf.indexOf(0);
  return buf.subarray(0, end < 0 ? buf.length : end).toString('latin1');
}

/** Champ de taille fixe rempli de zéros. */
function fixed(value, size) {
  const out = Buffer.alloc(size);
  (Buffer.isBuffer(value) ? value : Buffer.from(String(value), 'latin1')).copy(out, 0, 0, size);
  return out;
}

/** Socket coupé si le destinataire ne lit plus (tampon d'écriture trop plein). */
const MAX_PENDING_WRITE = 4 * 1024 * 1024;

function send(socket, data) {
  if (socket.destroyed) return;
  socket.write(data);
  if (socket.writableLength > MAX_PENDING_WRITE) socket.destroy();
}

// ---------------------------------------------------------------------------
// Mise en relation (adhocctl, protocole PRO online, révision 1)
// ---------------------------------------------------------------------------

export const OP = { PING: 0, LOGIN: 1, CONNECT: 2, DISCONNECT: 3, SCAN: 4, SCAN_COMPLETE: 5, CONNECT_BSSID: 6, CHAT: 7 };
const NICK = 128;
const GROUP = 8;
const PRODUCT = 9;
const CHAT = 64;
/** Taille des paquets envoyés par la console, selon leur code. */
const C2S_SIZE = { [OP.PING]: 1, [OP.LOGIN]: 1 + 6 + NICK + PRODUCT, [OP.CONNECT]: 1 + GROUP, [OP.DISCONNECT]: 1, [OP.SCAN]: 1, [OP.CHAT]: 1 + CHAT };
/** Connexion sans identification au bout de ce délai, ou silencieuse (sans ping) : fermée. */
const LOGIN_TIMEOUT_MS = 10_000;
const IDLE_TIMEOUT_MS = 90_000;

class Adhocctl {
  constructor() {
    this.clients = new Map(); // mac -> console identifiée
    this.games = new Map(); // code du jeu -> (nom du groupe -> { name, leader, members: Map(mac -> console) })
    this.nextId = 1;
  }

  attach(socket) {
    const client = { socket, buf: Buffer.alloc(0), mac: null, key: null, nick: '', game: '', group: null, id: 0 };
    let idle;
    const touch = () => {
      clearTimeout(idle);
      idle = setTimeout(() => socket.destroy(), client.key ? IDLE_TIMEOUT_MS : LOGIN_TIMEOUT_MS);
    };
    touch();
    socket.on('data', (chunk) => {
      client.buf = client.buf.length ? Buffer.concat([client.buf, chunk]) : chunk;
      if (client.buf.length > 64 * 1024) return socket.destroy();
      touch();
      this.process(client);
    });
    socket.on('error', () => socket.destroy());
    socket.on('close', () => {
      clearTimeout(idle);
      this.remove(client);
    });
  }

  process(client) {
    while (client.buf.length && !client.socket.destroyed) {
      const op = client.buf[0];
      const size = C2S_SIZE[op];
      // Code inconnu, ou demande avant l'identification : connexion fermée.
      if (!size || (op !== OP.LOGIN && op !== OP.PING && !client.key)) return client.socket.destroy();
      if (client.buf.length < size) return;
      const packet = client.buf.subarray(0, size);
      client.buf = client.buf.subarray(size);
      if (op === OP.LOGIN) this.login(client, packet);
      else if (op === OP.CONNECT) this.join(client, cString(packet.subarray(1, 1 + GROUP)));
      else if (op === OP.DISCONNECT) this.leave(client);
      else if (op === OP.SCAN) this.scan(client);
      else if (op === OP.CHAT) this.chat(client, packet.subarray(1, 1 + CHAT));
    }
  }

  login(client, packet) {
    const mac = Buffer.from(packet.subarray(1, 7));
    const game = cString(packet.subarray(7 + NICK, 7 + NICK + PRODUCT));
    // Adresse MAC vide ou code de jeu invalide (ex. ULES00123) : refusée.
    if (mac.subarray(1).every((b) => b === 0) || !/^[A-Z0-9]{9}$/.test(game)) return client.socket.destroy();
    const key = macKey(mac);
    // Même console reconnectée : l'ancienne connexion laisse la place.
    const previous = this.clients.get(key);
    if (previous && previous !== client) previous.socket.destroy();
    if (client.key && client.key !== key) this.remove(client);
    client.mac = mac;
    client.key = key;
    client.nick = cString(packet.subarray(7, 7 + NICK));
    client.game = game;
    if (!client.id) client.id = this.newId();
    this.clients.set(key, client);
  }

  /** Identifiant de la console (à la place de son adresse IP : rien ne passe directement). */
  newId() {
    const used = new Set([...this.clients.values()].map((c) => c.id));
    while (used.has(this.nextId) || this.nextId === 0) this.nextId = (this.nextId + 1) >>> 0;
    // 10.x.x.x : identifiant présenté comme une adresse privée.
    return (0x0a000000 + this.nextId++) >>> 0;
  }

  groupsOf(game) {
    let groups = this.games.get(game);
    if (!groups) {
      groups = new Map();
      this.games.set(game, groups);
    }
    return groups;
  }

  join(client, name) {
    this.leave(client);
    const groups = this.groupsOf(client.game);
    let group = groups.get(name);
    if (!group) {
      group = { name, leader: client.mac, members: new Map() };
      groups.set(name, group);
    }
    // Chacun apprend l'arrivée de l'autre ; le nouveau venu reçoit l'adresse du groupe (son créateur).
    for (const member of group.members.values()) {
      send(member.socket, connectPacket(client));
      send(client.socket, connectPacket(member));
    }
    send(client.socket, Buffer.concat([Buffer.from([OP.CONNECT_BSSID]), group.leader]));
    group.members.set(client.key, client);
    client.group = name;
  }

  leave(client) {
    if (client.group === null) return;
    const groups = this.games.get(client.game);
    const group = groups?.get(client.group);
    client.group = null;
    if (!group || group.members.get(client.key) !== client) return;
    group.members.delete(client.key);
    const packet = Buffer.alloc(5);
    packet[0] = OP.DISCONNECT;
    packet.writeUInt32LE(client.id, 1);
    for (const member of group.members.values()) send(member.socket, packet);
    if (!group.members.size) groups.delete(group.name);
    if (!groups.size) this.games.delete(client.game);
  }

  scan(client) {
    for (const group of this.games.get(client.game)?.values() || []) {
      send(client.socket, Buffer.concat([Buffer.from([OP.SCAN]), fixed(group.name, GROUP), group.leader]));
    }
    send(client.socket, Buffer.from([OP.SCAN_COMPLETE]));
  }

  chat(client, message) {
    const group = client.group === null ? null : this.games.get(client.game)?.get(client.group);
    if (!group) return;
    const packet = Buffer.concat([Buffer.from([OP.CHAT]), fixed(cString(message), CHAT), fixed(client.nick, NICK)]);
    for (const member of group.members.values()) if (member !== client) send(member.socket, packet);
  }

  remove(client) {
    this.leave(client);
    if (client.key && this.clients.get(client.key) === client) this.clients.delete(client.key);
  }
}

/** Arrivée d'une console : surnom, adresse MAC, identifiant. */
function connectPacket(client) {
  const id = Buffer.alloc(4);
  id.writeUInt32LE(client.id);
  return Buffer.concat([Buffer.from([OP.CONNECT]), fixed(client.nick, NICK - 1), Buffer.alloc(1), client.mac, id]);
}

// ---------------------------------------------------------------------------
// « Bureau de poste » (aemu_postoffice)
// ---------------------------------------------------------------------------

export const INIT = { PDP: 0, PTP_LISTEN: 1, PTP_CONNECT: 2, PTP_ACCEPT: 3 };
/** Demande d'ouverture : type (int32), adresse et port source, adresse et port visés (adresses sur 8 octets). */
const INIT_SIZE = 24;
const PDP_HEADER = 14;
const PDP_MAX = 2 * 10 * 1024;
const PTP_MAX = 2 * 50 * 1024;
/** Demande PTP vers un port qui n'écoute pas encore : réessayée pendant 2 s ; acceptation attendue 20 s. */
const CONNECT_RETRIES = 8;
const CONNECT_RETRY_MS = 250;
const ACCEPT_TIMEOUT_MS = 20_000;

class PostOffice {
  constructor() {
    this.sessions = new Map(); // nom -> connexion (« PDP mac port », « PTP_LISTEN mac port »…)
  }

  attach(socket) {
    const s = { socket, buf: Buffer.alloc(0), name: null, mode: null, peer: null, waiting: false, size: -1 };
    const initTimeout = setTimeout(() => socket.destroy(), LOGIN_TIMEOUT_MS);
    socket.on('data', (chunk) => {
      s.buf = s.buf.length ? Buffer.concat([s.buf, chunk]) : chunk;
      if (s.buf.length > 4 * PTP_MAX) return socket.destroy();
      if (s.mode === null) {
        if (s.buf.length < INIT_SIZE) return;
        clearTimeout(initTimeout);
        const init = s.buf.subarray(0, INIT_SIZE);
        s.buf = s.buf.subarray(INIT_SIZE);
        this.open(s, init, 0);
        return;
      }
      this.pump(s);
    });
    socket.on('error', () => socket.destroy());
    socket.on('close', () => {
      clearTimeout(initTimeout);
      clearTimeout(s.timer);
      if (s.name && this.sessions.get(s.name) === s) this.sessions.delete(s.name);
      s.peer?.socket.destroy();
    });
  }

  /** Enregistre la connexion sous [name] (une connexion déjà là sous ce nom est fermée). */
  register(s, name) {
    const previous = this.sessions.get(name);
    if (previous && previous !== s) previous.socket.destroy();
    s.name = name;
    this.sessions.set(name, s);
  }

  open(s, init, retries) {
    if (s.socket.destroyed) return;
    const type = init.readInt32LE(0);
    const src = Buffer.from(init.subarray(4, 12));
    const sport = init.readUInt16LE(12);
    const dst = Buffer.from(init.subarray(14, 22));
    const dport = init.readUInt16LE(22);
    const me = macKey(src);
    const other = macKey(dst);
    s.src = src;
    s.sport = sport;
    if (type === INIT.PDP) {
      s.mode = INIT.PDP;
      this.register(s, `PDP ${me} ${sport}`);
      this.pump(s);
    } else if (type === INIT.PTP_LISTEN) {
      s.mode = INIT.PTP_LISTEN;
      this.register(s, `PTP_LISTEN ${me} ${sport}`);
    } else if (type === INIT.PTP_CONNECT) {
      const listener = this.sessions.get(`PTP_LISTEN ${other} ${dport}`);
      if (!listener) {
        // Écoute pas encore ouverte de l'autre côté : nouvel essai un peu plus tard.
        if (retries >= CONNECT_RETRIES) return s.socket.destroy();
        s.timer = setTimeout(() => this.open(s, init, retries + 1), CONNECT_RETRY_MS);
        return;
      }
      s.mode = INIT.PTP_CONNECT;
      s.waiting = true;
      this.register(s, `PTP_CONNECT ${me} ${sport} ${other} ${dport}`);
      // L'écoute apprend la demande (adresse et port du demandeur) ; acceptation attendue.
      send(listener.socket, addressPacket(src, sport));
      s.timer = setTimeout(() => {
        if (s.waiting) s.socket.destroy();
      }, ACCEPT_TIMEOUT_MS);
    } else if (type === INIT.PTP_ACCEPT) {
      // Acceptation par l'écoute [src]:[sport] de la demande de [dst]:[dport].
      const connector = this.sessions.get(`PTP_CONNECT ${other} ${dport} ${me} ${sport}`);
      if (!connector || !connector.waiting) return s.socket.destroy();
      s.mode = INIT.PTP_ACCEPT;
      this.register(s, `PTP_ACCEPT ${me} ${sport} ${other} ${dport}`);
      clearTimeout(connector.timer);
      connector.waiting = false;
      connector.peer = s;
      s.peer = connector;
      send(connector.socket, addressPacket(src, sport));
      send(s.socket, addressPacket(dst, dport));
      this.pump(connector);
      this.pump(s);
    } else {
      s.socket.destroy();
    }
  }

  /** Paquets reçus de la connexion, remis à leur destinataire. */
  pump(s) {
    if (s.mode === INIT.PDP) {
      while (s.buf.length >= PDP_HEADER) {
        const size = s.buf.readUInt32LE(10);
        if (size > PDP_MAX) return s.socket.destroy();
        if (s.buf.length < PDP_HEADER + size) return;
        const to = `PDP ${macKey(s.buf.subarray(0, 8))} ${s.buf.readUInt16LE(8)}`;
        const data = s.buf.subarray(PDP_HEADER, PDP_HEADER + size);
        s.buf = s.buf.subarray(PDP_HEADER + size);
        const target = this.sessions.get(to);
        // Destinataire absent : datagramme perdu, comme sur un vrai réseau.
        if (target) {
          const header = Buffer.alloc(PDP_HEADER);
          s.src.copy(header, 0, 0, 8);
          header.writeUInt16LE(s.sport, 8);
          header.writeUInt32LE(size, 10);
          send(target.socket, Buffer.concat([header, data]));
        }
      }
    } else if ((s.mode === INIT.PTP_CONNECT || s.mode === INIT.PTP_ACCEPT) && s.peer) {
      while (s.buf.length >= 4) {
        const size = s.buf.readUInt32LE(0);
        if (size > PTP_MAX) return s.socket.destroy();
        if (s.buf.length < 4 + size) return;
        const block = s.buf.subarray(0, 4 + size);
        s.buf = s.buf.subarray(4 + size);
        send(s.peer.socket, Buffer.from(block));
      }
    }
  }
}

/** Adresse (8 octets) et port d'une console : demande de connexion PTP, ou connexion acceptée. */
function addressPacket(addr, port) {
  const out = Buffer.alloc(10);
  addr.copy(out, 0, 0, 8);
  out.writeUInt16LE(port, 8);
  return out;
}
