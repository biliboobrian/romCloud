// Chromecast : découverte sur le réseau local (mDNS) et protocole Cast v2 (TLS + protobuf),
// sans dépendance. Le flux vidéo lui-même est servi par screencast.js.
const dgram = require('node:dgram');
const tls = require('node:tls');
const os = require('node:os');
const { EventEmitter } = require('node:events');

// ---------------------------------------------------------------------------
// Découverte (mDNS, service _googlecast._tcp)
// ---------------------------------------------------------------------------

const MDNS_ADDRESS = '224.0.0.251';
const MDNS_PORT = 5353;
const SERVICE = '_googlecast._tcp.local';
const TYPE = { A: 1, PTR: 12, TXT: 16, SRV: 33 };

/** Requête DNS (une question PTR). */
function encodeQuery(name, type = TYPE.PTR) {
  const labels = name.split('.').filter(Boolean).map((l) => {
    const b = Buffer.from(l, 'utf8');
    return Buffer.concat([Buffer.from([b.length]), b]);
  });
  const header = Buffer.alloc(12);
  header.writeUInt16BE(1, 4); // une question
  const tail = Buffer.alloc(5);
  tail.writeUInt16BE(type, 1);
  tail.writeUInt16BE(1, 3); // classe IN
  return Buffer.concat([header, ...labels, tail]);
}

/** Nom DNS à [offset], pointeurs de compression compris ; renvoie { name, end }. */
function readName(buf, offset) {
  const labels = [];
  let end = -1;
  for (let jumps = 0; ; ) {
    if (offset >= buf.length) throw new Error('nom DNS tronqué');
    const len = buf[offset];
    if (len === 0) {
      offset += 1;
      break;
    }
    if ((len & 0xc0) === 0xc0) {
      if (end < 0) end = offset + 2;
      if (++jumps > 32) throw new Error('boucle de compression DNS');
      offset = ((len & 0x3f) << 8) | buf[offset + 1];
      continue;
    }
    labels.push(buf.toString('utf8', offset + 1, offset + 1 + len));
    offset += 1 + len;
  }
  return { name: labels.join('.'), end: end < 0 ? offset : end };
}

/** Enregistrements (réponses et additionnels) d'un paquet DNS : { name, type, data }. */
function parseDns(buf) {
  const count = (i) => buf.readUInt16BE(i);
  let offset = 12;
  for (let q = count(4); q > 0; q--) offset = readName(buf, offset).end + 4;
  const records = [];
  const total = count(6) + count(8) + count(10);
  for (let i = 0; i < total; i++) {
    const { name, end } = readName(buf, offset);
    const type = buf.readUInt16BE(end);
    const length = buf.readUInt16BE(end + 8);
    const start = end + 10;
    if (start + length > buf.length) break;
    let data = null;
    if (type === TYPE.PTR) data = readName(buf, start).name;
    else if (type === TYPE.SRV) data = { port: buf.readUInt16BE(start + 4), target: readName(buf, start + 6).name };
    else if (type === TYPE.A && length === 4) data = [...buf.subarray(start, start + 4)].join('.');
    else if (type === TYPE.TXT) {
      data = {};
      for (let o = start; o < start + length; ) {
        const entry = buf.toString('utf8', o + 1, o + 1 + buf[o]);
        o += 1 + buf[o];
        const eq = entry.indexOf('=');
        if (eq > 0) data[entry.slice(0, eq).toLowerCase()] = entry.slice(eq + 1);
      }
    }
    if (data !== null) records.push({ name: name.toLowerCase(), type, data });
    offset = start + length;
  }
  return records;
}

/**
 * Regroupe les enregistrements reçus en appareils Cast capables d'afficher de la vidéo
 * (enceintes et groupes d'enceintes exclus). [sources] : instance → adresse de l'expéditeur,
 * utilisée si l'adresse IP n'a pas été annoncée.
 */
function collectDevices(records, sources = new Map()) {
  const instances = new Set();
  const srv = new Map();
  const txt = new Map();
  const hosts = new Map();
  for (const r of records) {
    if (r.type === TYPE.PTR && r.name === SERVICE) instances.add(r.data.toLowerCase());
    else if (r.type === TYPE.SRV) {
      srv.set(r.name, r.data);
      if (r.name.endsWith(`.${SERVICE}`)) instances.add(r.name);
    } else if (r.type === TYPE.TXT) txt.set(r.name, r.data);
    else if (r.type === TYPE.A) hosts.set(r.name, r.data);
  }
  const devices = new Map();
  for (const instance of instances) {
    const s = srv.get(instance);
    const info = txt.get(instance) || {};
    const host = (s && hosts.get(s.target.toLowerCase())) || sources.get(instance);
    if (!host) continue;
    // Bit 0 de « ca » : sortie vidéo.
    if (info.ca !== undefined && !(Number(info.ca) & 1)) continue;
    if (/cast group/i.test(info.md || '')) continue;
    const id = info.id || instance;
    devices.set(id, {
      id,
      name: info.fn || instance.replace(`.${SERVICE}`, ''),
      model: info.md || '',
      host,
      port: s?.port || 8009,
    });
  }
  return [...devices.values()];
}

function ipv4Addresses() {
  return Object.values(os.networkInterfaces()).flat()
    .filter((a) => a && (a.family === 'IPv4' || a.family === 4) && !a.internal)
    .map((a) => a.address);
}

/** Cherche les Chromecast du réseau local pendant [timeout] ms. */
function discover({ timeout = 3000 } = {}) {
  return new Promise((resolve) => {
    const records = [];
    const sources = new Map();
    const sockets = [];
    const query = encodeQuery(SERVICE);
    const onMessage = (msg, rinfo) => {
      try {
        const found = parseDns(msg);
        records.push(...found);
        for (const r of found) {
          if (r.type === TYPE.SRV || r.type === TYPE.TXT) sources.set(r.name, rinfo.address);
          if (r.type === TYPE.PTR && r.name === SERVICE) sources.set(r.data.toLowerCase(), rinfo.address);
        }
      } catch {
        // paquet illisible : ignoré
      }
    };
    const open = (bindOptions, setup) => {
      const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
      socket.on('error', () => {});
      socket.on('message', onMessage);
      socket.bind(bindOptions, () => {
        try {
          setup(socket);
        } catch {
          // interface indisponible : les autres sockets suffisent
        }
      });
      sockets.push(socket);
    };
    const send = (socket) => socket.send(query, MDNS_PORT, MDNS_ADDRESS, () => {});
    // Port 5353 partagé : réponses envoyées au groupe multicast.
    open({ port: MDNS_PORT }, (socket) => {
      for (const address of ipv4Addresses()) {
        try {
          socket.addMembership(MDNS_ADDRESS, address);
        } catch {
          // déjà membre, ou interface sans multicast
        }
      }
    });
    // Un port libre par interface : réponses directes (« legacy unicast »), requête envoyée sur chaque réseau.
    for (const address of ipv4Addresses()) {
      open({ port: 0, address }, (socket) => {
        socket.setMulticastInterface(address);
        send(socket);
        setTimeout(() => send(socket), 1000);
      });
    }
    setTimeout(() => {
      for (const socket of sockets) {
        try {
          socket.close();
        } catch {
          // déjà fermé
        }
      }
      resolve(collectDevices(records, sources).sort((a, b) => a.name.localeCompare(b.name)));
    }, timeout);
  });
}

// ---------------------------------------------------------------------------
// Messages Cast v2 : protobuf CastMessage précédé de sa longueur (32 bits)
// ---------------------------------------------------------------------------

function varint(n) {
  const bytes = [];
  while (n > 0x7f) {
    bytes.push((n & 0x7f) | 0x80);
    n >>>= 7;
  }
  bytes.push(n);
  return Buffer.from(bytes);
}

/** Message texte (JSON) : protocol_version (1), source_id (2), destination_id (3), namespace (4), payload_type (5), payload_utf8 (6). */
function encodeMessage({ source, destination, namespace, data }) {
  const string = (field, value) => {
    const b = Buffer.from(value, 'utf8');
    return Buffer.concat([varint((field << 3) | 2), varint(b.length), b]);
  };
  const body = Buffer.concat([
    Buffer.from([0x08, 0x00]), // CASTV2_1_0
    string(2, source),
    string(3, destination),
    string(4, namespace),
    Buffer.from([0x28, 0x00]), // STRING
    string(6, typeof data === 'string' ? data : JSON.stringify(data)),
  ]);
  const size = Buffer.alloc(4);
  size.writeUInt32BE(body.length);
  return Buffer.concat([size, body]);
}

/** Message reçu (sans l'en-tête de longueur) ; [data] est le JSON décodé (null si binaire ou illisible). */
function decodeMessage(buf) {
  const fields = {};
  let o = 0;
  const readVarint = () => {
    let value = 0;
    let shift = 0;
    for (;;) {
      const byte = buf[o++];
      if (byte === undefined) throw new Error('message Cast tronqué');
      value += (byte & 0x7f) * 2 ** shift;
      if (!(byte & 0x80)) return value;
      shift += 7;
    }
  };
  while (o < buf.length) {
    const key = readVarint();
    const wire = key & 7;
    if (wire === 0) fields[key >>> 3] = readVarint();
    else if (wire === 2) {
      const len = readVarint();
      fields[key >>> 3] = buf.subarray(o, o + len);
      o += len;
    } else throw new Error(`type protobuf non géré : ${wire}`);
  }
  const text = (n) => (fields[n] ? fields[n].toString('utf8') : '');
  let data = null;
  try {
    data = fields[6] ? JSON.parse(text(6)) : null;
  } catch {
    data = null;
  }
  return { source: text(2), destination: text(3), namespace: text(4), data };
}

// ---------------------------------------------------------------------------
// Session : lance le lecteur multimédia par défaut du Chromecast sur une URL
// ---------------------------------------------------------------------------

const NS = {
  connection: 'urn:x-cast:com.google.cast.tp.connection',
  heartbeat: 'urn:x-cast:com.google.cast.tp.heartbeat',
  receiver: 'urn:x-cast:com.google.cast.receiver',
  media: 'urn:x-cast:com.google.cast.media',
};
const DEFAULT_MEDIA_RECEIVER = 'CC1AD845';
const SENDER = 'sender-romcloud';

/**
 * Connexion à un Chromecast. Événements : « playing », « ended » (arrêt depuis la télévision,
 * le téléphone ou une autre application), « failed » (Error).
 */
class CastSession extends EventEmitter {
  constructor(device) {
    super();
    this.device = device;
    this.socket = null;
    this.requestId = 1;
    this.transportId = null;
    this.sessionId = null;
    this.closed = false;
  }

  /** Ouvre la connexion TLS ; résout avec l'adresse locale utilisée (celle à donner au Chromecast). */
  connect() {
    return new Promise((resolve, reject) => {
      const socket = tls.connect({ host: this.device.host, port: this.device.port, rejectUnauthorized: false, timeout: 10000 });
      this.socket = socket;
      let buffer = Buffer.alloc(0);
      socket.once('secureConnect', () => {
        socket.setTimeout(0);
        this.send('receiver-0', NS.connection, { type: 'CONNECT' });
        this.heartbeat = setInterval(() => this.send('receiver-0', NS.heartbeat, { type: 'PING' }), 5000);
        resolve(socket.localAddress);
      });
      socket.on('timeout', () => socket.destroy(new Error('timeout')));
      socket.on('data', (chunk) => {
        buffer = Buffer.concat([buffer, chunk]);
        while (buffer.length >= 4 && buffer.length >= 4 + buffer.readUInt32BE(0)) {
          const size = buffer.readUInt32BE(0);
          try {
            this.onMessage(decodeMessage(buffer.subarray(4, 4 + size)));
          } catch {
            // message illisible : ignoré
          }
          buffer = buffer.subarray(4 + size);
        }
      });
      socket.on('error', (err) => {
        if (!this.closed) this.finish('failed', err);
        reject(err);
      });
      socket.on('close', () => this.finish('ended'));
    });
  }

  send(destination, namespace, data) {
    if (this.socket && !this.socket.destroyed) this.socket.write(encodeMessage({ source: SENDER, destination, namespace, data }));
  }

  request(destination, namespace, data) {
    const requestId = this.requestId++;
    this.send(destination, namespace, { ...data, requestId });
    return requestId;
  }

  /** Lance le lecteur par défaut puis lui fait lire le média renvoyé par [mediaFor](adresse locale) : { url, contentType, title }. */
  async load(mediaFor) {
    const localAddress = await this.connect();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        cleanup();
        reject(new Error('timeout'));
      }, 30000);
      const onPlaying = () => {
        cleanup();
        resolve(localAddress);
      };
      const onFailed = (err) => {
        cleanup();
        reject(err || new Error('closed'));
      };
      const cleanup = () => {
        clearTimeout(timer);
        this.off('playing', onPlaying);
        this.off('failed', onFailed);
        this.off('ended', onFailed);
      };
      this.on('playing', onPlaying);
      this.on('failed', onFailed);
      this.on('ended', onFailed);
      this.pendingMedia = mediaFor(localAddress);
      this.request('receiver-0', NS.receiver, { type: 'LAUNCH', appId: DEFAULT_MEDIA_RECEIVER });
    });
  }

  onMessage({ namespace, source, data }) {
    if (!data) return;
    if (namespace === NS.heartbeat && data.type === 'PING') return this.send(source, NS.heartbeat, { type: 'PONG' });
    if (namespace === NS.connection && data.type === 'CLOSE' && source === this.transportId) return this.finish('ended');
    if (namespace === NS.receiver) {
      if (data.type === 'LAUNCH_ERROR') return this.finish('failed', new Error(data.reason || 'LAUNCH_ERROR'));
      if (data.type !== 'RECEIVER_STATUS') return;
      const app = (data.status?.applications || []).find((a) => a.appId === DEFAULT_MEDIA_RECEIVER);
      if (this.pendingMedia && app?.transportId) {
        const media = this.pendingMedia;
        this.pendingMedia = null;
        this.transportId = app.transportId;
        this.sessionId = app.sessionId;
        this.send(this.transportId, NS.connection, { type: 'CONNECT' });
        this.request(this.transportId, NS.media, {
          type: 'LOAD',
          autoplay: true,
          currentTime: 0,
          media: {
            contentId: media.url,
            contentType: media.contentType,
            streamType: 'LIVE',
            metadata: { metadataType: 0, title: media.title },
          },
        });
      } else if (this.sessionId && (!app || app.sessionId !== this.sessionId)) {
        this.finish('ended'); // lecteur fermé ou remplacé par une autre application
      }
      return;
    }
    if (namespace === NS.media) {
      if (['LOAD_FAILED', 'LOAD_CANCELLED', 'INVALID_REQUEST'].includes(data.type)) {
        return this.finish('failed', new Error(data.reason || data.type));
      }
      if (data.type !== 'MEDIA_STATUS') return;
      const status = data.status?.[0];
      if (!status) return;
      if (status.playerState === 'PLAYING' || status.playerState === 'BUFFERING') this.emit('playing');
      else if (status.playerState === 'IDLE' && status.idleReason === 'ERROR') this.finish('failed', new Error('MEDIA_ERROR'));
      else if (status.playerState === 'IDLE' && status.idleReason) this.finish('ended');
    }
  }

  finish(event, err) {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.heartbeat);
    this.socket?.destroy();
    this.emit(event, err);
  }

  /** Ferme le lecteur sur le Chromecast puis la connexion. */
  stop() {
    if (this.closed) return;
    if (this.sessionId) this.request('receiver-0', NS.receiver, { type: 'STOP', sessionId: this.sessionId });
    this.closed = true;
    clearInterval(this.heartbeat);
    const socket = this.socket;
    setTimeout(() => socket?.destroy(), 500); // laisse partir le message STOP
  }
}

module.exports = { discover, CastSession, encodeQuery, parseDns, collectDevices, encodeMessage, decodeMessage };
