// ROMs sur le disque : <dossier ROMs>\<dossier du système>\<fichier>.
// Un jeu est téléchargé si le fichier existe avec la taille attendue.
const fs = require('node:fs');
const path = require('node:path');
const settings = require('./settings');

const systemDir = (system) => path.join(settings.load().romsDir, system.folder);
const fileFor = (system, game) => path.join(systemDir(system), game.fileName);

function isDownloaded(system, game) {
  try {
    return fs.statSync(fileFor(system, game)).size === game.size;
  } catch {
    return false;
  }
}

/** Identifiants des jeux présents (un seul listage du dossier par système). */
function downloadedIds(systems, games) {
  const sizes = new Map();
  for (const system of systems) {
    const dir = systemDir(system);
    let names = [];
    try {
      names = fs.readdirSync(dir);
    } catch {
      continue;
    }
    for (const name of names) {
      try {
        sizes.set(`${system.id}/${name}`, fs.statSync(path.join(dir, name)).size);
      } catch {
        /* fichier disparu entre-temps */
      }
    }
  }
  return games.filter((g) => sizes.get(`${g.systemId}/${g.fileName}`) === g.size).map((g) => g.id);
}

/** Systèmes dont le dossier contient au moins un fichier de jeu (seuls affichés hors ligne). */
function systemsWithGames(systems) {
  return systems.filter((system) => {
    try {
      return fs.readdirSync(systemDir(system), { withFileTypes: true }).some((e) => e.isFile() && !e.name.endsWith('.part'));
    } catch {
      return false;
    }
  }).map((s) => s.id);
}

/** Emplacement d'un BIOS : <dossier BIOS>\<chemin relatif, sous-dossiers compris>. */
const biosFile = (bios) => path.join(settings.biosDir(), ...bios.path.split('/').filter((p) => p && p !== '.' && p !== '..'));

/** BIOS absents du PC (ou de taille différente). */
function missingBios(files) {
  return files.filter((b) => {
    try {
      return fs.statSync(biosFile(b)).size !== b.size;
    } catch {
      return true;
    }
  });
}

function remove(system, game) {
  fs.rmSync(fileFor(system, game), { force: true });
}

module.exports = { systemDir, fileFor, isDownloaded, downloadedIds, systemsWithGames, remove, biosFile, missingBios };
