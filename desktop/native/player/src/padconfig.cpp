#include "padconfig.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <map>
#include <utility>

#include "core.h"
#include "libretro.h"
#include "util.h"

static const uint32_t kText = rgba(235, 235, 245);
static const uint32_t kMuted = rgba(160, 160, 180);
static const uint32_t kAccent = rgba(140, 130, 255);
static const uint32_t kSelection = rgba(91, 79, 224, 230);
static const uint32_t kDone = rgba(70, 160, 110, 230);
static const uint32_t kError = rgba(255, 140, 140);

/** Préfixe des réglages : « romcloud_pad_<GUID> » = correspondance SDL complète. */
static const char* kSettingPrefix = "romcloud_pad_";

/** Correspondances de SDL avant celles mémorisées (GUID -> correspondance) : rétablies par « défaut ». */
static std::map<std::string, std::string> originalMappings;

static std::string guidOf(SDL_Joystick* joystick) {
  char text[64] = {};
  SDL_JoystickGetGUIDString(SDL_JoystickGetGUID(joystick), text, sizeof(text));
  return text;
}

static std::vector<std::string> split(const std::string& s, char separator) {
  std::vector<std::string> parts;
  size_t start = 0;
  while (start <= s.size()) {
    size_t end = s.find(separator, start);
    if (end == std::string::npos) end = s.size();
    parts.push_back(s.substr(start, end - start));
    start = end + 1;
  }
  return parts;
}

/** Correspondance SDL actuelle de la manette [index] (vide si SDL ne la connaît pas). */
static std::string mappingForDevice(int index) {
  char* mapping = SDL_GameControllerMappingForDeviceIndex(index);
  std::string result = mapping ? mapping : "";
  SDL_free(mapping);
  return result;
}

void applySavedPadMappings() {
  for (const auto& [key, mapping] : g.options.settings(kSettingPrefix)) {
    std::string guid = key.substr(strlen(kSettingPrefix));
    if (!originalMappings.count(guid)) {
      char* original = SDL_GameControllerMappingForGUID(SDL_JoystickGetGUIDFromString(guid.c_str()));
      originalMappings[guid] = original ? original : "";
      SDL_free(original);
    }
    if (SDL_GameControllerAddMapping(mapping.c_str()) < 0) logf("Manette %s : correspondance refusée (%s)", guid.c_str(), SDL_GetError());
  }
}

// Bouton du RetroPad -> élément de la manette SDL (disposition Xbox : A en bas), inverse de
// padButton (input.cpp) ; L2 / R2 : gâchettes.
static const char* sdlElement(const PadStep& step) {
  if (step.stick) return step.stick == 'x' ? "rightx" : "righty";
  switch (step.id) {
    case RETRO_DEVICE_ID_JOYPAD_UP: return "dpup";
    case RETRO_DEVICE_ID_JOYPAD_DOWN: return "dpdown";
    case RETRO_DEVICE_ID_JOYPAD_LEFT: return "dpleft";
    case RETRO_DEVICE_ID_JOYPAD_RIGHT: return "dpright";
    case RETRO_DEVICE_ID_JOYPAD_B: return "a";
    case RETRO_DEVICE_ID_JOYPAD_A: return "b";
    case RETRO_DEVICE_ID_JOYPAD_Y: return "x";
    case RETRO_DEVICE_ID_JOYPAD_X: return "y";
    case RETRO_DEVICE_ID_JOYPAD_L: return "leftshoulder";
    case RETRO_DEVICE_ID_JOYPAD_R: return "rightshoulder";
    case RETRO_DEVICE_ID_JOYPAD_L2: return "lefttrigger";
    case RETRO_DEVICE_ID_JOYPAD_R2: return "righttrigger";
    case RETRO_DEVICE_ID_JOYPAD_L3: return "leftstick";
    case RETRO_DEVICE_ID_JOYPAD_R3: return "rightstick";
    case RETRO_DEVICE_ID_JOYPAD_START: return "start";
    case RETRO_DEVICE_ID_JOYPAD_SELECT: return "back";
    default: return "";
  }
}

/** Numéro d'axe d'une source (« a2 », « +a2 », « a2~ ») ; -1 pour un bouton ou une croix. */
static int axisOf(const std::string& source) {
  size_t a = source.find('a');
  if (a == std::string::npos || a > 1) return -1;
  return atoi(source.c_str() + a + 1);
}

PadConfig::PadConfig(const std::string& buttons, Translate tr) : tr_(std::move(tr)) {
  // Boutons de la console et leur nom ; liste vide : tous ceux du RetroPad.
  std::map<std::string, std::string> labels;
  std::vector<std::string> names;
  for (const auto& entry : split(buttons, ',')) {
    if (entry.empty()) continue;
    size_t eq = entry.find('=');
    std::string name = entry.substr(0, eq);
    names.push_back(name);
    if (eq != std::string::npos) labels[name] = entry.substr(eq + 1);
  }
  auto has = [&](const std::string& name) { return names.empty() || std::find(names.begin(), names.end(), name) != names.end(); };
  // Ordre d'Android : croix, boutons (rangée du bas d'abord), tranches, Select / Start, sticks.
  static const std::pair<unsigned, const char*> order[] = {
      {RETRO_DEVICE_ID_JOYPAD_UP, "up"},         {RETRO_DEVICE_ID_JOYPAD_DOWN, "down"},
      {RETRO_DEVICE_ID_JOYPAD_LEFT, "left"},     {RETRO_DEVICE_ID_JOYPAD_RIGHT, "right"},
      {RETRO_DEVICE_ID_JOYPAD_B, "b"},           {RETRO_DEVICE_ID_JOYPAD_A, "a"},
      {RETRO_DEVICE_ID_JOYPAD_Y, "y"},           {RETRO_DEVICE_ID_JOYPAD_X, "x"},
      {RETRO_DEVICE_ID_JOYPAD_L, "l"},           {RETRO_DEVICE_ID_JOYPAD_R, "r"},
      {RETRO_DEVICE_ID_JOYPAD_L2, "l2"},         {RETRO_DEVICE_ID_JOYPAD_R2, "r2"},
      {RETRO_DEVICE_ID_JOYPAD_SELECT, "select"}, {RETRO_DEVICE_ID_JOYPAD_START, "start"},
      {RETRO_DEVICE_ID_JOYPAD_L3, "l3"},         {RETRO_DEVICE_ID_JOYPAD_R3, "r3"},
  };
  for (const auto& [id, name] : order) {
    if (!has(name)) continue;
    std::string label = labels[name];
    if (label.empty()) {
      std::string key = std::string("btn_") + name;
      label = id <= RETRO_DEVICE_ID_JOYPAD_RIGHT && id >= RETRO_DEVICE_ID_JOYPAD_UP ? tr_(key.c_str()) : name;
      if (label == "start") label = "Start";
      else if (label == "select") label = "Select";
      else if (label == name) std::transform(label.begin(), label.end(), label.begin(), ::toupper);
    }
    steps_.push_back({id, 0, label});
  }
  if (names.empty() || has("rx")) steps_.push_back({0, 'x', labels.count("rx") ? labels["rx"] : tr_("pad_right_x")});
  if (names.empty() || has("ry")) steps_.push_back({0, 'y', labels.count("ry") ? labels["ry"] : tr_("pad_right_y")});
  assigned_.resize(steps_.size());

  // Toutes les manettes, connues de SDL ou non : la première utilisée est configurée.
  for (int i = 0; i < SDL_NumJoysticks(); i++) {
    if (SDL_Joystick* joystick = SDL_JoystickOpen(i)) opened_.push_back(joystick);
  }
}

PadConfig::~PadConfig() {
  for (auto* joystick : opened_) SDL_JoystickClose(joystick);
}

bool PadConfig::handleEvent(const SDL_Event& e) {
  if (finished_) return false;
  switch (e.type) {
    case SDL_KEYDOWN:
      if (e.key.repeat) return true;
      if (e.key.keysym.sym == SDLK_ESCAPE) finish(nullptr);
      else if (joystick_ && e.key.keysym.sym == SDLK_TAB) next();
      else if (joystick_ && e.key.keysym.sym == SDLK_DELETE) resetDevice();
      return true;
    case SDL_KEYUP:
    case SDL_TEXTINPUT:
    case SDL_CONTROLLERBUTTONDOWN:
    case SDL_CONTROLLERBUTTONUP:
    case SDL_CONTROLLERAXISMOTION:
      return true;
    case SDL_JOYDEVICEADDED:
      if (SDL_Joystick* joystick = SDL_JoystickOpen(e.jdevice.which)) opened_.push_back(joystick);
      return false;  // aussi pour Input (manettes connues de SDL)
    case SDL_JOYDEVICEREMOVED:
      if (joystick_ && SDL_JoystickInstanceID(joystick_) == e.jdevice.which) finish(nullptr);
      return false;
    case SDL_JOYBUTTONDOWN:
    case SDL_JOYBUTTONUP:
      if (!joystick_) {
        if (e.type == SDL_JOYBUTTONUP) lock(e.jbutton.which);
      } else if (SDL_JoystickInstanceID(joystick_) == e.jbutton.which) {
        onButton(e.jbutton.button, e.type == SDL_JOYBUTTONDOWN);
      }
      return true;
    case SDL_JOYHATMOTION:
      if (joystick_ && SDL_JoystickInstanceID(joystick_) == e.jhat.which) onHat(e.jhat.hat, e.jhat.value);
      return true;
    case SDL_JOYAXISMOTION:
      if (joystick_ && SDL_JoystickInstanceID(joystick_) == e.jaxis.which) onAxes();
      return true;
    default:
      return false;
  }
}

void PadConfig::lock(SDL_JoystickID id) {
  joystick_ = SDL_JoystickFromInstanceID(id);
  if (!joystick_) return;
  const char* name = SDL_JoystickName(joystick_);
  deviceName_ = name ? name : "?";
  // Position de repos de chaque axe (certaines gâchettes reposent à -1).
  int axes = SDL_JoystickNumAxes(joystick_);
  rest_.assign((size_t)std::max(0, axes), 0.0f);
  for (int i = 0; i < axes; i++) {
    Sint16 state = 0;
    if (!SDL_JoystickGetAxisInitialState(joystick_, i, &state)) state = SDL_JoystickGetAxis(joystick_, i);
    rest_[(size_t)i] = std::clamp(std::round(state / 32767.0f), -1.0f, 1.0f);
  }
  // Stick gauche : celui de la correspondance de SDL, sinon les deux premiers axes.
  leftStick_ = {0, 1};
  for (int i = 0; i < SDL_NumJoysticks(); i++) {
    if (SDL_JoystickGetDeviceInstanceID(i) != id) continue;
    std::set<int> found;
    for (const auto& bind : split(mappingForDevice(i), ',')) {
      if (bind.rfind("leftx:", 0) == 0 || bind.rfind("lefty:", 0) == 0) {
        int axis = axisOf(bind.substr(6));
        if (axis >= 0) found.insert(axis);
      }
    }
    if (!found.empty()) leftStick_ = found;
  }
  step_ = 0;
}

float PadConfig::delta(int axis) const {
  float rest = axis < (int)rest_.size() ? rest_[(size_t)axis] : 0.0f;
  return SDL_JoystickGetAxis(joystick_, axis) / 32767.0f - rest;
}

bool PadConfig::usesAxis(int axis) const {
  if (leftStick_.count(axis)) return true;
  for (const auto& source : assigned_) {
    if (axisOf(source) == axis) return true;
  }
  return false;
}

void PadConfig::onButton(int button, bool down) {
  if (down) {
    held_.insert(button);
    return;
  }
  held_.erase(button);
  if (ignoredReleases_.erase(button)) return;
  if (step_ >= steps_.size()) return;
  std::string source = "b" + std::to_string(button);
  if (source == lastButton_) {
    next();
  } else if (steps_[step_].stick) {
    // Stick : seul un axe convient.
  } else if (std::find(assigned_.begin(), assigned_.end(), source) != assigned_.end()) {
    message_ = tr_("pad_already_used");
  } else {
    assign(source);
    lastButton_ = source;
    settling_ = true;
    next();
  }
}

void PadConfig::onHat(int hat, Uint8 value) {
  if (step_ >= steps_.size() || steps_[step_].stick) return;
  if (value != SDL_HAT_CENTERED) {
    // Une seule direction (pas les diagonales) : validée quand la croix revient au centre.
    if (candidateHat_ < 0 && (value == SDL_HAT_UP || value == SDL_HAT_RIGHT || value == SDL_HAT_DOWN || value == SDL_HAT_LEFT)) {
      candidateHat_ = hat;
      candidateHatValue_ = value;
    }
    return;
  }
  if (candidateHat_ != hat) return;
  std::string source = "h" + std::to_string(hat) + "." + std::to_string((int)candidateHatValue_);
  candidateHat_ = -1;
  if (std::find(assigned_.begin(), assigned_.end(), source) != assigned_.end()) {
    message_ = tr_("pad_already_used");
    return;
  }
  assign(source);
  lastButton_.clear();
  next();
}

void PadConfig::onAxes() {
  if (step_ >= steps_.size()) return;
  const int axes = SDL_JoystickNumAxes(joystick_);
  if (settling_) {
    bool still = true;
    for (int i = 0; i < axes; i++) still = still && std::fabs(delta(i)) < 0.3f;
    if (still) settling_ = false;
    return;
  }
  if (candidateAxis_ < 0) {
    int best = -1;
    for (int i = 0; i < axes; i++) {
      if (!usesAxis(i) && (best < 0 || std::fabs(delta(i)) > std::fabs(delta(best)))) best = i;
    }
    if (best >= 0 && std::fabs(delta(best)) > 0.6f) {
      candidateAxis_ = best;
      candidateDirection_ = delta(best) > 0 ? 1.0f : -1.0f;
    }
    return;
  }
  if (std::fabs(delta(candidateAxis_)) >= 0.3f) return;
  // Axe revenu au repos : attribué. Stick (poussé vers la droite ou le bas) : axe entier, inversé
  // s'il diminue ; bouton ou gâchette : moitié de l'axe, ou axe entier s'il repose à une extrémité.
  const int axis = candidateAxis_;
  const float rest = axis < (int)rest_.size() ? rest_[(size_t)axis] : 0.0f;
  const std::string n = std::to_string(axis);
  std::string source;
  if (steps_[step_].stick) source = "a" + n + (candidateDirection_ < 0 ? "~" : "");
  else if (rest < 0 && candidateDirection_ > 0) source = "a" + n;
  else if (rest > 0 && candidateDirection_ < 0) source = "a" + n + "~";
  else source = (candidateDirection_ > 0 ? "+a" : "-a") + n;
  candidateAxis_ = -1;
  assign(source);
  // Certaines manettes envoient aussi un bouton pour la gâchette : son relâchement ne compte pas.
  ignoredReleases_.insert(held_.begin(), held_.end());
  lastButton_.clear();
  next();
}

void PadConfig::assign(const std::string& source) {
  if (step_ < assigned_.size()) assigned_[step_] = source;
}

void PadConfig::next() {
  candidateAxis_ = -1;
  candidateHat_ = -1;
  message_.clear();
  if (++step_ < steps_.size()) return;
  save();
}

void PadConfig::save() {
  if (!joystick_) return finish(nullptr);
  const std::string guid = guidOf(joystick_);
  // Correspondance de départ : celle de SDL (ou déjà configurée) ; les éléments configurés et les
  // boutons ou axes réattribués en sont retirés, le reste est gardé (étapes passées, stick gauche).
  std::string base;
  for (int i = 0; i < SDL_NumJoysticks(); i++) {
    if (SDL_JoystickGetDeviceInstanceID(i) == SDL_JoystickInstanceID(joystick_)) base = mappingForDevice(i);
  }
  std::vector<std::string> fields = split(base, ',');
  std::string name = fields.size() > 1 && !fields[1].empty() ? fields[1] : deviceName_;
  name.erase(std::remove(name.begin(), name.end(), ','), name.end());

  std::vector<std::string> binds;
  for (size_t i = 0; i < steps_.size(); i++) {
    if (!assigned_[i].empty()) binds.push_back(std::string(sdlElement(steps_[i])) + ":" + assigned_[i]);
  }
  auto taken = [&](const std::string& element, const std::string& source) {
    for (size_t i = 0; i < steps_.size(); i++) {
      if (assigned_[i].empty()) continue;
      if (element == sdlElement(steps_[i])) return true;
      if (source == assigned_[i] || (axisOf(source) >= 0 && axisOf(source) == axisOf(assigned_[i]))) return true;
    }
    return false;
  };
  bool hasLeftX = false, hasLeftY = false, hasPlatform = false;
  for (size_t i = 2; i < fields.size(); i++) {
    size_t colon = fields[i].find(':');
    if (colon == std::string::npos) continue;
    std::string element = fields[i].substr(0, colon), source = fields[i].substr(colon + 1);
    if (element == "platform") {
      hasPlatform = true;
    } else if (taken(element, source)) {
      continue;
    }
    hasLeftX = hasLeftX || element == "leftx";
    hasLeftY = hasLeftY || element == "lefty";
    binds.push_back(fields[i]);
  }
  if (!hasLeftX && !taken("leftx", "a0")) binds.push_back("leftx:a0");
  if (!hasLeftY && !taken("lefty", "a1")) binds.push_back("lefty:a1");
  if (!hasPlatform) binds.push_back("platform:Windows");

  std::string mapping = guid + "," + name + ",";
  for (const auto& bind : binds) mapping += bind + ",";
  if (!originalMappings.count(guid)) originalMappings[guid] = base;
  if (SDL_GameControllerAddMapping(mapping.c_str()) < 0) {
    logf("Manette %s : correspondance refusée (%s) : %s", guid.c_str(), SDL_GetError(), mapping.c_str());
    message_ = tr_("pad_failed");
    step_ = steps_.size() - 1;
    return;
  }
  g.options.setSetting(kSettingPrefix + guid, mapping);
  logf("Manette %s configurée : %s", guid.c_str(), mapping.c_str());
  finish("pad_saved");
}

void PadConfig::resetDevice() {
  const std::string guid = guidOf(joystick_);
  g.options.removeSetting(kSettingPrefix + guid);
  auto original = originalMappings.find(guid);
  if (original != originalMappings.end() && !original->second.empty()) SDL_GameControllerAddMapping(original->second.c_str());
  finish("pad_reset");
}

void PadConfig::finish(const char* message) {
  finished_ = true;
  result_ = message ? tr_(message) : "";
}

// ---------------------------------------------------------------------------
// Affichage : manette de la console, consignes en dessous, récapitulatif des attributions à droite.

/** Texte coupé en lignes de [width] pixels au plus (mots entiers). */
static std::vector<std::string> wrap(const std::string& text, int scale, int width) {
  std::vector<std::string> lines;
  std::string line;
  for (const auto& word : split(text, ' ')) {
    std::string candidate = line.empty() ? word : line + " " + word;
    if (!line.empty() && Canvas::measure(candidate, scale) > width) {
      lines.push_back(line);
      line = word;
    } else {
      line = candidate;
    }
  }
  if (!line.empty()) lines.push_back(line);
  return lines;
}

static void centered(Canvas& c, int cx, int y, const std::string& text, int scale, uint32_t color) {
  c.text(cx - Canvas::measure(text, scale) / 2, y, text, scale, color);
}

std::string PadConfig::sourceLabel(const std::string& source) const {
  if (source.empty()) return "-";
  if (source[0] == 'b') return tr_("pad_src_button") + " " + std::to_string(atoi(source.c_str() + 1) + 1);
  if (source[0] == 'h') {
    int value = atoi(source.c_str() + source.find('.') + 1);
    const char* direction = value == SDL_HAT_UP ? "btn_up" : value == SDL_HAT_DOWN ? "btn_down" : value == SDL_HAT_LEFT ? "btn_left" : "btn_right";
    return tr_("pad_src_hat") + " " + tr_(direction);
  }
  std::string label = tr_("pad_src_axis") + " " + std::to_string(axisOf(source) + 1);
  if (source[0] == '+') label += " +";
  else if (source[0] == '-') label += " -";
  else if (source.back() == '~') label += " " + tr_("pad_src_inverted");
  return label;
}

void PadConfig::renderPad(Canvas& c, int x, int y, int w) const {
  const bool locked = joystick_ != nullptr;
  // Étape d'un bouton (ou du stick droit) : en cours, attribuée, ou -1 si absente de la console.
  auto stepOf = [&](unsigned id, char stick) {
    for (size_t i = 0; i < steps_.size(); i++) {
      if (steps_[i].stick == stick && (stick || steps_[i].id == id)) return (int)i;
    }
    return -1;
  };
  auto key = [&](int i, int kx, int ky, int kw, int kh, const std::string& label) {
    if (i < 0) return;
    bool current = locked && i == (int)step_;
    bool done = !assigned_[(size_t)i].empty();
    c.fill(kx, ky, kw, kh, current ? kSelection : done ? kDone : rgba(255, 255, 255, 30));
    std::string text = Canvas::fit(label, 1, kw - 4);
    c.text(kx + (kw - Canvas::measure(text, 1)) / 2, ky + (kh - 8) / 2, text, 1, current || done ? rgba(255, 255, 255) : kText);
  };
  auto button = [&](unsigned id, int kx, int ky, int kw, int kh) {
    int i = stepOf(id, 0);
    key(i, kx, ky, kw, kh, i >= 0 ? steps_[(size_t)i].label : "");
  };
  // Tranches.
  button(RETRO_DEVICE_ID_JOYPAD_L2, x + 8, y, 52, 16);
  button(RETRO_DEVICE_ID_JOYPAD_L, x + 64, y, 52, 16);
  button(RETRO_DEVICE_ID_JOYPAD_R, x + w - 116, y, 52, 16);
  button(RETRO_DEVICE_ID_JOYPAD_R2, x + w - 60, y, 52, 16);
  // Corps de la manette.
  const int top = y + 22, height = 118;
  c.fill(x, top, w, height, rgba(255, 255, 255, 14));
  c.fill(x, top, w, 1, rgba(255, 255, 255, 70));
  c.fill(x, top + height - 1, w, 1, rgba(255, 255, 255, 70));
  c.fill(x, top, 1, height, rgba(255, 255, 255, 70));
  c.fill(x + w - 1, top, 1, height, rgba(255, 255, 255, 70));
  // Croix.
  const int dx = x + 62, dy = top + 46, s = 20;
  c.fill(dx - s / 2, dy - s / 2, s, s, rgba(255, 255, 255, 30));
  key(stepOf(RETRO_DEVICE_ID_JOYPAD_UP, 0), dx - s / 2, dy - s / 2 - s, s, s, "");
  key(stepOf(RETRO_DEVICE_ID_JOYPAD_DOWN, 0), dx - s / 2, dy + s / 2, s, s, "");
  key(stepOf(RETRO_DEVICE_ID_JOYPAD_LEFT, 0), dx - s / 2 - s, dy - s / 2, s, s, "");
  key(stepOf(RETRO_DEVICE_ID_JOYPAD_RIGHT, 0), dx + s / 2, dy - s / 2, s, s, "");
  // Boutons (disposition du RetroPad : X en haut, Y à gauche, A à droite, B en bas).
  const int fx = x + w - 70, fy = top + 46;
  button(RETRO_DEVICE_ID_JOYPAD_X, fx - 24, fy - 30, 48, 16);
  button(RETRO_DEVICE_ID_JOYPAD_Y, fx - 54, fy - 8, 48, 16);
  button(RETRO_DEVICE_ID_JOYPAD_A, fx + 6, fy - 8, 48, 16);
  button(RETRO_DEVICE_ID_JOYPAD_B, fx - 24, fy + 14, 48, 16);
  // Select / Start.
  const int cx = x + w / 2;
  button(RETRO_DEVICE_ID_JOYPAD_SELECT, cx - 52, top + 22, 48, 14);
  button(RETRO_DEVICE_ID_JOYPAD_START, cx + 4, top + 22, 48, 14);
  // Sticks : L3, stick droit (un seul repère pour ses deux axes), R3.
  button(RETRO_DEVICE_ID_JOYPAD_L3, cx - 98, top + 86, 40, 16);
  button(RETRO_DEVICE_ID_JOYPAD_R3, cx + 58, top + 86, 40, 16);
  int stick = locked && step_ < steps_.size() && steps_[step_].stick ? (int)step_ : stepOf(0, 'x');
  if (stick < 0) stick = stepOf(0, 'y');
  key(stick, cx - 50, top + 86, 100, 16, tr_("pad_stick"));
}

void PadConfig::render(Canvas& c) const {
  c.fill(0, 0, c.width(), c.height(), rgba(8, 9, 14, 215));
  const int summaryWidth = std::clamp(c.width() * 3 / 10, 190, 300), gap = 24;
  const int left = 24, right = c.width() - summaryWidth - gap * 2;
  const int cx = (left + right) / 2;

  // Manette, puis consignes en dessous.
  int y = 18;
  // Titre en grand s'il tient, sinon en petit (fenêtre étroite).
  const std::string title = tr_("pad_title");
  const int titleScale = Canvas::measure(title, 2) <= right - left ? 2 : 1;
  centered(c, cx, y + (titleScale == 1 ? 4 : 0), Canvas::fit(title, titleScale, right - left), titleScale, kText);
  y += 30;
  const int padWidth = std::min(340, right - left);
  renderPad(c, cx - padWidth / 2, y, padWidth);
  y += 156;
  const int textWidth = right - left;
  if (!joystick_) {
    for (const auto& line : wrap(tr_("pad_press_any"), 1, textWidth)) {
      centered(c, cx, y, line, 1, kText);
      y += 12;
    }
  } else {
    centered(c, cx, y, Canvas::fit(deviceName_, 1, textWidth), 1, kMuted);
    y += 16;
    if (step_ < steps_.size()) {
      const PadStep& step = steps_[step_];
      centered(c, cx, y, tr_("pad_step") + " " + std::to_string(step_ + 1) + " / " + std::to_string(steps_.size()), 1, kText);
      y += 16;
      centered(c, cx, y, Canvas::fit(step.label, 2, textWidth), 2, kAccent);
      y += 26;
      const char* hint = step.stick == 'x' ? "pad_hint_stick_x" : step.stick == 'y' ? "pad_hint_stick_y" : "pad_hint";
      for (const auto& line : wrap(tr_(hint), 1, textWidth)) {
        centered(c, cx, y, line, 1, kText);
        y += 12;
      }
    }
    if (!message_.empty()) centered(c, cx, y + 6, Canvas::fit(message_, 1, textWidth), 1, kError);
  }

  // Récapitulatif : chaque commande de la console et ce qui lui est attribué.
  const int sx = c.width() - summaryWidth - gap;
  int sy = 18;
  c.text(sx, sy, Canvas::fit(tr_("pad_summary"), 2, summaryWidth), 2, kText);
  sy += 28;
  const int lineHeight = 14, bottom = c.height() - 30;
  // Défilement : l'étape en cours reste visible.
  int visible = std::max(1, (bottom - sy) / lineHeight);
  int first = 0;
  if ((int)steps_.size() > visible) first = std::clamp((int)step_ - visible / 2, 0, (int)steps_.size() - visible);
  for (int i = first; i < (int)steps_.size() && i < first + visible; i++) {
    bool current = joystick_ && i == (int)step_;
    if (current) c.fill(sx - 6, sy - 3, summaryWidth + 12, lineHeight, kSelection);
    std::string value = sourceLabel(assigned_[(size_t)i]);
    int valueWidth = Canvas::measure(value, 1);
    c.text(sx + summaryWidth - valueWidth, sy, value, 1, current ? rgba(255, 255, 255) : assigned_[(size_t)i].empty() ? kMuted : kAccent);
    c.text(sx, sy, Canvas::fit(steps_[(size_t)i].label, 1, summaryWidth - valueWidth - 12), 1, current ? rgba(255, 255, 255) : kText);
    sy += lineHeight;
  }

  std::string keys = tr_(joystick_ ? "pad_keys" : "pad_keys_cancel");
  centered(c, c.width() / 2, c.height() - 16, keys, 1, kMuted);
}
