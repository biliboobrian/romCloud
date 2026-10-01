// Manettes (base de correspondances de SDL : Xbox, DualShock 3/4, DualSense, Switch…) et clavier,
// convertis en manette libretro (RetroPad) : boutons, sticks analogiques, gâchettes, vibrations.
#pragma once

#include <SDL.h>

#include <cstdint>

#include "libretro.h"

class Input {
 public:
  static constexpr int kPorts = 4;

  void init();
  void shutdown();
  /** Branchement / débranchement des manettes. */
  void handleEvent(const SDL_Event& event);

  // Callbacks libretro.
  int16_t state(unsigned port, unsigned device, unsigned index, unsigned id) const;
  bool rumble(unsigned port, retro_rumble_effect effect, uint16_t strength);

  /** Start + Select maintenus sur une manette (ouverture du menu). */
  bool menuCombo() const;
  int connected() const;

 private:
  bool button(unsigned port, unsigned id) const;
  int16_t axis(unsigned port, SDL_GameControllerAxis axis) const;

  SDL_GameController* pads_[kPorts] = {};
  uint16_t rumbleStrong_[kPorts] = {}, rumbleWeak_[kPorts] = {};
};
