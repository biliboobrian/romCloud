// Manettes (base de correspondances de SDL : Xbox, DualShock 3/4, DualSense, Switch…) et clavier,
// convertis en manette libretro (RetroPad) : boutons, sticks analogiques, gâchettes, vibrations.
#pragma once

#include <SDL3/SDL.h>

#include <cstdint>
#include <string>

#include "libretro.h"

class Input {
 public:
  static constexpr int kPorts = 4;

  Input();

  void init();

  // Touches du clavier du joueur 1, une par bouton du RetroPad (SDL_SCANCODE_UNKNOWN : aucune),
  // mémorisées dans un fichier « bouton=scancode » partagé avec l'application (keyboard.rs).
  static constexpr int kButtons = RETRO_DEVICE_ID_JOYPAD_R3 + 1;
  /** Boutons dans l'ordre d'affichage : identifiants libretro et noms du fichier. */
  static const unsigned kButtonOrder[kButtons];
  static const char* buttonName(unsigned id);
  /** Touche attribuable (connue de l'application et non réservée au moteur). */
  static bool assignable(SDL_Scancode code);
  void loadKeys(const std::string& file);
  bool saveKeys() const;
  SDL_Scancode keyOf(unsigned id) const { return id < (unsigned)kButtons ? keys_[id] : SDL_SCANCODE_UNKNOWN; }
  /** Attribue la touche au bouton (et la retire de celui qui l'avait), puis enregistre. */
  void assignKey(unsigned id, SDL_Scancode code);
  void resetKeys();
  void shutdown();
  /** Touches du clavier (joueur 1) : « identifiant libretro=scancode SDL » séparés par des virgules. */
  void setKeys(const std::string& spec);
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
  bool key(unsigned id) const;
  int16_t axis(unsigned port, SDL_GamepadAxis axis) const;

  SDL_Scancode keys_[kButtons] = {};
  std::string keysFile_;
  SDL_Gamepad* pads_[kPorts] = {};
  uint16_t rumbleStrong_[kPorts] = {}, rumbleWeak_[kPorts] = {};
};
