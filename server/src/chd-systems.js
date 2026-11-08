// Systèmes dont les jeux peuvent être convertis en CHD (chd.js) : consoles à CD (et la PS2 : DVD)
// dont tous les cœurs du moteur intégré lisent le CHD (Beetle PSX, SwanStation, PCSX ReARMed,
// DuckStation ; Beetle Saturn, Yabause, Kronos ; Genesis Plus GX, PicoDrive ; Flycast ; Beetle PCE ;
// PCSX2, Play!, ARMSX2). Disque : « cd » (chdman createcd), « dvd » (createdvd, images .iso de la PS2 ;
// ses jeux sur CD, en .cue, restent en createcd). PSP (PPSSPP), GameCube et Wii (Dolphin : RVZ) non
// proposés.
export const CHD_SYSTEMS = { psx: 'cd', saturn: 'cd', segacd: 'cd', dreamcast: 'cd', tgcd: 'cd', ps2: 'dvd' };

/** Disque des jeux du système (« cd », « dvd »), null s'il n'est pas converti en CHD. */
export function chdDisc(system) {
  if (!system || system.folderGames) return null;
  for (const key of [system.id, system.shortname]) {
    const disc = CHD_SYSTEMS[String(key ?? '').toLowerCase()];
    if (disc) return disc;
  }
  return null;
}

/** Fichiers convertibles : images de disque lues par chdman, ou archive .zip qui en contient une. */
export const CHD_SOURCE_RE = /\.(cue|gdi|toc|iso|zip)$/i;
