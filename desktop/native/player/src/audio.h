// Sortie audio SDL3 (flux vers la carte son) : le rythme de l'émulation suit la consommation du son,
// ce qui donne la bonne vitesse de jeu sans craquements. SDL convertit le son du cœur à la
// fréquence de la carte son ; fréquences très élevées (SameBoy : 2 097 152 Hz) : moyenne de
// plusieurs trames d'abord.
#pragma once

#include <SDL3/SDL.h>

#include <cstddef>
#include <cstdint>
#include <vector>

class Audio {
 public:
  bool open(double sampleRate);
  void close();
  void push(const int16_t* frames, size_t count);
  /** Trames stéréo en attente de lecture (à la fréquence [rate]). */
  size_t queuedFrames() const;
  void setPaused(bool paused);
  void clear();
  /** Fréquence du son envoyé à SDL (celle du cœur, ou réduite par la moyenne des trames). */
  double rate() const { return rate_; }
  /** Fréquence du son produit par le cœur. */
  double coreRate() const { return coreRate_; }
  bool isOpen() const { return stream_ != nullptr; }
  /** Le cœur a produit du son récemment (sinon le rythme suit l'horloge). */
  bool producing() const { return SDL_GetTicks() - lastPush_ < 500; }

 private:
  SDL_AudioStream* stream_ = nullptr;
  double rate_ = 0, coreRate_ = 0;
  Uint64 lastPush_ = 0;
  // Moyenne de [decimate_] trames (fréquences très élevées) avant l'envoi à SDL.
  int decimate_ = 1;
  int32_t sumLeft_ = 0, sumRight_ = 0;
  int summed_ = 0;
  std::vector<int16_t> reduced_;
};
