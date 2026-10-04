// Fonctions pures (sans Electron) : lecture des modèles d'émulateurs Daijishou du serveur.
const path = require('node:path');
const { rankCores } = require('./coreRanking');

/** Découpe une ligne de commande en arguments (guillemets gérés). */
function tokenize(input) {
  const tokens = [];
  let current = '';
  let quote = null;
  let has = false;
  for (const c of String(input)) {
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

/** Nom du cœur Libretro d'un modèle Android (« mupen64plus_next_gles3 », ou chemin .so). */
function coreOf(player) {
  const tokens = tokenize(player.amStartArguments || '');
  for (let i = 0; i < tokens.length - 2; i++) {
    if ((tokens[i] === '-e' || tokens[i] === '--es') && tokens[i + 1] === 'LIBRETRO') {
      return path.basename(tokens[i + 2]).replace(/\.so$/, '').replace(/_android$/, '').replace(/_libretro$/, '');
    }
  }
  return null;
}

/**
 * Cœurs RetroArch utilisables pour un système (sans doublon), du plus abouti au moins abouti
 * (coreRanking.js), sinon dans l'ordre des modèles.
 */
function cores(system) {
  // Les cœurs GLES (Android) ont un équivalent sans suffixe sous Windows, MAME (arcade) s'y appelle « mame ».
  const names = (system.players || []).map(coreOf).filter(Boolean).map((c) => (c === 'mamearcade' ? 'mame' : c.replace(/_gles[23]$/, '')));
  return rankCores(system.shortname || system.id, [...new Set(names)]);
}

module.exports = { tokenize, coreOf, cores };
