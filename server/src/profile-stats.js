// Statistiques du profil connecté, affichées dans les applications : temps de jeu, jeux téléchargés
// par appareil (journal des téléchargements, download-logs.js), jeux les plus joués et historique des
// parties (play_sessions, notées depuis cette version).
import { db } from './db.js';
import './download-logs.js';

/** Parties de l'historique renvoyées au plus. */
const HISTORY_LIMIT = 50;
const TOP_LIMIT = 10;

export function profileStats(userId) {
  const totals = db.prepare(`SELECT COALESCE(SUM(seconds), 0) AS seconds, COALESCE(SUM(sessions), 0) AS sessions,
                               COUNT(*) AS games FROM playtime WHERE user_id = ? AND seconds > 0`).get(userId);
  // Jeux téléchargés : un jeu compté une fois par appareil (reprises et nouveaux téléchargements compris).
  const downloaded = db.prepare(`SELECT COUNT(DISTINCT item_id) AS games FROM download_logs
                                 WHERE user_id = ? AND kind = 'game' AND item_id IS NOT NULL`).get(userId);
  const devices = db.prepare(`SELECT device, platform, COUNT(DISTINCT item_id) AS games,
                                SUM(CASE WHEN range_start = 0 THEN 1 ELSE 0 END) AS downloads,
                                COALESCE(SUM(bytes), 0) AS bytes, MAX(at) AS last_at
                              FROM download_logs WHERE user_id = ? AND kind = 'game' AND item_id IS NOT NULL
                              GROUP BY device, platform ORDER BY games DESC, last_at DESC LIMIT 50`).all(userId);
  const playedOn = db.prepare(`SELECT device, platform, COALESCE(SUM(seconds), 0) AS seconds, COUNT(*) AS sessions
                               FROM play_sessions WHERE user_id = ? GROUP BY device, platform`).all(userId);
  const top = db.prepare(`SELECT p.game_id, p.seconds, p.sessions, p.last_played_at, g.title, g.system_id
                          FROM playtime p JOIN games g ON g.id = p.game_id
                          WHERE p.user_id = ? AND p.seconds > 0 ORDER BY p.seconds DESC LIMIT ?`).all(userId, TOP_LIMIT);
  const history = db.prepare(`SELECT s.game_id, s.seconds, s.ended_at, s.device, s.platform, g.title, g.system_id
                              FROM play_sessions s JOIN games g ON g.id = s.game_id
                              WHERE s.user_id = ? ORDER BY s.ended_at DESC, s.id DESC LIMIT ?`).all(userId, HISTORY_LIMIT);

  // Appareils : ceux des téléchargements et ceux des parties, réunis.
  const key = (r) => `${r.device ?? ''}\u0000${r.platform ?? ''}`;
  const byDevice = new Map();
  const device = (r) => {
    if (!byDevice.has(key(r))) {
      byDevice.set(key(r), { device: r.device, platform: r.platform, games: 0, downloads: 0, bytes: 0, lastDownloadAt: null, seconds: 0, sessions: 0 });
    }
    return byDevice.get(key(r));
  };
  for (const r of devices) Object.assign(device(r), { games: r.games, downloads: r.downloads ?? 0, bytes: r.bytes, lastDownloadAt: r.last_at });
  for (const r of playedOn) Object.assign(device(r), { seconds: r.seconds, sessions: r.sessions });

  return {
    totals: { seconds: totals.seconds, sessions: totals.sessions, gamesPlayed: totals.games, gamesDownloaded: downloaded.games },
    devices: [...byDevice.values()].sort((a, b) => b.games - a.games || b.seconds - a.seconds),
    topGames: top.map((r) => ({ gameId: r.game_id, title: r.title, systemId: r.system_id, seconds: r.seconds, sessions: r.sessions, lastPlayedAt: r.last_played_at })),
    history: history.map((r) => ({ gameId: r.game_id, title: r.title, systemId: r.system_id, seconds: r.seconds, endedAt: r.ended_at, device: r.device, platform: r.platform })),
  };
}
