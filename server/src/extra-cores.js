// Cœurs libretro que RomCloud ajoute aux systèmes du catalogue Daijishou, dont les modèles ne les
// proposent pas (MesenCE, BlastEm, Geargrafx…) : un modèle RetroArch par cœur, ajouté aux émulateurs
// envoyés aux applications. Les moteurs intégrés (Windows et Android) en font un émulateur intégré,
// comme pour les cœurs des modèles. Les systèmes absents du catalogue ont leurs propres plateformes
// RomCloud (server/platforms/).
//
// Un cœur absent du buildbot d'une plateforme, ou qui demande l'OpenGL de bureau (moteur Android :
// OpenGL ES seulement), n'est pas envoyé à cette plateforme, même s'il vient d'un modèle enregistré.
import { CORES, SYSTEM_EXTRA } from './extra-cores-data.js';

const ALL_PLATFORMS = ['android', 'androidtv', 'windows'];

/** Découpe une ligne de commande en arguments (guillemets gérés), comme les applications. */
function tokenize(input) {
  const tokens = [];
  let current = '';
  let quote = null;
  let has = false;
  for (const c of String(input || '')) {
    if (quote) {
      if (c === quote) quote = null;
      else current += c;
    } else if (c === '"' || c === "'") {
      quote = c;
      has = true;
    } else if (/\s/.test(c)) {
      if (has) tokens.push(current);
      current = '';
      has = false;
    } else {
      current += c;
      has = true;
    }
  }
  if (has) tokens.push(current);
  return tokens;
}

/** Cœur libretro d'un modèle RetroArch (extra LIBRETRO) : « snes9x », chemin .so compris ; null sinon. */
export function coreOf(player) {
  const tokens = tokenize(player?.amStartArguments);
  for (let i = 0; i + 2 < tokens.length; i++) {
    if ((tokens[i] === '-e' || tokens[i] === '--es') && tokens[i + 1] === 'LIBRETRO') {
      const core = tokens[i + 2]
        .split(/[\\/]/)
        .pop()
        .replace(/\.so$/, '')
        .replace(/_android$/, '')
        .replace(/_libretro$/, '');
      return core || null;
    }
  }
  return null;
}

const systemKey = (system) => String(system.shortname || system.id || '').toLowerCase();

/** Plateformes où le cœur ajouté par RomCloud fonctionne (toutes pour les autres cœurs). */
export function corePlatforms(core) {
  return CORES[core]?.platforms || ALL_PLATFORMS;
}

/** Modèle RetroArch (64 bits) d'un cœur ajouté, au format Daijishou. */
function retroArchPlayer(system, core) {
  const { name, ext } = CORES[core];
  const prefix = systemKey(system);
  return {
    name: `${prefix} - RetroArch 64 - ${core}`,
    uniqueId: `${prefix}.romcloud.${core}`,
    description: `${name}. Supported extensions: ${ext.join(', ')}.`,
    acceptedFilenameRegex: ext.length ? `^(.*)\\.(?:${ext.join('|')})$` : null,
    amStartArguments: `-n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture\n -e ROM {file.path}\n -e LIBRETRO ${core}\n -e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg`,
    killPackageProcesses: true,
  };
}

/** Cœurs ajoutés au système (absents de ses modèles), avec leurs plateformes : affichés dans l'administration. */
export function extraCores(system) {
  const present = new Set((system.players || []).map(coreOf).filter(Boolean));
  return (SYSTEM_EXTRA[systemKey(system)] || [])
    .filter((core) => !present.has(core))
    .map((core) => ({ core, name: CORES[core].name, platforms: corePlatforms(core) }));
}

/**
 * Émulateurs envoyés à la plateforme [platform] : modèles du système sans les cœurs qu'elle ne peut
 * pas faire tourner, puis les cœurs ajoutés par RomCloud qu'elle fait tourner.
 */
export function playersFor(system, platform) {
  if (!ALL_PLATFORMS.includes(platform)) return system.players || [];
  const runs = (core) => !core || corePlatforms(core).includes(platform);
  const own = (system.players || []).filter((p) => runs(coreOf(p)));
  const added = extraCores(system)
    .filter((c) => c.platforms.includes(platform))
    .map((c) => retroArchPlayer(system, c.core));
  return [...own, ...added];
}

/** Système tel qu'envoyé à l'application de la plateforme [platform] (inconnue : inchangé). */
export function systemFor(system, platform) {
  return ALL_PLATFORMS.includes(platform) ? { ...system, players: playersFor(system, platform) } : system;
}
