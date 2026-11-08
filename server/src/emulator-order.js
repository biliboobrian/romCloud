// Ordre des émulateurs d'un système dans chaque application (Windows, Android, Android TV) : le
// premier disponible est choisi par défaut, l'utilisateur peut toujours en choisir un autre.
//
// Identifiants, ceux des applications :
//  • Android / Android TV : uniqueId des émulateurs (« libretrodroid.<cœur> » pour l'émulateur
//    intégré, sinon celui du modèle Daijishou, suffixé « #2 »… s'il est en double) ;
//  • Windows : « builtin:<cœur> » (moteur intégré ou émulateur géré : RPCS3, Cemu, shadPS4),
//    « emu:<id> » (émulateur installé par l'utilisateur).
//
// Pour afficher l'ordre par défaut dans l'administration, ce module reprend les tables des
// applications : CoreRanking.kt / LibretroPlayers.kt (Android), cores.rs, managed.rs et
// catalog.rs (Windows). À mettre à jour avec elles.
import { AFTER_EXTERNAL, ANDROID_UNAVAILABLE, RANKING, WINDOWS_UNAVAILABLE } from './core-ranking.js';
import { coreOf, playersFor } from './extra-cores.js';
import { HttpError } from './http-error.js';
import { PLATFORMS } from './systems.js';

export { coreOf };

/** Émulateurs gérés par RomCloud sous Windows (managed.rs). */
const WINDOWS_APPS = [
  { core: 'rpcs3', name: 'RPCS3', systems: ['ps3'] },
  { core: 'shadps4', name: 'shadPS4', systems: ['ps4'] },
  { core: 'cemu', name: 'Cemu', systems: ['wiiu'] },
];

/** Émulateurs Windows installés par l'utilisateur (catalog.rs). */
const WINDOWS_EMULATORS = [
  { id: 'ppsspp', name: 'PPSSPP', systems: ['pspminis'] },
  { id: 'vita3k', name: 'Vita3K', systems: ['vita'] },
  { id: 'dolphin', name: 'Dolphin', systems: ['wiiware', 'triforce'] },
  { id: 'eden', name: 'Eden', systems: ['switch'] },
  { id: 'lichii', name: 'Lichii', systems: ['switch'] },
  { id: 'orchid', name: 'Orchid', systems: ['switch'] },
  { id: 'fritchii', name: 'Fritchii', systems: ['switch'] },
  { id: 'ryujinx', name: 'Ryujinx', systems: ['switch'] },
  { id: 'snes9x', name: 'Snes9x', systems: ['satellaview'] },
  { id: 'ares', name: 'ares', systems: ['coleco', 'msx'] },
  { id: 'flycast', name: 'Flycast', systems: ['naomi', 'atomiswave'] },
  { id: 'mednafen', name: 'Mednafen', systems: ['pcfx'] },
  { id: 'supermodel', name: 'Supermodel', systems: ['model3'] },
  { id: 'mame', name: 'MAME', systems: ['cps1', 'cps2', 'cps3', 'stv', 'naomi'] },
  { id: 'xemu', name: 'xemu', systems: ['xbox'] },
  { id: 'xenia', name: 'Xenia Canary', systems: ['xbox360'] },
  { id: 'scummvm', name: 'ScummVM', systems: ['scummvm'] },
];

const systemKeys = (system) => [system.shortname, system.id].filter(Boolean).map((k) => String(k).toLowerCase());
const rankKey = (system) => String(system.shortname || system.id || '').toLowerCase();

/** Cœurs triés selon la table du système ; les absents de la table après, puis les indisponibles. */
function rankCores(system, cores, unavailable) {
  const order = RANKING[rankKey(system)] || [];
  const rank = (core) => (unavailable.includes(core) ? order.length + 1 : order.includes(core) ? order.indexOf(core) : order.length);
  return cores.map((core, i) => ({ core, i })).sort((a, b) => rank(a.core) - rank(b.core) || a.i - b.i).map((x) => x.core);
}

/** uniqueId des modèles, suffixés « #2 »… en double (GameSystem.withUniquePlayerIds). */
function uniquePlayers(players) {
  const seen = new Map();
  return players.map((p) => {
    const n = (seen.get(p.uniqueId) || 0) + 1;
    seen.set(p.uniqueId, n);
    return n === 1 ? p : { ...p, uniqueId: `${p.uniqueId}#${n}` };
  });
}

/** Émulateurs de l'application Android (LibretroPlayers.addTo), dans leur ordre par défaut. */
function androidEmulators(system, platform) {
  const players = uniquePlayers(playersFor(system, platform));
  const cores = [...new Set(players.map(coreOf).filter((c) => c && /^[A-Za-z0-9_-]+$/.test(c)))];
  const ranked = rankCores(system, cores, ANDROID_UNAVAILABLE);
  // Modèles de cœur : lancés par l'émulateur intégré seulement.
  return [
    ...ranked.map((core) => ({ id: `libretrodroid.${core}`, name: `LibretroDroid - ${core}`, kind: 'builtin' })),
    ...players
      .filter((p) => !coreOf(p))
      .map((p) => ({ id: p.uniqueId, name: p.name, kind: 'external' })),
  ];
}

/** Émulateurs de l'application Windows (launcher.rs, options), dans leur ordre par défaut. */
function windowsEmulators(system) {
  const keys = systemKeys(system);
  const forSystem = (e) => e.systems.some((s) => keys.includes(s));
  const cores = [];
  for (const player of playersFor(system, 'windows')) {
    let core = coreOf(player);
    if (!core) continue;
    // Cœurs GLES (Android) : équivalent sans suffixe sous Windows ; MAME (arcade) s'y appelle « mame ».
    core = core === 'mamearcade' ? 'mame' : core.replace(/_gles[23]$/, '');
    if (!cores.includes(core)) cores.push(core);
  }
  const ranked = rankCores(system, cores, WINDOWS_UNAVAILABLE);
  const list = [];
  const app = WINDOWS_APPS.find(forSystem);
  if (app) list.push({ id: `builtin:${app.core}`, name: app.name, kind: 'builtin' });
  const core = (c) => ({ id: `builtin:${c}`, name: `RomCloud - ${c}`, kind: 'builtin' });
  for (const c of ranked.filter((c) => !AFTER_EXTERNAL.includes(c))) list.push(core(c));
  for (const emu of WINDOWS_EMULATORS.filter(forSystem)) list.push({ id: `emu:${emu.id}`, name: emu.name, kind: 'external' });
  // Cœurs pas encore essayés (xemu) : après l'émulateur externe.
  for (const c of ranked.filter((c) => AFTER_EXTERNAL.includes(c))) list.push(core(c));
  return list;
}

/**
 * Émulateurs proposés par chaque application pour ce système (cœurs ajoutés par RomCloud compris),
 * dans l'ordre par défaut de l'application.
 */
export function emulatorCandidates(system) {
  return { android: androidEmulators(system, 'android'), androidtv: androidEmulators(system, 'androidtv'), windows: windowsEmulators(system) };
}

/**
 * Ordre enregistré : { plateforme: [identifiants] }, plateformes connues seulement, sans doublon ;
 * liste vide ou absente : ordre par défaut de l'application.
 */
export function normalizeEmulatorOrder(input) {
  if (input == null || input === '') return {};
  if (typeof input !== 'object' || Array.isArray(input)) throw new HttpError(400, 'errors.invalidEmulatorOrder');
  const out = {};
  for (const [platform, ids] of Object.entries(input)) {
    if (!PLATFORMS.includes(platform)) throw new HttpError(400, 'errors.unknownPlatform', { platform });
    if (ids == null) continue;
    if (!Array.isArray(ids) || ids.length > 200 || ids.some((id) => typeof id !== 'string' || !id || id.length > 200)) {
      throw new HttpError(400, 'errors.invalidEmulatorOrder');
    }
    const unique = [...new Set(ids)];
    if (unique.length) out[platform] = unique;
  }
  return out;
}
