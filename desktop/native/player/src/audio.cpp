#include "audio.h"

#include <cmath>

#include "util.h"

bool Audio::open(double sampleRate) {
  close();
  rate_ = sampleRate > 0 ? sampleRate : 44100.0;
  SDL_AudioSpec want{}, have{};
  want.freq = (int)std::lround(rate_);
  want.format = AUDIO_S16SYS;
  want.channels = 2;
  want.samples = 1024;
  // Aucun changement accepté : SDL convertit vers le format de la carte son.
  device_ = SDL_OpenAudioDevice(nullptr, 0, &want, &have, 0);
  if (!device_) {
    logf("Audio indisponible : %s", SDL_GetError());
    return false;
  }
  SDL_PauseAudioDevice(device_, 0);
  logf("Audio : %d Hz (cœur %.2f Hz)", want.freq, rate_);
  return true;
}

void Audio::close() {
  if (device_) SDL_CloseAudioDevice(device_);
  device_ = 0;
}

void Audio::push(const int16_t* frames, size_t count) {
  if (!device_ || !count) return;
  SDL_QueueAudio(device_, frames, (Uint32)(count * 4));
  lastPush_ = SDL_GetTicks();
}

size_t Audio::queuedFrames() const { return device_ ? SDL_GetQueuedAudioSize(device_) / 4 : 0; }

void Audio::setPaused(bool paused) {
  if (device_) SDL_PauseAudioDevice(device_, paused ? 1 : 0);
}

void Audio::clear() {
  if (device_) SDL_ClearQueuedAudio(device_);
}
