// Touches de volume du clavier (multimédia) pendant la partie : elles règlent le son de RomCloud
// (commun à tous les émulateurs) au lieu du volume de Windows, tant que la fenêtre du jeu est au
// premier plan. Windows les traite avant l'application : elles sont interceptées par un crochet
// clavier de bas niveau, et seulement à ce moment-là.
#pragma once

#include <SDL3/SDL.h>

/** Commence à intercepter les touches de volume pour [window]. */
void startVolumeKeys(SDL_Window* window);
void stopVolumeKeys();
/** Appuis reçus depuis le dernier appel : positif pour monter, négatif pour baisser. */
int takeVolumeSteps();
