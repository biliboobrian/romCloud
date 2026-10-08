#include "audio.h"

#include <algorithm>
#include <cmath>

#include "util.h"

/** Fréquence de la carte son quand celle du cœur ne convient pas. */
static constexpr int kDeviceRate = 48000;

bool Audio::openDevice(int freq) {
  SDL_AudioSpec want{}, have{};
  want.freq = freq;
  want.format = AUDIO_S16SYS;
  want.channels = 2;
  want.samples = 1024;
  // Aucun changement accepté : SDL convertit vers le format de la carte son.
  device_ = SDL_OpenAudioDevice(nullptr, 0, &want, &have, 0);
  if (!device_) logf("Audio indisponible à %d Hz : %s", freq, SDL_GetError());
  return device_ != 0;
}

bool Audio::open(double sampleRate) {
  close();
  coreRate_ = sampleRate > 0 ? sampleRate : 44100.0;
  const int coreFreq = (int)std::lround(coreRate_);
  // Fréquence du cœur si la carte son l'accepte, sinon 48 kHz et conversion.
  bool direct = coreFreq >= 8000 && coreFreq <= 192000 && openDevice(coreFreq);
  if (!direct && !openDevice(kDeviceRate)) return false;
  rate_ = direct ? coreRate_ : kDeviceRate;
  if (!direct) {
    // Fréquences très élevées : moyenne de plusieurs trames d'abord (filtre simple, peu coûteux),
    // pour que la conversion de SDL parte d'une fréquence raisonnable.
    decimate_ = std::max(1, coreFreq / (kDeviceRate * 2));
    const int reducedFreq = (int)std::lround(coreRate_ / decimate_);
    stream_ = SDL_NewAudioStream(AUDIO_S16SYS, 2, reducedFreq, AUDIO_S16SYS, 2, kDeviceRate);
    if (!stream_) {
      logf("Conversion du son impossible : %s", SDL_GetError());
      close();
      return false;
    }
  }
  SDL_PauseAudioDevice(device_, 0);
  logf("Audio : %d Hz (cœur %.2f Hz%s)", (int)rate_, coreRate_, direct ? "" : ", converti");
  return true;
}

void Audio::close() {
  if (device_) SDL_CloseAudioDevice(device_);
  device_ = 0;
  if (stream_) SDL_FreeAudioStream(stream_);
  stream_ = nullptr;
  decimate_ = 1;
  sumLeft_ = sumRight_ = 0;
  summed_ = 0;
}

void Audio::push(const int16_t* frames, size_t count) {
  if (!device_ || !count) return;
  lastPush_ = SDL_GetTicks();
  if (!stream_) {
    SDL_QueueAudio(device_, frames, (Uint32)(count * 4));
    return;
  }
  const int16_t* input = frames;
  size_t inputFrames = count;
  if (decimate_ > 1) {
    reduced_.clear();
    for (size_t i = 0; i < count; i++) {
      sumLeft_ += frames[2 * i];
      sumRight_ += frames[2 * i + 1];
      if (++summed_ == decimate_) {
        reduced_.push_back((int16_t)(sumLeft_ / decimate_));
        reduced_.push_back((int16_t)(sumRight_ / decimate_));
        sumLeft_ = sumRight_ = 0;
        summed_ = 0;
      }
    }
    input = reduced_.data();
    inputFrames = reduced_.size() / 2;
  }
  if (inputFrames) SDL_AudioStreamPut(stream_, input, (int)(inputFrames * 4));
  const int available = SDL_AudioStreamAvailable(stream_);
  if (available <= 0) return;
  converted_.resize((size_t)available / 2);
  const int got = SDL_AudioStreamGet(stream_, converted_.data(), available);
  if (got > 0) SDL_QueueAudio(device_, converted_.data(), (Uint32)got);
}

size_t Audio::queuedFrames() const { return device_ ? SDL_GetQueuedAudioSize(device_) / 4 : 0; }

void Audio::setPaused(bool paused) {
  if (device_) SDL_PauseAudioDevice(device_, paused ? 1 : 0);
}

void Audio::clear() {
  if (device_) SDL_ClearQueuedAudio(device_);
  if (stream_) SDL_AudioStreamClear(stream_);
}
