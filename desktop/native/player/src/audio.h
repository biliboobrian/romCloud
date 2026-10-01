// Sortie audio SDL (file d'attente) : le rythme de l'émulation suit la consommation du son,
// ce qui donne la bonne vitesse de jeu sans craquements.
#pragma once

#include <SDL.h>

#include <cstddef>
#include <cstdint>

class Audio {
 public:
  bool open(double sampleRate);
  void close();
  void push(const int16_t* frames, size_t count);
  /** Trames stéréo en attente de lecture. */
  size_t queuedFrames() const;
  void setPaused(bool paused);
  void clear();
  double rate() const { return rate_; }
  bool isOpen() const { return device_ != 0; }
  /** Le cœur a produit du son récemment (sinon le rythme suit l'horloge). */
  bool producing() const { return SDL_GetTicks() - lastPush_ < 500; }

 private:
  SDL_AudioDeviceID device_ = 0;
  double rate_ = 0;
  uint32_t lastPush_ = 0;
};
