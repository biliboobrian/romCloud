#include "input.h"

#include <cstdlib>

#include "util.h"

// Clavier du joueur 1 (disposition proche de RetroArch).
static const struct {
  unsigned id;
  SDL_Scancode key;
} kKeyboard[] = {
    {RETRO_DEVICE_ID_JOYPAD_UP, SDL_SCANCODE_UP},       {RETRO_DEVICE_ID_JOYPAD_DOWN, SDL_SCANCODE_DOWN},
    {RETRO_DEVICE_ID_JOYPAD_LEFT, SDL_SCANCODE_LEFT},   {RETRO_DEVICE_ID_JOYPAD_RIGHT, SDL_SCANCODE_RIGHT},
    {RETRO_DEVICE_ID_JOYPAD_B, SDL_SCANCODE_Z},         {RETRO_DEVICE_ID_JOYPAD_A, SDL_SCANCODE_X},
    {RETRO_DEVICE_ID_JOYPAD_Y, SDL_SCANCODE_A},         {RETRO_DEVICE_ID_JOYPAD_X, SDL_SCANCODE_S},
    {RETRO_DEVICE_ID_JOYPAD_L, SDL_SCANCODE_Q},         {RETRO_DEVICE_ID_JOYPAD_R, SDL_SCANCODE_W},
    {RETRO_DEVICE_ID_JOYPAD_L2, SDL_SCANCODE_E},        {RETRO_DEVICE_ID_JOYPAD_R2, SDL_SCANCODE_R},
    {RETRO_DEVICE_ID_JOYPAD_START, SDL_SCANCODE_RETURN}, {RETRO_DEVICE_ID_JOYPAD_SELECT, SDL_SCANCODE_RSHIFT},
};

// Manette SDL (disposition Xbox : A en bas) -> RetroPad (disposition Super Nintendo : B en bas).
static int padButton(unsigned id) {
  switch (id) {
    case RETRO_DEVICE_ID_JOYPAD_B: return SDL_CONTROLLER_BUTTON_A;
    case RETRO_DEVICE_ID_JOYPAD_A: return SDL_CONTROLLER_BUTTON_B;
    case RETRO_DEVICE_ID_JOYPAD_Y: return SDL_CONTROLLER_BUTTON_X;
    case RETRO_DEVICE_ID_JOYPAD_X: return SDL_CONTROLLER_BUTTON_Y;
    case RETRO_DEVICE_ID_JOYPAD_L: return SDL_CONTROLLER_BUTTON_LEFTSHOULDER;
    case RETRO_DEVICE_ID_JOYPAD_R: return SDL_CONTROLLER_BUTTON_RIGHTSHOULDER;
    case RETRO_DEVICE_ID_JOYPAD_L3: return SDL_CONTROLLER_BUTTON_LEFTSTICK;
    case RETRO_DEVICE_ID_JOYPAD_R3: return SDL_CONTROLLER_BUTTON_RIGHTSTICK;
    case RETRO_DEVICE_ID_JOYPAD_START: return SDL_CONTROLLER_BUTTON_START;
    case RETRO_DEVICE_ID_JOYPAD_SELECT: return SDL_CONTROLLER_BUTTON_BACK;
    case RETRO_DEVICE_ID_JOYPAD_UP: return SDL_CONTROLLER_BUTTON_DPAD_UP;
    case RETRO_DEVICE_ID_JOYPAD_DOWN: return SDL_CONTROLLER_BUTTON_DPAD_DOWN;
    case RETRO_DEVICE_ID_JOYPAD_LEFT: return SDL_CONTROLLER_BUTTON_DPAD_LEFT;
    case RETRO_DEVICE_ID_JOYPAD_RIGHT: return SDL_CONTROLLER_BUTTON_DPAD_RIGHT;
    default: return -1;
  }
}

void Input::init() {
  for (int i = 0; i < SDL_NumJoysticks(); i++) {
    SDL_Event e{};
    e.cdevice.type = SDL_CONTROLLERDEVICEADDED;
    e.cdevice.which = i;
    handleEvent(e);
  }
}

void Input::shutdown() {
  for (auto& pad : pads_) {
    if (pad) SDL_GameControllerClose(pad);
    pad = nullptr;
  }
}

void Input::handleEvent(const SDL_Event& event) {
  if (event.type == SDL_CONTROLLERDEVICEADDED) {
    if (!SDL_IsGameController(event.cdevice.which)) return;
    SDL_JoystickID id = SDL_JoystickGetDeviceInstanceID(event.cdevice.which);
    for (auto* pad : pads_) {
      if (pad && SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(pad)) == id) return;  // déjà ouverte
    }
    for (int port = 0; port < kPorts; port++) {
      if (!pads_[port]) {
        pads_[port] = SDL_GameControllerOpen(event.cdevice.which);
        if (pads_[port]) logf("Manette %d : %s", port + 1, SDL_GameControllerName(pads_[port]));
        return;
      }
    }
  } else if (event.type == SDL_CONTROLLERDEVICEREMOVED) {
    for (auto& pad : pads_) {
      if (pad && SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(pad)) == event.cdevice.which) {
        SDL_GameControllerClose(pad);
        pad = nullptr;
      }
    }
  }
}

int Input::connected() const {
  int n = 0;
  for (auto* pad : pads_) n += pad != nullptr;
  return n;
}

int16_t Input::axis(unsigned port, SDL_GameControllerAxis a) const {
  if (port >= (unsigned)kPorts || !pads_[port]) return 0;
  return SDL_GameControllerGetAxis(pads_[port], a);
}

bool Input::button(unsigned port, unsigned id) const {
  if (port >= (unsigned)kPorts) return false;
  if (port == 0) {
    const Uint8* keys = SDL_GetKeyboardState(nullptr);
    for (const auto& k : kKeyboard) {
      if (k.id == id && keys[k.key]) return true;
    }
  }
  SDL_GameController* pad = pads_[port];
  if (!pad) return false;
  if (id == RETRO_DEVICE_ID_JOYPAD_L2) return SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_TRIGGERLEFT) > 16000;
  if (id == RETRO_DEVICE_ID_JOYPAD_R2) return SDL_GameControllerGetAxis(pad, SDL_CONTROLLER_AXIS_TRIGGERRIGHT) > 16000;
  int b = padButton(id);
  return b >= 0 && SDL_GameControllerGetButton(pad, (SDL_GameControllerButton)b);
}

int16_t Input::state(unsigned port, unsigned device, unsigned index, unsigned id) const {
  switch (device & RETRO_DEVICE_MASK) {
    case RETRO_DEVICE_JOYPAD:
      if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
        int16_t mask = 0;
        for (unsigned b = 0; b <= RETRO_DEVICE_ID_JOYPAD_R3; b++) {
          if (button(port, b)) mask |= (int16_t)(1 << b);
        }
        return mask;
      }
      return button(port, id) ? 1 : 0;

    case RETRO_DEVICE_ANALOG: {
      if (index == RETRO_DEVICE_INDEX_ANALOG_BUTTON) {
        if (id == RETRO_DEVICE_ID_JOYPAD_L2) return axis(port, SDL_CONTROLLER_AXIS_TRIGGERLEFT);
        if (id == RETRO_DEVICE_ID_JOYPAD_R2) return axis(port, SDL_CONTROLLER_AXIS_TRIGGERRIGHT);
        return button(port, id) ? 0x7FFF : 0;
      }
      bool left = index == RETRO_DEVICE_INDEX_ANALOG_LEFT;
      bool x = id == RETRO_DEVICE_ID_ANALOG_X;
      if (port < (unsigned)kPorts && pads_[port]) {
        SDL_GameControllerAxis a = left ? (x ? SDL_CONTROLLER_AXIS_LEFTX : SDL_CONTROLLER_AXIS_LEFTY)
                                        : (x ? SDL_CONTROLLER_AXIS_RIGHTX : SDL_CONTROLLER_AXIS_RIGHTY);
        int16_t v = axis(port, a);
        if (v != 0) return v;
      }
      // Clavier : les flèches servent aussi de stick gauche (jeux Nintendo 64, par exemple).
      if (port == 0 && left) {
        const Uint8* keys = SDL_GetKeyboardState(nullptr);
        int v = x ? (keys[SDL_SCANCODE_RIGHT] - keys[SDL_SCANCODE_LEFT]) : (keys[SDL_SCANCODE_DOWN] - keys[SDL_SCANCODE_UP]);
        return (int16_t)(v * 0x7FFF);
      }
      return 0;
    }

    default:
      return 0;
  }
}

bool Input::rumble(unsigned port, retro_rumble_effect effect, uint16_t strength) {
  if (port >= (unsigned)kPorts || !pads_[port]) return false;
  (effect == RETRO_RUMBLE_STRONG ? rumbleStrong_ : rumbleWeak_)[port] = strength;
  return SDL_GameControllerRumble(pads_[port], rumbleStrong_[port], rumbleWeak_[port], 5000) == 0;
}

bool Input::menuCombo() const {
  for (auto* pad : pads_) {
    if (pad && SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_START) &&
        SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_BACK)) {
      return true;
    }
  }
  return false;
}
