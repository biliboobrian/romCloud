// Sortie audio SDL (file d'attente) : le rythme de l'émulation suit la consommation du son,
// ce qui donne la bonne vitesse de jeu sans craquements. Fréquence du cœur refusée par la carte
// son ou inhabituelle (SameBoy : 2 097 152 Hz) : son converti à 48 kHz.
#pragma once

#include <SDL.h>

#include <cstddef>
#include <cstdint>
#include <vector>

class Audio {
 public:
  bool open(double sampleRate);
  void close();
  void push(const int16_t* frames, size_t count);
  /** Trames stéréo en attente de lecture (à la fréquence de la carte son, [rate]). */
  size_t queuedFrames() const;
  void setPaused(bool paused);
  void clear();
  /** Fréquence de la carte son. */
  double rate() const { return rate_; }
  /** Fréquence du son produit par le cœur. */
  double coreRate() const { return coreRate_; }
  bool isOpen() const { return device_ != 0; }
  /** Le cœur a produit du son récemment (sinon le rythme suit l'horloge). */
  bool producing() const { return SDL_GetTicks() - lastPush_ < 500; }

 private:
  bool openDevice(int freq);

  SDL_AudioDeviceID device_ = 0;
  double rate_ = 0, coreRate_ = 0;
  uint32_t lastPush_ = 0;
  // Conversion de fréquence : moyenne de [decimate_] trames (fréquences très élevées), puis SDL.
  SDL_AudioStream* stream_ = nullptr;
  int decimate_ = 1;
  int32_t sumLeft_ = 0, sumRight_ = 0;
  int summed_ = 0;
  std::vector<int16_t> reduced_, converted_;
};
