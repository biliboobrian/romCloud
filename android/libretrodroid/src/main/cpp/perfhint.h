// RomCloud : indice de performance d'Android (ADPF, Android 13+) pour le fil de l'émulation. À
// chaque image, la durée du travail (cœur et affichage, sans l'attente du rythme) est signalée
// au système, qui ajuste la fréquence du processeur pour tenir le temps d'une image du jeu.
// Fonctions de libandroid.so lues au lancement : rien n'est fait sur les versions plus anciennes.
#ifndef LIBRETRODROID_PERFHINT_H
#define LIBRETRODROID_PERFHINT_H

#include <chrono>
#include <cstdint>

namespace libretrodroid {

class PerformanceHint {
public:
    ~PerformanceHint() { close(); }

    /** Début du travail d'une image (fil de l'émulation) ; [frameRate] : images par seconde du jeu. */
    void begin(double frameRate);
    /** Fin du travail de l'image : durée signalée au système. */
    void end();
    void close();

private:
    void* session = nullptr;
    bool unavailable = false;
    int64_t targetNanos = 0;
    std::chrono::steady_clock::time_point started;
};

}

#endif //LIBRETRODROID_PERFHINT_H
