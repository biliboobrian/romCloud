// Mode sans fenêtre de romcloud-player pour la fiche d'un jeu de l'application (manettes du moteur
// intégré) :
//
//   romcloud-player --pad-service --options <options du système> --keys-file <touches du clavier>
//                   [--buttons <boutons de la console>] [--lang fr|en]
//
// Mêmes réglages que le moteur : joueurs des manettes (players.cfg, à côté des touches du clavier)
// et configuration des manettes du système (« romcloud_pad_<GUID> » du fichier d'options). Échange
// par lignes : commandes sur l'entrée standard, état en JSON sur la sortie standard (voir padservice.cpp).
#pragma once

#include <string>

struct PadServiceArgs {
  std::string options, keysFile, buttons, lang = "fr", padStyle;
};

/** Tourne jusqu'à la fin de l'entrée standard (application fermée) ou la commande « quit ». */
int runPadService(const PadServiceArgs& args);
