import { test } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';

process.env.DATA_DIR = path.join(os.tmpdir(), `romcloud-profile-stats-test-${process.pid}`);

const accounts = await import('../src/accounts.js');
const { db } = await import('../src/db.js');
const { profileStats } = await import('../src/profile-stats.js');

db.prepare("INSERT INTO systems (id, name, shortname, folder) VALUES ('snes', 'Super Nintendo', 'snes', 'snes')").run();
const game = (file, title) => Number(db.prepare("INSERT INTO games (system_id, file_name, size, mtime, title) VALUES ('snes', ?, 1000, 0, ?)").run(file, title).lastInsertRowid);
const zelda = game('zelda.sfc', 'Zelda');
const mario = game('mario.sfc', 'Mario');

const download = (userId, gameId, device, platform, rangeStart = 0) => db.prepare(
  `INSERT INTO download_logs (kind, item_id, system_id, bytes, range_start, complete, status, user_id, device, platform)
   VALUES ('game', ?, 'snes', 1000, ?, 1, 200, ?, ?, ?)`,
).run(gameId, rangeStart, userId, device, platform);

test('statistiques du profil : temps de jeu, téléchargements par appareil, jeux les plus joués, historique', () => {
  const client = { device: 'Pixel 8', platform: 'android', appVersion: '1.67.0', ip: '10.0.0.2', userAgent: 'okhttp' };
  const { user } = accounts.register('bob', 'secret1', client);
  const other = accounts.register('carol', 'secret1', client).user;
  const phone = { device: 'Pixel 8', platform: 'android' };
  const pc = { device: 'BUREAU', platform: 'windows' };

  // Zelda téléchargé deux fois sur le téléphone (dont une reprise), Mario sur le téléphone et le PC.
  download(user.id, zelda, 'Pixel 8', 'android');
  download(user.id, zelda, 'Pixel 8', 'android', 500);
  download(user.id, mario, 'Pixel 8', 'android');
  download(user.id, mario, 'BUREAU', 'windows');
  download(other.id, zelda, 'Autre', 'android');

  const yesterday = Date.now() - 86400_000;
  accounts.addPlaytime(user.id, zelda, 600, phone, yesterday);
  accounts.addPlaytime(user.id, zelda, 1200, pc);
  accounts.addPlaytime(user.id, mario, 300, phone);
  // Fin de partie trop ancienne (plus de 30 jours) : notée maintenant.
  accounts.addPlaytime(other.id, zelda, 60, phone, Date.now() - 60 * 86400_000);

  const stats = profileStats(user.id);
  assert.deepEqual(stats.totals, { seconds: 2100, sessions: 3, gamesPlayed: 2, gamesDownloaded: 2 });

  const onPhone = stats.devices.find((d) => d.device === 'Pixel 8');
  assert.equal(onPhone.games, 2);
  assert.equal(onPhone.downloads, 2); // la reprise ne compte pas comme un nouveau téléchargement
  assert.equal(onPhone.seconds, 900);
  assert.equal(onPhone.sessions, 2);
  const onPc = stats.devices.find((d) => d.device === 'BUREAU');
  assert.equal(onPc.games, 1);
  assert.equal(onPc.seconds, 1200);
  assert.equal(stats.devices.length, 2);

  assert.deepEqual(stats.topGames.map((g) => [g.title, g.seconds, g.sessions]), [['Zelda', 1800, 2], ['Mario', 300, 1]]);
  assert.deepEqual(stats.history.map((h) => [h.title, h.seconds, h.device]), [['Mario', 300, 'Pixel 8'], ['Zelda', 1200, 'BUREAU'], ['Zelda', 600, 'Pixel 8']]);
  assert.ok(stats.history[2].endedAt < stats.history[1].endedAt);

  const otherStats = profileStats(other.id);
  assert.equal(otherStats.history.length, 1);
  assert.ok(Date.now() - new Date(`${otherStats.history[0].endedAt}Z`).getTime() < 60_000);
});
