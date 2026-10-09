import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-play-test-${process.pid}`);
const accounts = await import('../src/accounts.js');
const play = await import('../src/play.js');

const phone = { device: 'Pixel 8', platform: 'android', appVersion: '1.0', ip: '10.0.0.2', userAgent: 'okhttp' };
const pc = { device: 'PC', platform: 'windows', appVersion: '1.0', ip: '10.0.0.3', userAgent: 'RomCloud' };

const alice = accounts.register('alice', 'secret1', phone);
const bob = accounts.register('bob', 'secret2', pc);
const authOf = (s, client) => accounts.authenticate(s.token, client);

const game = { gameId: 7, systemId: 'snes', title: 'Zelda', fileName: 'zelda.sfc', size: 4, core: 'snes9x' };
const session = 'a'.repeat(32);

test('les appareils connectés se voient, tous profils confondus', () => {
  play.reset();
  play.announce(authOf(alice, phone), { deviceId: 'alice-phone-1', name: 'Pixel', platform: 'android', hosting: { ...game, session } }, phone);
  const r = play.announce(authOf(bob, pc), { deviceId: 'bob-pc-0001', name: 'PC de Bob', platform: 'windows', rtt: 42 }, pc);
  assert.equal(r.peers.length, 1);
  assert.equal(r.peers[0].user, 'alice');
  assert.equal(r.peers[0].hosting.session, session);
  assert.equal(r.peers[0].hosting.title, 'Zelda');
  // L'appareil ne se voit pas lui-même.
  assert.ok(!play.peers('bob-pc-0001').some((p) => p.id === 'bob-pc-0001'));
  play.withdraw(authOf(bob, pc), 'alice-phone-1');  // pas le sien : ignoré
  assert.equal(play.peers('x').length, 2);
  play.withdraw(authOf(alice, phone), 'alice-phone-1');
  assert.equal(play.peers('x').length, 1);
});

test('partie proposée invalide refusée', () => {
  assert.throws(() => play.announce(authOf(alice, phone), { deviceId: 'alice-phone-1', hosting: { ...game, session: 'xyz' } }, phone), { status: 400 });
  assert.throws(() => play.announce(authOf(alice, phone), { deviceId: '!' }, phone), { status: 400 });
});

/** Ouvre une connexion de relais ; renvoie la socket une fois l'en-tête de réponse lu, et son statut. */
function relay(port, query, token, websocket = false) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1', () => {
      // Clé de l'exemple de la RFC 6455 : réponse attendue connue.
      const upgrade = websocket ? 'Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' : 'Upgrade: romcloud-relay';
      socket.write(`GET /api/play/relay?${query} HTTP/1.1\r\nHost: x\r\nConnection: Upgrade\r\n${upgrade}\r\nX-RomCloud-Session: ${token}\r\n\r\n`);
    });
    let buffer = Buffer.alloc(0);
    const onData = (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      const end = buffer.indexOf('\r\n\r\n');
      if (end < 0) return;
      socket.off('data', onData);
      const status = Number(buffer.toString('latin1', 9, 12));
      const rest = buffer.subarray(end + 4);
      if (rest.length) socket.unshift(rest);
      resolve({ socket, status, head: buffer.toString('latin1', 0, end) });
    };
    socket.on('data', onData);
    socket.on('error', reject);
  });
}

const read = (socket, n) => new Promise((resolve) => {
  let buffer = Buffer.alloc(0);
  const onData = (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    if (buffer.length >= n) {
      socket.off('data', onData);
      resolve(buffer);
    }
  };
  socket.on('data', onData);
});

test('relais : l\'hôte attend, l\'invité le rejoint, les octets passent dans les deux sens', async () => {
  play.reset();
  play.announce(authOf(alice, phone), { deviceId: 'alice-phone-1', name: 'Pixel', platform: 'android', hosting: { ...game, session } }, phone);
  const server = http.createServer((req, res) => res.end());
  server.on('upgrade', (req, socket) => {
    if (!play.handleUpgrade(req, socket, (r) => accounts.authenticate(r.headers['x-romcloud-session'], pc))) socket.destroy();
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const { port } = server.address();
  try {
    // Personne n'attend encore : l'invité est refusé.
    assert.equal((await relay(port, `session=${session}&role=guest`, bob.token)).status, 404);
    // Bob ne peut pas attendre à la place d'Alice.
    assert.equal((await relay(port, `session=${session}&role=host`, bob.token)).status, 403);
    assert.equal((await relay(port, `session=${session}&role=host`, 'inconnu')).status, 401);
    // Hôte parti avant l'arrivée d'un invité : sa connexion n'est plus proposée.
    const gone = await relay(port, `session=${session}&role=host`, alice.token);
    assert.equal(gone.status, 101);
    gone.socket.end();
    await new Promise((r) => setTimeout(r, 100));
    assert.equal((await relay(port, `session=${session}&role=guest`, bob.token)).status, 404);
    const host = await relay(port, `session=${session}&role=host`, alice.token, true);
    assert.equal(host.status, 101);
    assert.match(host.head, /Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK\+xOo=/);
    const guest = await relay(port, `session=${session}&role=guest`, bob.token);
    assert.equal(guest.status, 101);
    assert.deepEqual([...await read(host.socket, 1)], [1]);  // un invité est arrivé
    guest.socket.write('demande');
    assert.equal((await read(host.socket, 7)).toString(), 'demande');
    host.socket.write('réponse');
    assert.equal((await read(guest.socket, Buffer.byteLength('réponse'))).toString(), 'réponse');
    // L'hôte ferme : l'invité est déconnecté aussi.
    const closed = new Promise((r) => guest.socket.on('close', r));
    host.socket.destroy();
    await closed;
  } finally {
    server.close();
    play.reset();
  }
});
