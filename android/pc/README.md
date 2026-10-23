# RomCloud PC (`com.romcloud.wine`)

Application compagnon de RomCloud qui lance les jeux PC (Windows) : moteur de
[Winlator Cmod](https://github.com/coffincolors/winlator) (branche `cmod_bionic`, 10 août 2025,
version publiée `cmod_v13.1.1`), repris sans ses écrans. Licence MIT : [LICENSE-winlator](LICENSE-winlator).

## Pourquoi une application à part

Wine, Box64 et les autres programmes du système Linux sont lancés depuis le dossier de
l'application ; Android l'interdit aux applications qui visent l'API 29 ou plus. RomCloud PC vise
l'API 28 (comme Winlator) ; RomCloud reste à jour. RomCloud la propose à l'installation au premier
jeu PC (Release GitHub de RomCloud, `RomCloud-PC-<version>.apk`) et la lance pour chaque jeu ;
elle n'a pas d'icône.

## Ce qui change par rapport à Winlator Cmod

- `com.romcloud.wine.LaunchActivity` : point d'entrée appelé par RomCloud (appelant signé avec la
  même clé). Accès aux fichiers, téléchargement, installation du système Linux et de Proton,
  conteneur (lecteurs D: Téléchargements et R: dossier des ROMs), raccourci du jeu, écran du jeu.
- `ContentPack` : les gros fichiers (dossier `assets` de l'APK de Winlator Cmod, 700 Mo : système
  Linux, Proton, DXVK, pilotes graphiques, composants Windows) ne sont pas dans l'APK ; ils sont
  téléchargés au premier lancement depuis la version publiée correspondante, entrée par entrée.
- `PackagePath` : le système Linux est construit pour `/data/data/com.winlator.cmod`. Le nom de
  paquet `com.romcloud.wine` a la même longueur : les fichiers extraits sont réécrits sans décalage.
  **Ne jamais changer ce nom de paquet ni lui ajouter de suffixe.**
- Écrans de Winlator (conteneurs, réglages, raccourcis) gardés dans le code mais non accessibles ;
  `switch` sur les ressources réécrits en `if` (identifiants non constants avec AGP 9).

## Composants téléchargés et leurs licences

Wine / Proton (LGPL 2.1, [sources](https://gitlab.winehq.org/wine/wine)), Box64 (MIT),
FEX (MIT), Mesa / Turnip (MIT), DXVK (zlib), VKD3D-Proton (LGPL 2.1), cnc-ddraw (MIT),
PulseAudio (LGPL 2.1), système de fichiers d'origine Ubuntu / Termux (licences de leurs paquets).
