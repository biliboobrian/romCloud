#include "input.h"

#include <cstdlib>
#include <utility>
#include <vector>

#include "util.h"

// Clavier du joueur 1 par défaut (disposition proche de RetroArch), comme DEFAULTS de keyboard.js.
static const std::pair<unsigned, SDL_Scancode> kDefaultKeys[] = {
    {RETRO_DEVICE_ID_JOYPAD_UP, SDL_SCANCODE_UP},       {RETRO_DEVICE_ID_JOYPAD_DOWN, SDL_SCANCODE_DOWN},
    {RETRO_DEVICE_ID_JOYPAD_LEFT, SDL_SCANCODE_LEFT},   {RETRO_DEVICE_ID_JOYPAD_RIGHT, SDL_SCANCODE_RIGHT},
    {RETRO_DEVICE_ID_JOYPAD_B, SDL_SCANCODE_Z},         {RETRO_DEVICE_ID_JOYPAD_A, SDL_SCANCODE_X},
    {RETRO_DEVICE_ID_JOYPAD_Y, SDL_SCANCODE_A},         {RETRO_DEVICE_ID_JOYPAD_X, SDL_SCANCODE_S},
    {RETRO_DEVICE_ID_JOYPAD_L, SDL_SCANCODE_Q},         {RETRO_DEVICE_ID_JOYPAD_R, SDL_SCANCODE_W},
    {RETRO_DEVICE_ID_JOYPAD_L2, SDL_SCANCODE_E},        {RETRO_DEVICE_ID_JOYPAD_R2, SDL_SCANCODE_R},
    {RETRO_DEVICE_ID_JOYPAD_START, SDL_SCANCODE_RETURN}, {RETRO_DEVICE_ID_JOYPAD_SELECT, SDL_SCANCODE_RSHIFT},
};

const unsigned Input::kButtonOrder[Input::kButtons] = {
    RETRO_DEVICE_ID_JOYPAD_UP, RETRO_DEVICE_ID_JOYPAD_DOWN, RETRO_DEVICE_ID_JOYPAD_LEFT, RETRO_DEVICE_ID_JOYPAD_RIGHT,
    RETRO_DEVICE_ID_JOYPAD_A,  RETRO_DEVICE_ID_JOYPAD_B,    RETRO_DEVICE_ID_JOYPAD_X,    RETRO_DEVICE_ID_JOYPAD_Y,
    RETRO_DEVICE_ID_JOYPAD_L,  RETRO_DEVICE_ID_JOYPAD_R,    RETRO_DEVICE_ID_JOYPAD_L2,   RETRO_DEVICE_ID_JOYPAD_R2,
    RETRO_DEVICE_ID_JOYPAD_L3, RETRO_DEVICE_ID_JOYPAD_R3,   RETRO_DEVICE_ID_JOYPAD_START, RETRO_DEVICE_ID_JOYPAD_SELECT,
};

const char* Input::buttonName(unsigned id) {
  static const char* names[kButtons] = {"b", "y", "select", "start", "up", "down", "left", "right",
                                        "a", "x", "l", "r", "l2", "r2", "l3", "r3"};
  return id < (unsigned)kButtons ? names[id] : "";
}

bool Input::assignable(SDL_Scancode code) {
  // Touches de keyboard.js (SCANCODES) ; Échap, F1, F2, F4 et F11 commandent le moteur.
  bool known = (code >= SDL_SCANCODE_A && code <= SDL_SCANCODE_APPLICATION && code != SDL_SCANCODE_NONUSHASH) ||
               (code >= SDL_SCANCODE_LCTRL && code <= SDL_SCANCODE_RGUI);
  return known && code != SDL_SCANCODE_ESCAPE && code != SDL_SCANCODE_F1 && code != SDL_SCANCODE_F2 &&
         code != SDL_SCANCODE_F4 && code != SDL_SCANCODE_F11;
}

void Input::resetKeys() {
  for (auto& k : keys_) k = SDL_SCANCODE_UNKNOWN;
  for (const auto& [id, code] : kDefaultKeys) keys_[id] = code;
}

void Input::loadKeys(const std::string& file) {
  resetKeys();
  keysFile_ = file;
  std::vector<uint8_t> data;
  if (file.empty() || !readFile(file, data)) return;
  std::string text(data.begin(), data.end());
  size_t start = 0;
  while (start < text.size()) {
    size_t end = text.find('\n', start);
    if (end == std::string::npos) end = text.size();
    std::string line = text.substr(start, end - start);
    start = end + 1;
    if (!line.empty() && line.back() == '\r') line.pop_back();
    size_t eq = line.find('=');
    if (eq == std::string::npos) continue;
    std::string name = line.substr(0, eq), value = line.substr(eq + 1);
    for (unsigned id = 0; id < (unsigned)kButtons; id++) {
      if (name != buttonName(id)) continue;
      auto code = (SDL_Scancode)atoi(value.c_str());
      if (value.empty()) keys_[id] = SDL_SCANCODE_UNKNOWN;
      else if (assignable(code)) keys_[id] = code;
    }
  }
  logf("Clavier : %s", file.c_str());
}

bool Input::saveKeys() const {
  if (keysFile_.empty()) return false;
  std::string text;
  for (unsigned id : kButtonOrder) {
    text += std::string(buttonName(id)) + "=" + (keys_[id] ? std::to_string((int)keys_[id]) : "") + "\n";
  }
  return writeFile(keysFile_, text.data(), text.size());
}

void Input::assignKey(unsigned id, SDL_Scancode code) {
  if (id >= (unsigned)kButtons) return;
  if (code != SDL_SCANCODE_UNKNOWN) {
    if (!assignable(code)) return;
    for (auto& k : keys_) {
      if (k == code) k = SDL_SCANCODE_UNKNOWN;  // une touche ne commande qu'un bouton
    }
  }
  keys_[id] = code;
  saveKeys();
}

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

Input::Input() { resetKeys(); }

bool Input::key(unsigned id) const {
  SDL_Scancode code = keyOf(id);
  return code != SDL_SCANCODE_UNKNOWN && SDL_GetKeyboardState(nullptr)[code];
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
  if (port == 0 && key(id)) return true;
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
      // Clavier : les touches de direction servent aussi de stick gauche (jeux Nintendo 64, par exemple).
      if (port == 0 && left) {
        int v = x ? key(RETRO_DEVICE_ID_JOYPAD_RIGHT) - key(RETRO_DEVICE_ID_JOYPAD_LEFT)
                  : key(RETRO_DEVICE_ID_JOYPAD_DOWN) - key(RETRO_DEVICE_ID_JOYPAD_UP);
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
