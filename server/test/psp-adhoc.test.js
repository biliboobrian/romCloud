import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-psp-adhoc-test-${process.pid}`);
const accounts = await import('../src/accounts.js');
const play = await import('../src/play.js');
const { OP, INIT, openRooms } = await import('../src/psp-adhoc.js');

const phone = { device: 'Pixel 8', platform: 'android', appVersion: '1.0', ip: '10.0.0.2', userAgent: 'okhttp' };
const pc = { device: 'PC', platform: 'windows', appVersion: '1.0', ip: '10.0.0.3', userAgent: 'RomCloud' };
const alice = accounts.register('alice', 'secret1', phone);
const bob = accounts.register('bob', 'secret2', pc);

const session = 'b'.repeat(32);
const hosted = { gameId: 9, systemId: 'psp', title: 'Ridge Racer', fileName: 'rr.iso', size: 4, core: 'ppsspp', link: 'psp', session };

const MAC_A = Buffer.from('02aaaaaaaa01', 'hex');
const MAC_B = Buffer.from('02bbbbbbbb02', 'hex');

/** Serveur HTTP de test : requêtes « Upgrade » traitées comme par le serveur RomCloud. */
async function withServer(fn) {
  play.reset();
  play.announce(accounts.authenticate(alice.token, phone), { deviceId: 'alice-phone-1', name: 'Pixel', platform: 'android', hosting: hosted }, phone);
  const server = http.createServer((req, res) => res.end());
  const open = new Set();
  server.on('connection', (s) => {
    open.add(s);
    s.on('close', () => open.delete(s));
  });
  server.on('upgrade', (req, socket, head) => {
    if (head?.length) socket.unshift(head);
    if (!play.handleUpgrade(req, socket, (r) => accounts.authenticate(r.headers['x-romcloud-session'], pc))) socket.destroy();
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  try {
    await fn(server.address().port);
  } finally {
    for (const s of open) s.destroy();
    server.close();
    play.reset();
  }
}

/** Connexion « Upgrade » vers [kind] (adhocctl ou postoffice) ; { socket, status }. */
function connect(port, kind, { token = bob.token, room = session } = {}) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1', () => {
      socket.write(`GET /api/play/psp/${kind}?session=${room} HTTP/1.1\r\nHost: x\r\nConnection: Upgrade\r\nUpgrade: romcloud-relay\r\nX-RomCloud-Session: ${token}\r\n\r\n`);
    });
    let buffer = Buffer.alloc(0);
    const onData = (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      const end = buffer.indexOf('\r\n\r\n');
      if (end < 0) return;
      socket.off('data', onData);
      const rest = buffer.subarray(end + 4);
      if (rest.length) socket.unshift(rest);
      resolve({ socket, status: Number(buffer.toString('latin1', 9, 12)) });
    };
    socket.on('data', onData);
    socket.on('error', reject);
  });
}

/** Lecteur des octets reçus : read(n) attend exactement n octets. */
function reader(socket) {
  let buffer = Buffer.alloc(0);
  let waiting = null;
  const check = () => {
    if (waiting && buffer.length >= waiting.n) {
      const out = buffer.subarray(0, waiting.n);
      buffer = buffer.subarray(waiting.n);
      const { resolve } = waiting;
      waiting = null;
      resolve(Buffer.from(out));
    }
  };
  socket.on('data', (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    check();
  });
  return {
    read: (n) => new Promise((resolve, reject) => {
      waiting = { n, resolve };
      const timer = setTimeout(() => reject(new Error(`attendu ${n} octets, reçu ${buffer.length}`)), 2000);
      const done = waiting.resolve;
      waiting.resolve = (v) => {
        clearTimeout(timer);
        done(v);
      };
      check();
    }),
    pending: () => buffer.length,
  };
}

const fixed = (s, n) => {
  const b = Buffer.alloc(n);
  Buffer.from(s, 'latin1').copy(b);
  return b;
};
const login = (mac, nick, game = 'UCES00002') => Buffer.concat([Buffer.from([OP.LOGIN]), mac, fixed(nick, 128), fixed(game, 9)]);
const cstr = (b) => b.toString('latin1').split('\0')[0];

test('psp : réseau d\'une partie proposée seulement, profil connecté', async () => {
  await withServer(async (port) => {
    assert.equal((await connect(port, 'adhocctl', { token: 'inconnu' })).status, 401);
    assert.equal((await connect(port, 'adhocctl', { room: 'c'.repeat(32) })).status, 404);
    assert.equal((await connect(port, 'postoffice', { room: 'pas-une-session' })).status, 400);
    assert.equal((await connect(port, 'adhocctl')).status, 101);
  });
});

test('psp : mise en relation (groupe, recherche, arrivées, messages, départs)', async () => {
  await withServer(async (port) => {
    const a = await connect(port, 'adhocctl');
    const b = await connect(port, 'adhocctl');
    const ra = reader(a.socket);
    const rb = reader(b.socket);
    a.socket.write(login(MAC_A, 'Alice'));
    b.socket.write(login(MAC_B, 'Bob'));
    // Alice crée le groupe « RACE0001 ».
    a.socket.write(Buffer.concat([Buffer.from([OP.CONNECT]), fixed('RACE0001', 8)]));
    const bssid = await ra.read(7);
    assert.equal(bssid[0], OP.CONNECT_BSSID);
    assert.deepEqual(bssid.subarray(1), MAC_A);
    // Bob cherche : le groupe d'Alice (créé par elle), puis fin de recherche.
    b.socket.write(Buffer.from([OP.SCAN]));
    const scan = await rb.read(15);
    assert.equal(scan[0], OP.SCAN);
    assert.equal(cstr(scan.subarray(1, 9)), 'RACE0001');
    assert.deepEqual(scan.subarray(9, 15), MAC_A);
    assert.deepEqual([...await rb.read(1)], [OP.SCAN_COMPLETE]);
    // Bob rejoint : chacun apprend l'arrivée de l'autre (surnom, MAC, identifiant).
    b.socket.write(Buffer.concat([Buffer.from([OP.CONNECT]), fixed('RACE0001', 8)]));
    const seenByAlice = await ra.read(139);
    assert.equal(seenByAlice[0], OP.CONNECT);
    assert.equal(cstr(seenByAlice.subarray(1, 129)), 'Bob');
    assert.deepEqual(seenByAlice.subarray(129, 135), MAC_B);
    const bobId = seenByAlice.readUInt32LE(135);
    const seenByBob = await rb.read(139);
    assert.equal(cstr(seenByBob.subarray(1, 129)), 'Alice');
    const aliceId = seenByBob.readUInt32LE(135);
    assert.notEqual(aliceId, bobId);
    const bobBssid = await rb.read(7);
    assert.deepEqual(bobBssid.subarray(1), MAC_A);
    // Message d'Alice : reçu par Bob avec son surnom.
    a.socket.write(Buffer.concat([Buffer.from([OP.CHAT]), fixed('Prêt ?', 64)]));
    const chat = await rb.read(193);
    assert.equal(chat[0], OP.CHAT);
    assert.equal(cstr(chat.subarray(1, 65)), 'Prêt ?');
    assert.equal(cstr(chat.subarray(65, 193)), 'Alice');
    // Bob part : Alice reçoit son identifiant.
    b.socket.write(Buffer.from([OP.DISCONNECT]));
    const gone = await ra.read(5);
    assert.equal(gone[0], OP.DISCONNECT);
    assert.equal(gone.readUInt32LE(1), bobId);
    // Connexion coupée : même effet (Alice dans le groupe, Bob le rejoint puis disparaît).
    b.socket.write(Buffer.concat([Buffer.from([OP.CONNECT]), fixed('RACE0001', 8)]));
    await ra.read(139);
    await rb.read(139 + 7);
    b.socket.destroy();
    assert.equal((await ra.read(5)).readUInt32LE(1), bobId);
    // Demande avant identification : connexion fermée.
    const c = await connect(port, 'adhocctl');
    const closed = new Promise((r) => c.socket.on('close', r));
    c.socket.write(Buffer.from([OP.SCAN]));
    await closed;
  });
});

const init = (type, src, sport, dst = Buffer.alloc(6), dport = 0) => {
  const b = Buffer.alloc(24);
  b.writeInt32LE(type, 0);
  src.copy(b, 4);
  b.writeUInt16LE(sport, 12);
  dst.copy(b, 14);
  b.writeUInt16LE(dport, 22);
  return b;
};

test('psp : bureau de poste, datagrammes (PDP) remis à la console et au port visés', async () => {
  await withServer(async (port) => {
    const a = await connect(port, 'postoffice');
    const b = await connect(port, 'postoffice');
    const rb = reader(b.socket);
    a.socket.write(init(INIT.PDP, MAC_A, 309));
    b.socket.write(init(INIT.PDP, MAC_B, 309));
    await new Promise((r) => setTimeout(r, 50));
    // Vers un port sans socket : perdu ; vers Bob:309 : reçu avec l'adresse et le port d'Alice.
    const header = (mac, p, size) => {
      const h = Buffer.alloc(14);
      mac.copy(h);
      h.writeUInt16LE(p, 8);
      h.writeUInt32LE(size, 10);
      return h;
    };
    a.socket.write(Buffer.concat([header(MAC_B, 999, 3), Buffer.from('nul')]));
    a.socket.write(Buffer.concat([header(MAC_B, 309, 5), Buffer.from('hello')]));
    const got = await rb.read(19);
    assert.deepEqual(got.subarray(0, 6), MAC_A);
    assert.equal(got.readUInt16LE(8), 309);
    assert.equal(got.readUInt32LE(10), 5);
    assert.equal(got.subarray(14).toString(), 'hello');
    await new Promise((r) => setTimeout(r, 50));
    assert.equal(rb.pending(), 0);
  });
});

test('psp : bureau de poste, flux (PTP) : écoute, demande, acceptation, données dans les deux sens', async () => {
  await withServer(async (port) => {
    // Bob écoute sur le port 400.
    const listen = await connect(port, 'postoffice');
    const rl = reader(listen.socket);
    listen.socket.write(init(INIT.PTP_LISTEN, MAC_B, 400));
    await new Promise((r) => setTimeout(r, 50));
    // Alice demande Bob:400 depuis son port 500 : l'écoute reçoit l'adresse et le port d'Alice.
    const conn = await connect(port, 'postoffice');
    const rc = reader(conn.socket);
    conn.socket.write(init(INIT.PTP_CONNECT, MAC_A, 500, MAC_B, 400));
    const request = await rl.read(10);
    assert.deepEqual(request.subarray(0, 6), MAC_A);
    assert.equal(request.readUInt16LE(8), 500);
    // Données envoyées avant l'acceptation : gardées.
    const block = (s) => {
      const b = Buffer.alloc(4);
      b.writeUInt32LE(Buffer.byteLength(s));
      return Buffer.concat([b, Buffer.from(s)]);
    };
    conn.socket.write(block('tôt'));
    // Bob accepte : chacun reçoit l'adresse et le port de l'autre, puis les données passent.
    const acc = await connect(port, 'postoffice');
    const ra = reader(acc.socket);
    acc.socket.write(init(INIT.PTP_ACCEPT, MAC_B, 400, MAC_A, 500));
    const toAlice = await rc.read(10);
    assert.deepEqual(toAlice.subarray(0, 6), MAC_B);
    assert.equal(toAlice.readUInt16LE(8), 400);
    const toBob = await ra.read(10);
    assert.deepEqual(toBob.subarray(0, 6), MAC_A);
    assert.equal(toBob.readUInt16LE(8), 500);
    const early = await ra.read(4 + Buffer.byteLength('tôt'));
    assert.equal(early.subarray(4).toString(), 'tôt');
    acc.socket.write(block('réponse'));
    assert.equal((await rc.read(4 + Buffer.byteLength('réponse'))).subarray(4).toString(), 'réponse');
    // Bob ferme : la connexion d'Alice est fermée aussi.
    const closed = new Promise((r) => conn.socket.on('close', r));
    acc.socket.destroy();
    await closed;
    // Demande vers un port qui n'écoute pas : refusée après les essais (2 s).
    const lost = await connect(port, 'postoffice');
    const lostClosed = new Promise((r) => lost.socket.on('close', r));
    lost.socket.write(init(INIT.PTP_CONNECT, MAC_A, 501, MAC_B, 401));
    await lostClosed;
  });
  // Plus aucune connexion : réseau de la partie retiré.
  await new Promise((r) => setTimeout(r, 50));
  assert.equal(openRooms(), 0);
});
