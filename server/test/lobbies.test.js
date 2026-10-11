import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-lobbies-test-${process.pid}-${Date.now()}`);
const accounts = await import('../src/accounts.js');
const play = await import('../src/play.js');
const lobbies = await import('../src/lobbies.js');

const phone = { device: 'Pixel 8', platform: 'android', appVersion: '1.0', ip: '10.0.0.2', userAgent: 'okhttp' };
const pc = { device: 'PC', platform: 'windows', appVersion: '1.0', ip: '10.0.0.3', userAgent: 'RomCloud' };
const alice = accounts.register('alice', 'secret1', phone);
const bob = accounts.register('bob', 'secret2', pc);
const a = accounts.authenticate(alice.token, phone);
const b = accounts.authenticate(bob.token, pc);
const game = { gameId: 7, systemId: 'snes', title: 'Super Bomberman', fileName: 'bomberman.sfc', size: 4, players: 4 };

function connect() {
  play.reset();
  lobbies.reset();
  play.announce(a, { deviceId: 'alice-phone-1', name: 'Pixel', platform: 'android' }, phone);
  play.announce(b, { deviceId: 'bob-pc-0001', name: 'PC de Bob', platform: 'windows', activeAt: Date.now() - 6 * 60_000 }, pc);
}

test('joueurs connectés : présent (activité récente ou en partie) ou absent', () => {
  connect();
  const { players } = lobbies.list();
  assert.equal(players.find((p) => p.id === 'alice-phone-1').active, true);
  assert.equal(players.find((p) => p.id === 'bob-pc-0001').active, false);
  play.announce(b, { deviceId: 'bob-pc-0001', name: 'PC de Bob', busy: true, activeAt: Date.now() - 6 * 60_000 }, pc);
  assert.equal(lobbies.list().players.find((p) => p.id === 'bob-pc-0001').active, true);
});

test('lobby vierge : l’hôte choisit le jeu et le mode, les invités rejoignent puis jouent après lui', () => {
  connect();
  const lobby = lobbies.create(a, { deviceId: 'alice-phone-1' });
  assert.equal(lobby.game, null);
  assert.equal(lobby.members[0].host, true);
  // Appareil d'un autre profil : refusé ; appareil non annoncé : refusé.
  assert.throws(() => lobbies.join(a, lobby.id, { deviceId: 'bob-pc-0001' }), { status: 403 });
  assert.throws(() => lobbies.join(b, lobby.id, { deviceId: 'bob-inconnu-1' }), { status: 409 });
  const joined = lobbies.join(b, lobby.id, { deviceId: 'bob-pc-0001' });
  assert.deepEqual(joined.members.map((m) => [m.name, m.host, m.status]), [['Pixel', true, 'waiting'], ['PC de Bob', false, 'waiting']]);
  // Seul l'hôte change le jeu et le mode.
  assert.throws(() => lobbies.update(b, lobby.id, { deviceId: 'bob-pc-0001', game }), { status: 403 });
  assert.throws(() => lobbies.setStatus(a, lobby.id, { deviceId: 'alice-phone-1', status: 'playing' }), { status: 400 });
  let l = lobbies.update(a, lobby.id, { deviceId: 'alice-phone-1', game, modes: ['netplay', 'link', 'autre'], mode: 'link' });
  assert.deepEqual(l.modes, ['netplay', 'link']);
  assert.equal(l.mode, 'link');
  l = lobbies.update(a, lobby.id, { deviceId: 'alice-phone-1', mode: 'netplay' });
  assert.equal(l.mode, 'netplay');
  assert.throws(() => lobbies.update(a, lobby.id, { deviceId: 'alice-phone-1', mode: 'cable' }), { status: 400 });
  // L'hôte lance : partie commencée ; l'invité en jeu ensuite.
  l = lobbies.setStatus(a, lobby.id, { deviceId: 'alice-phone-1', status: 'playing' });
  assert.equal(l.started, true);
  l = lobbies.setStatus(b, lobby.id, { deviceId: 'bob-pc-0001', status: 'playing' });
  assert.deepEqual(l.members.map((m) => m.status), ['playing', 'playing']);
  // Nouveau jeu : tous en attente, partie à relancer.
  l = lobbies.update(a, lobby.id, { deviceId: 'alice-phone-1', game: { ...game, title: 'Micro Machines' }, modes: ['netplay'] });
  assert.equal(l.started, false);
  assert.deepEqual(l.members.map((m) => m.status), ['waiting', 'waiting']);
});

test('quitter, dissoudre, hôte parti : le lobby disparaît', () => {
  connect();
  const lobby = lobbies.create(a, { deviceId: 'alice-phone-1', game, modes: ['netplay'] });
  lobbies.join(b, lobby.id, { deviceId: 'bob-pc-0001' });
  lobbies.leave(b, lobby.id, { deviceId: 'bob-pc-0001' });
  assert.equal(lobbies.list().lobbies[0].members.length, 1);
  assert.throws(() => lobbies.dissolve(b, lobby.id, 'bob-pc-0001'), { status: 403 });
  lobbies.dissolve(a, lobby.id, 'alice-phone-1');
  assert.equal(lobbies.list().lobbies.length, 0);
  assert.throws(() => lobbies.join(b, lobby.id, { deviceId: 'bob-pc-0001' }), { status: 404 });
  // Hôte qui ne s'annonce plus : lobby dissous.
  const again = lobbies.create(a, { deviceId: 'alice-phone-1' });
  lobbies.join(b, again.id, { deviceId: 'bob-pc-0001' });
  play.withdraw(a, 'alice-phone-1');
  assert.equal(lobbies.list().lobbies.length, 0);
  // Un appareil n'est que dans un lobby : en créer un autre quitte le premier.
  play.announce(a, { deviceId: 'alice-phone-1', name: 'Pixel' }, phone);
  const first = lobbies.create(a, { deviceId: 'alice-phone-1' });
  lobbies.join(b, first.id, { deviceId: 'bob-pc-0001' });
  lobbies.create(b, { deviceId: 'bob-pc-0001' });
  assert.deepEqual(lobbies.list().lobbies.map((l) => l.members.length), [1, 1]);
});
