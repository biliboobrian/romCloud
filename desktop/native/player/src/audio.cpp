#include "audio.h"

#include <algorithm>
#include <cmath>

#include "util.h"

/** Au-delà de cette fréquence, moyenne de plusieurs trames avant la conversion de SDL. */
static constexpr int kMaxStreamRate = 192000;

bool Audio::open(double sampleRate) {
  close();
  coreRate_ = sampleRate > 0 ? sampleRate : 44100.0;
  const int coreFreq = (int)std::lround(coreRate_);
  decimate_ = std::max(1, coreFreq / kMaxStreamRate + (coreFreq % kMaxStreamRate ? 1 : 0));
  rate_ = coreRate_ / decimate_;
  SDL_AudioSpec spec{};
  spec.format = SDL_AUDIO_S16;
  spec.channels = 2;
  spec.freq = (int)std::lround(rate_);
  // Carte son par défaut ; SDL convertit vers sa fréquence et son format.
  stream_ = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, nullptr, nullptr);
  if (!stream_) {
    logf("Audio indisponible : %s", SDL_GetError());
    return false;
  }
  SDL_ResumeAudioStreamDevice(stream_);
  logf("Audio : %d Hz envoyés (cœur %.2f Hz%s)", spec.freq, coreRate_, decimate_ > 1 ? ", moyenne des trames" : "");
  return true;
}

void Audio::close() {
  if (stream_) SDL_DestroyAudioStream(stream_);
  stream_ = nullptr;
  decimate_ = 1;
  sumLeft_ = sumRight_ = 0;
  summed_ = 0;
}

void Audio::push(const int16_t* frames, size_t count) {
  if (!stream_ || !count) return;
  lastPush_ = SDL_GetTicks();
  if (decimate_ == 1) {
    SDL_PutAudioStreamData(stream_, frames, (int)(count * 4));
    return;
  }
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
  if (!reduced_.empty()) SDL_PutAudioStreamData(stream_, reduced_.data(), (int)(reduced_.size() * 2));
}

size_t Audio::queuedFrames() const {
  if (!stream_) return 0;
  const int queued = SDL_GetAudioStreamQueued(stream_);
  return queued > 0 ? (size_t)queued / 4 : 0;
}

void Audio::setPaused(bool paused) {
  if (!stream_) return;
  if (paused) SDL_PauseAudioStreamDevice(stream_);
  else SDL_ResumeAudioStreamDevice(stream_);
}

void Audio::clear() {
  if (stream_) SDL_ClearAudioStream(stream_);
}
