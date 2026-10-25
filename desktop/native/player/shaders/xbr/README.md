# Déclinaisons de xBR (Hyllian)

Shaders et préréglages repris tels quels de [libretro/glsl-shaders](https://github.com/libretro/glsl-shaders/tree/master/xbr)
(dossier `xbr`), au format GLSL de RetroArch. Ils sont intégrés au moteur (`shaderchain.cpp`) et proposés dans
« Image > xBR > Version ».

Licence : MIT, Copyright (C) 2011-2016 Hyllian (texte complet en tête de chaque fichier `.glsl`).

Non repris, pour garder la licence MIT du moteur :

- `custom-jinc2-sharper.glsl` (GPL v2), dernière passe des préréglages Super-xBR, et `stock.glsl` : remplacés
  par l'agrandissement bicubique (Catmull-Rom) du moteur jusqu'à la taille de l'écran ;
- `xbr-hybrid` (GPL v2), `2xBR-multipass` (sans licence), `super-xbr-deposterize` (passes d'un autre dossier).
