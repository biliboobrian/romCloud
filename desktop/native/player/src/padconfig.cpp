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
static const uint32_t kError = rgba(255, 140, 140);

/** Préfixe des réglages : « romcloud_pad_<GUID> » = correspondance SDL complète. */
static const char* kSettingPrefix = "romcloud_pad_";

/** Correspondances de SDL avant celles mémorisées (GUID -> correspondance) : rétablies par « défaut ». */
static std::map<std::string, std::string> originalMappings;

static std::string guidOf(SDL_Joystick* joystick) {
  char text[64] = {};
  SDL_GUIDToString(SDL_GetJoystickGUID(joystick), text, sizeof(text));
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

/** Correspondance SDL actuelle de la manette [id] (vide si SDL ne la connaît pas). */
static std::string mappingFor(SDL_JoystickID id) {
  char* mapping = SDL_GetGamepadMappingForID(id);
  std::string result = mapping ? mapping : "";
  SDL_free(mapping);
  return result;
}

void applySavedPadMappings() {
  for (const auto& [key, mapping] : g.options.settings(kSettingPrefix)) {
    std::string guid = key.substr(strlen(kSettingPrefix));
    if (!originalMappings.count(guid)) {
      char* original = SDL_GetGamepadMappingForGUID(SDL_StringToGUID(guid.c_str()));
      originalMappings[guid] = original ? original : "";
      SDL_free(original);
    }
    if (SDL_AddGamepadMapping(mapping.c_str()) < 0) logf("Manette %s : correspondance refusée (%s)", guid.c_str(), SDL_GetError());
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

PadConfig::PadConfig(const std::string& buttons, const std::string& style, Translate tr)
    : tr_(std::move(tr)), style_(style) {
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
  int count = 0;
  SDL_JoystickID* ids = SDL_GetJoysticks(&count);
  for (int i = 0; i < count; i++) {
    if (SDL_Joystick* joystick = SDL_OpenJoystick(ids[i])) opened_.push_back(joystick);
  }
  SDL_free(ids);
}

PadConfig::~PadConfig() {
  for (auto* joystick : opened_) SDL_CloseJoystick(joystick);
}

bool PadConfig::handleEvent(const SDL_Event& e) {
  if (finished_) return false;
  switch (e.type) {
    case SDL_EVENT_KEY_DOWN:
      if (e.key.repeat) return true;
      if (e.key.key == SDLK_ESCAPE) finish(nullptr);
      else if (joystick_ && e.key.key == SDLK_TAB) next();
      else if (joystick_ && e.key.key == SDLK_DELETE) resetDevice();
      return true;
    case SDL_EVENT_KEY_UP:
    case SDL_EVENT_TEXT_INPUT:
    case SDL_EVENT_GAMEPAD_BUTTON_DOWN:
    case SDL_EVENT_GAMEPAD_BUTTON_UP:
    case SDL_EVENT_GAMEPAD_AXIS_MOTION:
      return true;
    case SDL_EVENT_JOYSTICK_ADDED:
      if (SDL_Joystick* joystick = SDL_OpenJoystick(e.jdevice.which)) opened_.push_back(joystick);
      return false;  // aussi pour Input (manettes connues de SDL)
    case SDL_EVENT_JOYSTICK_REMOVED:
      if (joystick_ && SDL_GetJoystickID(joystick_) == e.jdevice.which) finish(nullptr);
      return false;
    case SDL_EVENT_JOYSTICK_BUTTON_DOWN:
    case SDL_EVENT_JOYSTICK_BUTTON_UP:
      if (!joystick_) {
        if (e.type == SDL_EVENT_JOYSTICK_BUTTON_UP) lock(e.jbutton.which);
      } else if (SDL_GetJoystickID(joystick_) == e.jbutton.which) {
        onButton(e.jbutton.button, e.type == SDL_EVENT_JOYSTICK_BUTTON_DOWN);
      }
      return true;
    case SDL_EVENT_JOYSTICK_HAT_MOTION:
      if (joystick_ && SDL_GetJoystickID(joystick_) == e.jhat.which) onHat(e.jhat.hat, e.jhat.value);
      return true;
    case SDL_EVENT_JOYSTICK_AXIS_MOTION:
      if (joystick_ && SDL_GetJoystickID(joystick_) == e.jaxis.which) onAxes();
      return true;
    default:
      return false;
  }
}

void PadConfig::lock(SDL_JoystickID id) {
  joystick_ = SDL_GetJoystickFromID(id);
  if (!joystick_) return;
  const char* name = SDL_GetJoystickName(joystick_);
  deviceName_ = name ? name : "?";
  // Position de repos de chaque axe (certaines gâchettes reposent à -1).
  int axes = SDL_GetNumJoystickAxes(joystick_);
  rest_.assign((size_t)std::max(0, axes), 0.0f);
  for (int i = 0; i < axes; i++) {
    Sint16 state = 0;
    if (!SDL_GetJoystickAxisInitialState(joystick_, i, &state)) state = SDL_GetJoystickAxis(joystick_, i);
    rest_[(size_t)i] = std::clamp(std::round(state / 32767.0f), -1.0f, 1.0f);
  }
  // Stick gauche : celui de la correspondance de SDL, sinon les deux premiers axes.
  leftStick_ = {0, 1};
  std::set<int> found;
  for (const auto& bind : split(mappingFor(id), ',')) {
    if (bind.rfind("leftx:", 0) == 0 || bind.rfind("lefty:", 0) == 0) {
      int axis = axisOf(bind.substr(6));
      if (axis >= 0) found.insert(axis);
    }
  }
  if (!found.empty()) leftStick_ = found;
  step_ = 0;
}

float PadConfig::delta(int axis) const {
  float rest = axis < (int)rest_.size() ? rest_[(size_t)axis] : 0.0f;
  return SDL_GetJoystickAxis(joystick_, axis) / 32767.0f - rest;
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
  const int axes = SDL_GetNumJoystickAxes(joystick_);
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
  const std::string base = mappingFor(SDL_GetJoystickID(joystick_));
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
  if (SDL_AddGamepadMapping(mapping.c_str()) < 0) {
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
  if (original != originalMappings.end() && !original->second.empty()) SDL_AddGamepadMapping(original->second.c_str());
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

// Manette de chaque console, dessinée d'après l'originale (forme, couleurs, place des boutons),
// dans une boîte de 340 x 150 unités mise à l'échelle. Chaque bouton de la configuration y porte un
// repère : anneau violet (étape en cours) ou vert (attribué).

static constexpr uint32_t kRed = rgba(206, 46, 52), kYellow = rgba(232, 190, 46), kGreen = rgba(46, 156, 80),
                          kBlue = rgba(58, 96, 200), kWhite = rgba(250, 250, 252);

/** Texte lisible sur [background] : noir sur fond clair, blanc sinon. */
static uint32_t inkOn(uint32_t background) {
  int r = background & 0xFF, gr = (background >> 8) & 0xFF, b = (background >> 16) & 0xFF;
  return r * 299 + gr * 587 + b * 114 > 150000 ? rgba(20, 20, 24) : kWhite;
}

/** Couleur éclaircie (amount > 0) ou assombrie (amount < 0), même opacité. */
static uint32_t shade(uint32_t c, float amount) {
  auto channel = [&](int shift) {
    float v = (float)((c >> shift) & 0xFF);
    v = amount > 0 ? v + (255 - v) * amount : v * (1 + amount);
    return (uint32_t)std::clamp((int)std::lround(v), 0, 255) << shift;
  };
  return channel(0) | channel(8) | channel(16) | (c & 0xFF000000u);
}

namespace {

enum class Label { Inside, Below, Above, None };
enum class Symbol { Cross, Circle, Square, Triangle };

class PadPainter {
 public:
  PadPainter(Canvas& c, float x, float y, float w, const std::vector<PadStep>& steps,
             const std::vector<std::string>& assigned, int current)
      : c_(c), x_(x), y_(y), k_(w / 340.0f), steps_(steps), assigned_(assigned), current_(current) {}

  int height() const { return (int)std::lround(150 * k_); }
  void draw(const std::string& style);

 private:
  // Coordonnées de la boîte -> canevas.
  float X(float v) const { return x_ + v * k_; }
  float Y(float v) const { return y_ + v * k_; }
  float S(float v) const { return v * k_; }

  int stepOf(unsigned id) const {
    for (size_t i = 0; i < steps_.size(); i++) {
      if (!steps_[i].stick && steps_[i].id == id) return (int)i;
    }
    return -1;
  }
  int axisStep(char axis) const {
    for (size_t i = 0; i < steps_.size(); i++) {
      if (steps_[i].stick == axis) return (int)i;
    }
    return -1;
  }
  /** Étape du stick droit à repérer : l'axe en cours, sinon l'horizontal. */
  int rightAxis() const { return isCurrent(axisStep('y')) ? axisStep('y') : axisStep('x'); }
  bool isCurrent(int i) const { return i >= 0 && i == current_; }
  bool isDone(int i) const { return i >= 0 && !assigned_[(size_t)i].empty(); }
  std::string labelOf(int i) const { return i >= 0 ? steps_[(size_t)i].label : std::string(); }

  // Formes (coordonnées de la boîte).
  void rect(float x, float y, float w, float h, float r, uint32_t color) { c_.roundRect(X(x), Y(y), S(w), S(h), S(r), color); }
  void disc(float cx, float cy, float r, uint32_t color) { c_.circle(X(cx), Y(cy), S(r), color); }
  void ring(float cx, float cy, float r, float t, uint32_t color) { c_.circleOutline(X(cx), Y(cy), S(r), std::max(1.0f, S(t)), color); }
  void line(float x0, float y0, float x1, float y1, float t, uint32_t color) {
    c_.line(X(x0), Y(y0), X(x1), Y(y1), std::max(1.0f, S(t)), color);
  }
  void text(float cx, float cy, const std::string& s, float maxWidth, uint32_t color) {
    std::string t = Canvas::fit(s, 1, std::max(8, (int)S(maxWidth)));
    c_.text((int)std::lround(X(cx) - Canvas::measure(t, 1) / 2.0f), (int)std::lround(Y(cy) - 4), t, 1, color);
  }

  /** Corps : [shape](grossissement, couleur) en liseré clair, puis bord, puis couleur du corps. */
  template <typename Shape>
  void body(Shape shape, uint32_t color, uint32_t edge) {
    shape(3.0f, rgba(255, 255, 255, 40));  // liseré : manettes noires visibles sur le fond sombre
    shape(1.5f, edge);
    shape(0.0f, color);
  }

  /** Repère d'état de l'étape [i] autour d'une forme (coordonnées de la boîte). */
  void mark(int i, float x, float y, float w, float h, float r) {
    if (isCurrent(i)) {
      c_.roundRectOutline(X(x) - 5, Y(y) - 5, S(w) + 10, S(h) + 10, S(r) + 5, 3, kAccent);
      c_.roundRect(X(x), Y(y), S(w), S(h), S(r), rgba(140, 130, 255, 100));
    } else if (isDone(i)) {
      c_.roundRectOutline(X(x) - 3, Y(y) - 3, S(w) + 6, S(h) + 6, S(r) + 3, 2, rgba(90, 210, 130));
    }
  }

  void label(int i, Label where, float cx, float cy, float halfH, float maxWidth, uint32_t ink, uint32_t outside) {
    switch (where) {
      case Label::Inside: text(cx, cy, labelOf(i), maxWidth, ink); break;
      case Label::Below: text(cx, cy + halfH + 7, labelOf(i), std::max(maxWidth, 44.0f), outside); break;
      case Label::Above: text(cx, cy - halfH - 7, labelOf(i), std::max(maxWidth, 44.0f), outside); break;
      case Label::None: break;
    }
  }

  /** Bouton rond de la console (absent de la configuration : non dessiné). */
  void button(unsigned id, float cx, float cy, float r, uint32_t color, Label where = Label::Inside,
              uint32_t outside = rgba(230, 230, 236)) {
    int i = stepOf(id);
    if (i < 0) return;
    disc(cx, cy + 1.5f, r + 1, rgba(0, 0, 0, 90));  // ombre
    disc(cx, cy, r, color);
    disc(cx - r * 0.25f, cy - r * 0.3f, r * 0.45f, shade(color, 0.2f));  // reflet
    mark(i, cx - r, cy - r, 2 * r, 2 * r, r);
    label(i, where, cx, cy, r, 2 * r + 4, inkOn(color), outside);
  }

  /** Bouton PlayStation : symbole de couleur sur un bouton sombre. */
  void symbol(unsigned id, float cx, float cy, float r, uint32_t color, Symbol shape, uint32_t ink) {
    int i = stepOf(id);
    if (i < 0) return;
    disc(cx, cy + 1.5f, r + 1, rgba(0, 0, 0, 90));
    disc(cx, cy, r, color);
    disc(cx - r * 0.25f, cy - r * 0.3f, r * 0.45f, shade(color, 0.12f));
    const float s = r * 0.45f, t = 1.8f;
    switch (shape) {
      case Symbol::Cross:
        line(cx - s, cy - s, cx + s, cy + s, t, ink);
        line(cx - s, cy + s, cx + s, cy - s, t, ink);
        break;
      case Symbol::Circle: ring(cx, cy, s + 0.8f, t, ink); break;
      case Symbol::Square:
        c_.roundRectOutline(X(cx - s), Y(cy - s), S(2 * s), S(2 * s), 1, std::max(1.0f, S(t)), ink);
        break;
      case Symbol::Triangle:
        line(cx, cy - s - 1, cx + s + 1, cy + s * 0.75f, t, ink);
        line(cx + s + 1, cy + s * 0.75f, cx - s - 1, cy + s * 0.75f, t, ink);
        line(cx - s - 1, cy + s * 0.75f, cx, cy - s - 1, t, ink);
        break;
    }
    mark(i, cx - r, cy - r, 2 * r, 2 * r, r);
  }

  /** Bouton allongé (Select, Start…) centré en ([cx], [cy]). */
  void pill(unsigned id, float cx, float cy, float w, float h, uint32_t color, Label where = Label::Inside,
            uint32_t outside = rgba(230, 230, 236)) {
    int i = stepOf(id);
    if (i < 0) return;
    const float r = h / 2;
    rect(cx - w / 2, cy - h / 2 + 1.2f, w, h, r, rgba(0, 0, 0, 90));
    rect(cx - w / 2, cy - h / 2, w, h, r, color);
    if (h >= 12) rect(cx - w / 2 + 3, cy - h / 2 + 2, w - 6, h * 0.3f, r * 0.5f, shade(color, 0.2f));
    mark(i, cx - w / 2, cy - h / 2, w, h, r);
    label(i, where, cx, cy, h / 2, w - 2, inkOn(color), outside);
  }

  /** Bouton en biais (Select / Start de la Super Nintendo et de la Game Boy). */
  void slanted(unsigned id, float x0, float y0, float x1, float y1, float t, uint32_t color, Label where,
               uint32_t outside) {
    int i = stepOf(id);
    if (i < 0) return;
    if (isCurrent(i)) line(x0, y0, x1, y1, t + 10 / k_, kAccent);
    else if (isDone(i)) line(x0, y0, x1, y1, t + 6 / k_, rgba(90, 210, 130));
    line(x0, y0 + 1, x1, y1 + 1, t, rgba(0, 0, 0, 90));
    line(x0, y0, x1, y1, t, color);
    if (isCurrent(i)) line(x0, y0, x1, y1, t, rgba(140, 130, 255, 100));
    label(i, where, (x0 + x1) / 2, (y0 + y1) / 2, std::fabs(y1 - y0) / 2 + t / 2, 50, inkOn(color), outside);
  }

  /** Tranche (vue de face, derrière le corps). */
  void shoulder(unsigned id, float x, float y, float w, float h, uint32_t color, uint32_t edge) {
    int i = stepOf(id);
    if (i < 0) return;
    rect(x - 1, y - 1, w + 2, h + 2, h / 2 + 1, edge);
    rect(x, y, w, h, h / 2, color);
    rect(x + 3, y + 2, w - 6, h * 0.28f, h * 0.2f, shade(color, 0.25f));
    mark(i, x, y, w, h, h / 2);
    text(x + w / 2, y + h * 0.42f, labelOf(i), w - 6, inkOn(color));
  }

  /** Croix directionnelle ; [separate] : quatre flèches séparées (PlayStation). */
  void dpad(float cx, float cy, float arm, float half, uint32_t color, bool separate = false) {
    struct Arm {
      unsigned id;
      float x, y, w, h;
    };
    const float gap = separate ? 3.0f : -1.0f;
    const Arm arms[] = {
        {RETRO_DEVICE_ID_JOYPAD_UP, cx - half, cy - half - arm, 2 * half, arm + half - gap},
        {RETRO_DEVICE_ID_JOYPAD_DOWN, cx - half, cy + gap, 2 * half, arm + half - gap},
        {RETRO_DEVICE_ID_JOYPAD_LEFT, cx - half - arm, cy - half, arm + half - gap, 2 * half},
        {RETRO_DEVICE_ID_JOYPAD_RIGHT, cx + gap, cy - half, arm + half - gap, 2 * half},
    };
    if (!separate) {
      rect(cx - half - 1, cy - half - arm - 1, 2 * half + 2, 2 * (half + arm) + 2, 3, shade(color, -0.45f));
      rect(cx - half - arm - 1, cy - half - 1, 2 * (half + arm) + 2, 2 * half + 2, 3, shade(color, -0.45f));
    }
    for (const auto& a : arms) {
      if (separate) rect(a.x, a.y + 1, a.w, a.h, 2.5f, rgba(0, 0, 0, 90));
      rect(a.x, a.y, a.w, a.h, separate ? 2.5f : 2.0f, color);
    }
    if (!separate) {
      disc(cx, cy, half * 0.45f, shade(color, -0.35f));
      // Flèches gravées.
      const uint32_t engraved = shade(color, 0.3f);
      line(cx, cy - half - arm * 0.6f, cx, cy - half - arm * 0.2f, 1.5f, engraved);
      line(cx, cy + half + arm * 0.2f, cx, cy + half + arm * 0.6f, 1.5f, engraved);
      line(cx - half - arm * 0.6f, cy, cx - half - arm * 0.2f, cy, 1.5f, engraved);
      line(cx + half + arm * 0.2f, cy, cx + half + arm * 0.6f, cy, 1.5f, engraved);
    }
    // Branches attribuées teintées en vert (des anneaux se croiseraient au centre).
    for (const auto& a : arms) {
      const int i = stepOf(a.id);
      if (isCurrent(i)) mark(i, a.x, a.y, a.w, a.h, 2);
      else if (isDone(i)) rect(a.x, a.y, a.w, a.h, 2, rgba(90, 210, 130, 150));
    }
  }

  /** Directions d'un joystick (arcade, Atari, Neo Geo) : quatre flèches autour, repérées. */
  void directions(float cx, float cy, float dist, uint32_t color) {
    const struct {
      unsigned id;
      float dx, dy;
    } dirs[] = {{RETRO_DEVICE_ID_JOYPAD_UP, 0, -1}, {RETRO_DEVICE_ID_JOYPAD_DOWN, 0, 1},
                {RETRO_DEVICE_ID_JOYPAD_LEFT, -1, 0}, {RETRO_DEVICE_ID_JOYPAD_RIGHT, 1, 0}};
    for (const auto& d : dirs) {
      const float px = cx + d.dx * dist, py = cy + d.dy * dist, s = 5;
      // Triangle pointant vers l'extérieur.
      const float ax = px + d.dx * s, ay = py + d.dy * s;
      const float bx = px - d.dx * s + d.dy * s, by = py - d.dy * s + d.dx * s;
      const float qx = px - d.dx * s - d.dy * s, qy = py - d.dy * s - d.dx * s;
      line(ax, ay, bx, by, 2, color);
      line(bx, by, qx, qy, 2, color);
      line(qx, qy, ax, ay, 2, color);
      mark(stepOf(d.id), px - s, py - s, 2 * s, 2 * s, s);
    }
  }

  /** Stick analogique : [step] (L3 / R3) ou [axis] (axe du stick droit) ; -1 : décor. */
  void stick(float cx, float cy, float r, uint32_t cap, uint32_t well, int step, int axis, const char* name) {
    disc(cx, cy, r + 5, well);
    disc(cx, cy + 1.5f, r + 0.5f, rgba(0, 0, 0, 100));
    disc(cx, cy, r, cap);
    ring(cx, cy, r * 0.72f, 1.6f, shade(cap, 0.18f));
    const int shown = isCurrent(axis) ? axis : step >= 0 ? step : axis;
    mark(shown, cx - r, cy - r, 2 * r, 2 * r, r);
    if (*name && step >= 0) text(cx, cy, name, 2 * r, inkOn(cap));
  }

  /** Écran d'une console portable. */
  void screen(float x, float y, float w, float h, uint32_t bezel, uint32_t glass) {
    rect(x, y, w, h, 6, bezel);
    rect(x + w * 0.14f, y + h * 0.12f, w * 0.72f, h * 0.76f, 2, glass);
    rect(x + w * 0.14f, y + h * 0.12f, w * 0.72f, h * 0.16f, 2, rgba(255, 255, 255, 16));
  }

  void defaultPad();
  void nes(uint32_t color, uint32_t panel, uint32_t strip, uint32_t buttons, uint32_t print);
  void snes();
  void n64();
  void playstation(uint32_t color, uint32_t edge, uint32_t trigger, uint32_t arrows, uint32_t print);
  void psp();
  void gameBoy();
  void gba();
  void genesis();
  void master();
  void saturn();
  void dreamcast();
  void pcEngine();
  void neoGeo();
  void ngp();
  void arcade(bool cps);
  void atari(bool proLine);
  void lynx();
  void gx4000();
  void gamecube();

  Canvas& c_;
  float x_, y_, k_;
  const std::vector<PadStep>& steps_;
  const std::vector<std::string>& assigned_;
  int current_;
};

void PadPainter::draw(const std::string& style) {
  if (style == "nes") nes(rgba(204, 204, 204), rgba(30, 30, 32), rgba(150, 150, 152), rgba(196, 30, 40), rgba(196, 30, 40));
  // Famicom : manette rouge, façade crème et bande dorée.
  else if (style == "fds") nes(rgba(176, 34, 40), rgba(226, 208, 164), rgba(196, 166, 104), rgba(150, 26, 32), rgba(140, 26, 30));
  else if (style == "snes") snes();
  else if (style == "n64") n64();
  // PlayStation : DualShock grise ; PlayStation 2 : DualShock 2 noire.
  else if (style == "psx") playstation(rgba(202, 202, 208), rgba(112, 112, 120), rgba(170, 170, 178), rgba(72, 72, 78), rgba(70, 70, 80));
  else if (style == "ps2") playstation(rgba(40, 40, 46), rgba(12, 12, 14), rgba(62, 62, 70), rgba(96, 96, 104), rgba(190, 190, 200));
  else if (style == "gc") gamecube();
  else if (style == "psp") psp();
  else if (style == "gameBoy") gameBoy();
  else if (style == "gba") gba();
  else if (style == "genesis") genesis();
  else if (style == "master") master();
  else if (style == "saturn") saturn();
  else if (style == "dreamcast") dreamcast();
  else if (style == "pcEngine") pcEngine();
  else if (style == "neoGeo") neoGeo();
  else if (style == "ngp") ngp();
  else if (style == "cps") arcade(true);
  else if (style == "arcade") arcade(false);
  else if (style == "atari2600") atari(false);
  else if (style == "atari7800") atari(true);
  else if (style == "lynx") lynx();
  else if (style == "gx4000") gx4000();
  else defaultPad();
}

// RetroPad : manette de type Xbox (deux poignées, deux sticks, A vert en bas, B rouge à droite,
// X bleu à gauche, Y jaune en haut ; noms du RetroPad : B en bas, A à droite, Y à gauche, X en haut).
void PadPainter::defaultPad() {
  const uint32_t color = rgba(54, 56, 64), edge = rgba(22, 22, 26), trigger = rgba(40, 42, 48);
  shoulder(RETRO_DEVICE_ID_JOYPAD_L2, 18, 0, 40, 20, trigger, edge);
  shoulder(RETRO_DEVICE_ID_JOYPAD_L, 62, 2, 62, 18, trigger, edge);
  shoulder(RETRO_DEVICE_ID_JOYPAD_R, 216, 2, 62, 18, trigger, edge);
  shoulder(RETRO_DEVICE_ID_JOYPAD_R2, 282, 0, 40, 20, trigger, edge);
  body([&](float g, uint32_t col) {
    rect(12 - g, 16 - g, 316 + 2 * g, 92 + 2 * g, 40 + g, col);
    disc(66, 100, 46 + g, col);
    disc(274, 100, 46 + g, col);
  }, color, edge);
  rect(40, 20, 260, 9, 4.5f, shade(color, 0.12f));
  stick(70, 54, 15, rgba(34, 34, 38), shade(color, -0.25f), stepOf(RETRO_DEVICE_ID_JOYPAD_L3), -1, "L3");
  disc(118, 96, 28, shade(color, -0.15f));
  dpad(118, 96, 14, 7.5f, rgba(28, 28, 32));
  stick(222, 96, 15, rgba(34, 34, 38), shade(color, -0.25f), stepOf(RETRO_DEVICE_ID_JOYPAD_R3), rightAxis(), "R3");
  pill(RETRO_DEVICE_ID_JOYPAD_SELECT, 146, 58, 24, 11, rgba(30, 30, 34), Label::Below);
  pill(RETRO_DEVICE_ID_JOYPAD_START, 194, 58, 24, 11, rgba(30, 30, 34), Label::Below);
  button(RETRO_DEVICE_ID_JOYPAD_X, 272, 32, 11, kYellow);
  button(RETRO_DEVICE_ID_JOYPAD_Y, 250, 54, 11, kBlue);
  button(RETRO_DEVICE_ID_JOYPAD_A, 294, 54, 11, kRed);
  button(RETRO_DEVICE_ID_JOYPAD_B, 272, 76, 11, rgba(70, 168, 80));
}

// NES (et Famicom) : rectangle, façade noire, bandes grises au centre, B et A rouges dans des
// cadres carrés ; noms imprimés sous les boutons.
void PadPainter::nes(uint32_t color, uint32_t panel, uint32_t strip, uint32_t buttons, uint32_t print) {
  const uint32_t edge = shade(color, -0.45f);
  shoulder(RETRO_DEVICE_ID_JOYPAD_L, 34, 6, 70, 18, shade(color, -0.15f), edge);  // Famicom Disk System
  shoulder(RETRO_DEVICE_ID_JOYPAD_R, 236, 6, 70, 18, shade(color, -0.15f), edge);
  body([&](float g, uint32_t col) { rect(16 - g, 20 - g, 308 + 2 * g, 110 + 2 * g, 6 + g, col); }, color, edge);
  rect(26, 32, 288, 86, 4, panel);
  rect(126, 38, 88, 74, 5, strip);
  for (int i = 0; i < 4; i++) rect(132, 44 + i * 7.0f, 76, 3, 1.5f, shade(strip, -0.22f));
  disc(78, 75, 32, shade(panel, 0.08f));
  dpad(78, 75, 17, 9, rgba(48, 48, 50));
  pill(RETRO_DEVICE_ID_JOYPAD_SELECT, 146, 96, 26, 9, rgba(30, 30, 32), Label::Above, print);
  pill(RETRO_DEVICE_ID_JOYPAD_START, 194, 96, 26, 9, rgba(30, 30, 32), Label::Above, print);
  rect(232, 58, 34, 34, 4, shade(strip, 0.35f));
  rect(276, 58, 34, 34, 4, shade(strip, 0.35f));
  button(RETRO_DEVICE_ID_JOYPAD_B, 249, 75, 12, buttons, Label::Below, print);
  button(RETRO_DEVICE_ID_JOYPAD_A, 293, 75, 12, buttons, Label::Below, print);
}

// Super Nintendo : « os de chien » gris, croix dans un creux, boutons violets (A et B foncés,
// X et Y lavande) sur un fond mauve, Select / Start en biais.
void PadPainter::snes() {
  const uint32_t color = rgba(206, 206, 214), edge = rgba(128, 128, 138);
  shoulder(RETRO_DEVICE_ID_JOYPAD_L, 36, 6, 96, 22, rgba(168, 168, 178), edge);
  shoulder(RETRO_DEVICE_ID_JOYPAD_R, 208, 6, 96, 22, rgba(168, 168, 178), edge);
  body([&](float g, uint32_t col) {
    disc(84, 84, 58 + g, col);
    disc(256, 84, 58 + g, col);
    rect(84, 26 - g, 172, 116 + 2 * g, 8, col);
  }, color, edge);
  disc(84, 84, 36, shade(color, -0.1f));
  dpad(84, 84, 18, 10, rgba(48, 48, 54));
  disc(256, 84, 48, rgba(150, 146, 172));
  const uint32_t lavender = rgba(176, 166, 224), purple = rgba(92, 72, 168);
  button(RETRO_DEVICE_ID_JOYPAD_X, 256, 58, 13, lavender);
  button(RETRO_DEVICE_ID_JOYPAD_Y, 230, 84, 13, lavender);
  button(RETRO_DEVICE_ID_JOYPAD_A, 282, 84, 13, purple);
  button(RETRO_DEVICE_ID_JOYPAD_B, 256, 110, 13, purple);
  slanted(RETRO_DEVICE_ID_JOYPAD_SELECT, 132, 102, 144, 90, 8, rgba(100, 100, 108), Label::Below, rgba(80, 80, 92));
  slanted(RETRO_DEVICE_ID_JOYPAD_START, 196, 102, 208, 90, 8, rgba(100, 100, 108), Label::Below, rgba(80, 80, 92));
}

// Nintendo 64 : grise claire, trois poignées, croix à gauche, stick au centre (Z dessous), Start rouge, A bleu,
// B vert et boutons C jaunes à droite (stick droit : gauche / droite, haut / bas).
void PadPainter::n64() {
  const uint32_t color = rgba(200, 200, 208), edge = rgba(118, 118, 128);
  shoulder(RETRO_DEVICE_ID_JOYPAD_L, 36, 4, 70, 18, rgba(176, 176, 186), edge);
  shoulder(RETRO_DEVICE_ID_JOYPAD_R, 234, 4, 70, 18, rgba(176, 176, 186), edge);
  body([&](float g, uint32_t col) {
    rect(16 - g, 18 - g, 308 + 2 * g, 60 + 2 * g, 30 + g, col);
    rect(30 - g, 48 - g, 70 + 2 * g, 100 + 2 * g, 33 + g, col);
    rect(136 - g, 56 - g, 68 + 2 * g, 92 + 2 * g, 33 + g, col);
    rect(240 - g, 48 - g, 70 + 2 * g, 100 + 2 * g, 33 + g, col);
  }, color, edge);
  disc(66, 50, 28, shade(color, -0.08f));
  dpad(66, 50, 14, 8, rgba(84, 84, 92));
  stick(170, 84, 12, rgba(150, 150, 158), shade(color, -0.3f), -1, -1, "");
  pill(RETRO_DEVICE_ID_JOYPAD_L2, 170, 132, 34, 14, rgba(96, 96, 104));
  button(RETRO_DEVICE_ID_JOYPAD_START, 170, 42, 9, rgba(200, 40, 40), Label::Above, rgba(40, 40, 48));
  button(RETRO_DEVICE_ID_JOYPAD_Y, 230, 46, 12, kGreen);
  button(RETRO_DEVICE_ID_JOYPAD_B, 254, 66, 12, rgba(42, 82, 200));
  // Boutons C.
  const int stepX = axisStep('x'), stepY = axisStep('y');
  if (stepX >= 0 || stepY >= 0) {
    const float ccx = 288, ccy = 42, off = 13, cr = 7.5f;
    const struct {
      float ox, oy;
      int step;
    } cs[] = {{-off, 0, stepX}, {off, 0, stepX}, {0, -off, stepY}, {0, off, stepY}};
    for (const auto& b : cs) {
      disc(ccx + b.ox, ccy + b.oy + 1.2f, cr + 1, rgba(0, 0, 0, 90));
      disc(ccx + b.ox, ccy + b.oy, cr, kYellow);
    }
    for (const auto& b : cs) mark(b.step, ccx + b.ox - cr, ccy + b.oy - cr, 2 * cr, 2 * cr, cr);
    text(ccx, ccy, "C", 10, rgba(70, 60, 20));
  }
}

// PlayStation (DualShock) et PlayStation 2 (DualShock 2, noire) : deux poignées, croix en quatre
// flèches, symboles de couleur, deux sticks ; [print] : noms de Select / Start.
void PadPainter::playstation(uint32_t color, uint32_t edge, uint32_t trigger, uint32_t arrows, uint32_t print) {
  shoulder(RETRO_DEVICE_ID_JOYPAD_L2, 22, 0, 44, 20, trigger, edge);
  shoulder(RETRO_DEVICE_ID_JOYPAD_L, 70, 2, 58, 18, trigger, edge);
  shoulder(RETRO_DEVICE_ID_JOYPAD_R, 212, 2, 58, 18, trigger, edge);
  shoulder(RETRO_DEVICE_ID_JOYPAD_R2, 274, 0, 44, 20, trigger, edge);
  body([&](float g, uint32_t col) {
    rect(12 - g, 16 - g, 316 + 2 * g, 88 + 2 * g, 40 + g, col);
    disc(70, 100, 44 + g, col);
    disc(270, 100, 44 + g, col);
  }, color, edge);
  const bool dark = (color & 0xFF) < 100;
  disc(72, 56, 30, shade(color, dark ? 0.08f : -0.1f));
  dpad(72, 56, 15, 8, arrows, true);
  disc(268, 56, 32, shade(color, dark ? 0.08f : -0.1f));
  const uint32_t buttons = dark ? rgba(26, 26, 30) : rgba(46, 46, 54);
  symbol(RETRO_DEVICE_ID_JOYPAD_X, 268, 34, 10.5f, buttons, Symbol::Triangle, rgba(76, 196, 156));
  symbol(RETRO_DEVICE_ID_JOYPAD_Y, 246, 56, 10.5f, buttons, Symbol::Square, rgba(232, 136, 206));
  symbol(RETRO_DEVICE_ID_JOYPAD_A, 290, 56, 10.5f, buttons, Symbol::Circle, rgba(232, 86, 96));
  symbol(RETRO_DEVICE_ID_JOYPAD_B, 268, 78, 10.5f, buttons, Symbol::Cross, rgba(120, 150, 236));
  pill(RETRO_DEVICE_ID_JOYPAD_SELECT, 146, 56, 24, 8, shade(color, dark ? 0.3f : -0.55f), Label::Below, print);
  pill(RETRO_DEVICE_ID_JOYPAD_START, 194, 56, 24, 8, shade(color, dark ? 0.3f : -0.55f), Label::Below, print);
  disc(170, 82, 3, rgba(220, 40, 40));  // voyant « Analog »
  const uint32_t cap = dark ? rgba(28, 28, 32) : rgba(50, 50, 56), well = shade(color, dark ? 0.12f : -0.22f);
  stick(128, 100, 15, cap, well, stepOf(RETRO_DEVICE_ID_JOYPAD_L3), -1, "L3");
  stick(212, 100, 15, cap, well, stepOf(RETRO_DEVICE_ID_JOYPAD_R3), rightAxis(), "R3");
}

// PSP : console portable noire, écran au centre, croix et petit stick à gauche, symboles à droite.
void PadPainter::psp() {
  const uint32_t color = rgba(40, 40, 44), edge = rgba(12, 12, 14);
  shoulder(RETRO_DEVICE_ID_JOYPAD_L, 22, 8, 74, 20, rgba(64, 64, 70), edge);
  shoulder(RETRO_DEVICE_ID_JOYPAD_R, 244, 8, 74, 20, rgba(64, 64, 70), edge);
  body([&](float g, uint32_t col) { rect(8 - g, 26 - g, 324 + 2 * g, 104 + 2 * g, 50 + g, col); }, color, edge);
  screen(94, 32, 152, 84, rgba(24, 24, 28), rgba(18, 22, 32));
  dpad(50, 62, 12, 7, rgba(84, 84, 90), true);
  stick(50, 104, 8, rgba(110, 110, 116), rgba(24, 24, 26), -1, -1, "");
  const uint32_t dark = rgba(30, 30, 34);
  symbol(RETRO_DEVICE_ID_JOYPAD_X, 290, 46, 9, dark, Symbol::Triangle, rgba(76, 196, 156));
  symbol(RETRO_DEVICE_ID_JOYPAD_Y, 271, 66, 9, dark, Symbol::Square, rgba(232, 136, 206));
  symbol(RETRO_DEVICE_ID_JOYPAD_A, 309, 66, 9, dark, Symbol::Circle, rgba(232, 86, 96));
  symbol(RETRO_DEVICE_ID_JOYPAD_B, 290, 86, 9, dark, Symbol::Cross, rgba(120, 150, 236));
  pill(RETRO_DEVICE_ID_JOYPAD_SELECT, 152, 122, 20, 6, rgba(96, 96, 102), Label::None);
  pill(RETRO_DEVICE_ID_JOYPAD_START, 188, 122, 20, 6, rgba(96, 96, 102), Label::None);
}

// Game Boy : console verticale grise, écran vert, B et A magenta en biais.
void PadPainter::gameBoy() {
  const uint32_t color = rgba(196, 196, 186), edge = rgba(112, 112, 104);
  body([&](float g, uint32_t col) { rect(108 - g, 0 - g, 124 + 2 * g, 150 + 2 * g, 9 + g, col); }, color, edge);
  rect(116, 8, 108, 66, 5, rgba(96, 96, 112));
  rect(136, 16, 68, 50, 1, rgba(150, 170, 72));
  disc(126, 36, 2.5f, rgba(220, 40, 40));
  dpad(140, 102, 11, 6, rgba(40, 40, 42));
  button(RETRO_DEVICE_ID_JOYPAD_B, 192, 110, 9, rgba(160, 32, 90), Label::Below, rgba(50, 50, 120));
  button(RETRO_DEVICE_ID_JOYPAD_A, 214, 98, 9, rgba(160, 32, 90), Label::Below, rgba(50, 50, 120));
  slanted(RETRO_DEVICE_ID_JOYPAD_SELECT, 150, 140, 158, 134, 5, rgba(130, 130, 128), Label::None, 0);
  slanted(RETRO_DEVICE_ID_JOYPAD_START, 170, 140, 178, 134, 5, rgba(130, 130, 128), Label::None, 0);
  for (int i = 0; i < 4; i++) line(196 + i * 6.0f, 146, 212 + i * 6.0f, 130, 2, shade(color, -0.3f));  // haut-parleur
}

// Game Boy Advance : console horizontale violette, écran au centre, A et B à droite.
void PadPainter::gba() {
  const uint32_t color = rgba(92, 80, 172), edge = rgba(48, 40, 110);
  shoulder(RETRO_DEVICE_ID_JOYPAD_L, 30, 10, 80, 20, rgba(160, 160, 170), edge);
  shoulder(RETRO_DEVICE_ID_JOYPAD_R, 230, 10, 80, 20, rgba(160, 160, 170), edge);
  body([&](float g, uint32_t col) { rect(16 - g, 28 - g, 308 + 2 * g, 100 + 2 * g, 48 + g, col); }, color, edge);
  screen(104, 36, 132, 84, rgba(40, 36, 70), rgba(20, 24, 32));
  dpad(62, 70, 13, 7, rgba(34, 34, 40));
  button(RETRO_DEVICE_ID_JOYPAD_SELECT, 50, 108, 5, rgba(204, 204, 214), Label::None);
  button(RETRO_DEVICE_ID_JOYPAD_START, 70, 108, 5, rgba(204, 204, 214), Label::None);
  button(RETRO_DEVICE_ID_JOYPAD_B, 264, 88, 11, rgba(204, 204, 214));
  button(RETRO_DEVICE_ID_JOYPAD_A, 290, 72, 11, rgba(204, 204, 214));
}

// Mega Drive (6 boutons) : noire, deux lobes arrondis, croix ronde, A B C en bas et X Y Z au-dessus.
void PadPainter::genesis() {
  const uint32_t color = rgba(34, 34, 38), edge = rgba(10, 10, 12), buttons = rgba(58, 58, 66);
  body([&](float g, uint32_t col) {
    rect(14 - g, 30 - g, 312 + 2 * g, 72 + 2 * g, 36 + g, col);
    disc(84, 96, 46 + g, col);
    disc(256, 96, 46 + g, col);
  }, color, edge);
  rect(130, 36, 80, 12, 6, shade(color, 0.1f));  // logo
  disc(80, 76, 31, rgba(22, 22, 24));
  dpad(80, 76, 16, 9, rgba(48, 48, 52));
  pill(RETRO_DEVICE_ID_JOYPAD_START, 170, 58, 44, 12, rgba(64, 64, 70));
  button(RETRO_DEVICE_ID_JOYPAD_Y, 220, 98, 13, buttons);  // A
  button(RETRO_DEVICE_ID_JOYPAD_B, 252, 88, 13, buttons);  // B
  button(RETRO_DEVICE_ID_JOYPAD_A, 284, 78, 13, buttons);  // C
  button(RETRO_DEVICE_ID_JOYPAD_L, 214, 64, 9.5f, buttons);  // X
  button(RETRO_DEVICE_ID_JOYPAD_X, 242, 54, 9.5f, buttons);  // Y
  button(RETRO_DEVICE_ID_JOYPAD_R, 270, 44, 9.5f, buttons);  // Z
}

// Master System (et Game Gear) : rectangle noir, croix dans un cadre rouge, boutons 1 et 2.
void PadPainter::master() {
  const uint32_t color = rgba(36, 36, 40), edge = rgba(12, 12, 14);
  body([&](float g, uint32_t col) { rect(20 - g, 32 - g, 300 + 2 * g, 90 + 2 * g, 8 + g, col); }, color, edge);
  rect(28, 40, 284, 74, 4, rgba(44, 44, 50));
  rect(48, 41, 72, 72, 4, rgba(196, 40, 44));  // cadre rouge de la croix
  rect(51, 44, 66, 66, 3, rgba(30, 30, 34));
  dpad(84, 77, 18, 10, rgba(56, 56, 62));
  rect(222, 104, 78, 3, 1.5f, rgba(196, 40, 44));  // filet rouge sous 1 et 2
  pill(RETRO_DEVICE_ID_JOYPAD_START, 170, 54, 40, 10, rgba(84, 84, 90), Label::Below);
  button(RETRO_DEVICE_ID_JOYPAD_B, 238, 82, 15, rgba(64, 64, 70));
  button(RETRO_DEVICE_ID_JOYPAD_A, 284, 82, 15, rgba(64, 64, 70));
}

// Saturn : « os de chien » noir, boutons gris (A B C en bas, X Y Z plus petits au-dessus),
// gâchettes L / R en haut.
void PadPainter::saturn() {
  const uint32_t color = rgba(44, 44, 50), edge = rgba(14, 14, 16), buttons = rgba(100, 100, 110);
  shoulder(RETRO_DEVICE_ID_JOYPAD_L2, 36, 6, 96, 22, rgba(72, 72, 80), edge);
  shoulder(RETRO_DEVICE_ID_JOYPAD_R2, 208, 6, 96, 22, rgba(72, 72, 80), edge);
  body([&](float g, uint32_t col) {
    disc(84, 84, 58 + g, col);
    disc(256, 84, 58 + g, col);
    rect(84, 26 - g, 172, 116 + 2 * g, 8, col);
  }, color, edge);
  disc(84, 84, 34, shade(color, -0.3f));
  dpad(84, 84, 18, 10, rgba(78, 78, 86));
  pill(RETRO_DEVICE_ID_JOYPAD_START, 170, 108, 34, 12, rgba(110, 110, 120), Label::Below);
  button(RETRO_DEVICE_ID_JOYPAD_B, 226, 104, 12, buttons);  // A
  button(RETRO_DEVICE_ID_JOYPAD_A, 256, 94, 12, buttons);  // B
  button(RETRO_DEVICE_ID_JOYPAD_R, 286, 84, 12, buttons);  // C
  button(RETRO_DEVICE_ID_JOYPAD_Y, 222, 72, 9, buttons);   // X
  button(RETRO_DEVICE_ID_JOYPAD_X, 250, 62, 9, buttons);   // Y
  button(RETRO_DEVICE_ID_JOYPAD_L, 278, 52, 9, buttons);   // Z
}

// Dreamcast : blanche, fente de la carte mémoire au centre, stick en haut à gauche, croix
// dessous, A rouge, B bleu, X jaune, Y vert ; gâchettes L / R.
void PadPainter::dreamcast() {
  const uint32_t color = rgba(232, 232, 234), edge = rgba(140, 140, 146);
  shoulder(RETRO_DEVICE_ID_JOYPAD_L2, 40, 4, 74, 18, rgba(196, 196, 200), edge);
  shoulder(RETRO_DEVICE_ID_JOYPAD_R2, 226, 4, 74, 18, rgba(196, 196, 200), edge);
  body([&](float g, uint32_t col) {
    rect(22 - g, 16 - g, 296 + 2 * g, 84 + 2 * g, 34 + g, col);
    disc(86, 104, 42 + g, col);
    disc(254, 104, 42 + g, col);
  }, color, edge);
  rect(132, 24, 76, 46, 4, rgba(170, 170, 178));
  rect(140, 30, 60, 34, 2, rgba(120, 164, 104));  // écran de la carte mémoire (VMU)
  rect(140, 30, 60, 8, 2, rgba(255, 255, 255, 30));
  stick(74, 52, 13, rgba(206, 206, 210), shade(color, -0.25f), -1, -1, "");
  dpad(104, 98, 13, 7.5f, rgba(70, 70, 76));
  button(RETRO_DEVICE_ID_JOYPAD_X, 262, 48, 10.5f, kGreen);   // Y
  button(RETRO_DEVICE_ID_JOYPAD_Y, 240, 70, 10.5f, kYellow);  // X
  button(RETRO_DEVICE_ID_JOYPAD_A, 284, 70, 10.5f, kBlue);    // B
  button(RETRO_DEVICE_ID_JOYPAD_B, 262, 92, 10.5f, kRed);     // A
  button(RETRO_DEVICE_ID_JOYPAD_START, 170, 96, 7, rgba(70, 70, 76), Label::Below, rgba(80, 80, 92));
}

// PC Engine (TurboPad de la TurboGrafx-16) : noir, bosse au centre, Select / Run, interrupteurs
// turbo au-dessus des boutons II et I.
void PadPainter::pcEngine() {
  const uint32_t color = rgba(36, 36, 40), edge = rgba(10, 10, 12);
  body([&](float g, uint32_t col) {
    rect(16 - g, 30 - g, 308 + 2 * g, 92 + 2 * g, 10 + g, col);
    rect(122 - g, 22 - g, 96 + 2 * g, 40 + 2 * g, 14 + g, col);  // bosse
  }, color, edge);
  rect(26, 40, 288, 72, 6, rgba(44, 44, 50));
  rect(128, 26, 84, 30, 10, rgba(54, 54, 60));
  rect(34, 46, 30, 6, 2, rgba(200, 40, 40));  // « TURBO »
  disc(80, 78, 30, rgba(28, 28, 32));
  dpad(80, 78, 18, 10, rgba(64, 64, 70));
  pill(RETRO_DEVICE_ID_JOYPAD_SELECT, 148, 92, 26, 9, rgba(104, 104, 112), Label::Above, rgba(200, 200, 210));
  pill(RETRO_DEVICE_ID_JOYPAD_START, 192, 92, 26, 9, rgba(104, 104, 112), Label::Above, rgba(200, 200, 210));
  for (float x : {232.0f, 276.0f}) {
    rect(x, 46, 16, 8, 2, rgba(150, 150, 156));
    rect(x + 6, 46, 4, 8, 1, rgba(200, 40, 40));
  }
  button(RETRO_DEVICE_ID_JOYPAD_B, 240, 84, 14, rgba(72, 72, 80));  // II
  button(RETRO_DEVICE_ID_JOYPAD_A, 284, 84, 14, rgba(72, 72, 80));  // I
}

// Neo Geo (manette du Neo Geo CD) : noire, joystick à gauche, A rouge, B jaune, C vert, D bleu en arc.
void PadPainter::neoGeo() {
  const uint32_t color = rgba(40, 40, 44), edge = rgba(12, 12, 14);
  body([&](float g, uint32_t col) { rect(14 - g, 28 - g, 312 + 2 * g, 98 + 2 * g, 34 + g, col); }, color, edge);
  disc(82, 78, 32, rgba(24, 24, 26));
  disc(82, 78 + 1.5f, 14, rgba(0, 0, 0, 100));
  disc(82, 78, 14, rgba(76, 76, 82));
  ring(82, 78, 9, 1.5f, rgba(110, 110, 118));
  directions(82, 78, 24, rgba(150, 150, 160));
  pill(RETRO_DEVICE_ID_JOYPAD_SELECT, 148, 64, 24, 9, rgba(90, 90, 96), Label::Below);
  pill(RETRO_DEVICE_ID_JOYPAD_START, 192, 64, 24, 9, rgba(90, 90, 96), Label::Below);
  button(RETRO_DEVICE_ID_JOYPAD_B, 212, 100, 12, kRed);     // A
  button(RETRO_DEVICE_ID_JOYPAD_A, 242, 86, 12, kYellow);   // B
  button(RETRO_DEVICE_ID_JOYPAD_Y, 272, 74, 12, kGreen);    // C
  button(RETRO_DEVICE_ID_JOYPAD_X, 302, 66, 12, kBlue);     // D
}

// Neo Geo Pocket : console horizontale, écran au centre, joystick à gauche, A et B à droite.
void PadPainter::ngp() {
  const uint32_t color = rgba(62, 64, 74), edge = rgba(24, 24, 28);
  body([&](float g, uint32_t col) { rect(20 - g, 28 - g, 300 + 2 * g, 100 + 2 * g, 46 + g, col); }, color, edge);
  screen(104, 34, 132, 88, rgba(30, 30, 36), rgba(36, 46, 40));
  disc(62, 80, 22, rgba(30, 30, 34));
  disc(62, 80, 11, rgba(116, 116, 124));
  directions(62, 80, 17, rgba(180, 180, 190));
  button(RETRO_DEVICE_ID_JOYPAD_B, 264, 94, 11, rgba(186, 186, 194));  // A
  button(RETRO_DEVICE_ID_JOYPAD_A, 292, 74, 11, rgba(186, 186, 194));  // B
  button(RETRO_DEVICE_ID_JOYPAD_START, 280, 118, 5, rgba(140, 140, 148), Label::None);  // Option
}

// Arcade : panneau noir, joystick à boule rouge, deux rangées de trois boutons, Pièce / Start.
// Capcom (CPS) : poings en haut (rouges), pieds en bas (bleus).
void PadPainter::arcade(bool cps) {
  const uint32_t color = rgba(28, 28, 32), edge = rgba(8, 8, 10);
  body([&](float g, uint32_t col) { rect(10 - g, 24 - g, 320 + 2 * g, 114 + 2 * g, 10 + g, col); }, color, edge);
  rect(10, 30, 320, 5, 0, cps ? rgba(200, 40, 40) : rgba(60, 100, 210));
  disc(76, 86, 30, rgba(16, 16, 18));
  disc(76, 86, 6, rgba(110, 110, 118));
  disc(76, 82 + 1.5f, 16, rgba(0, 0, 0, 110));
  disc(76, 82, 16, kRed);
  disc(71, 77, 6, shade(kRed, 0.35f));
  directions(76, 86, 40, rgba(160, 160, 170));
  const uint32_t top[3] = {cps ? kRed : kRed, cps ? kRed : kBlue, cps ? kRed : kYellow};
  const uint32_t bottom[3] = {cps ? kBlue : kGreen, cps ? kBlue : rgba(224, 224, 228), cps ? kBlue : rgba(232, 120, 40)};
  button(RETRO_DEVICE_ID_JOYPAD_Y, 176, 66, 13, top[0]);
  button(RETRO_DEVICE_ID_JOYPAD_X, 214, 62, 13, top[1]);
  button(RETRO_DEVICE_ID_JOYPAD_L, 252, 62, 13, top[2]);
  button(RETRO_DEVICE_ID_JOYPAD_B, 180, 104, 13, bottom[0]);
  button(RETRO_DEVICE_ID_JOYPAD_A, 218, 100, 13, bottom[1]);
  button(RETRO_DEVICE_ID_JOYPAD_R, 256, 100, 13, bottom[2]);
  button(RETRO_DEVICE_ID_JOYPAD_SELECT, 300, 54, 7, rgba(224, 224, 228), Label::Below);
  button(RETRO_DEVICE_ID_JOYPAD_START, 300, 96, 7, rgba(224, 224, 228), Label::Below);
}

// Atari 2600 (CX40) : joystick noir carré, bouton de tir rouge ; 7800 (ProLine) : poignée et deux
// boutons latéraux. Select / Reset (ou Pause) : interrupteurs de la console, à gauche.
void PadPainter::atari(bool proLine) {
  const uint32_t color = rgba(30, 30, 32), edge = rgba(8, 8, 10);
  const uint32_t metal = rgba(192, 192, 198);
  pill(RETRO_DEVICE_ID_JOYPAD_SELECT, 52, 56, 44, 14, metal, Label::Below);
  pill(RETRO_DEVICE_ID_JOYPAD_START, 52, 104, 44, 14, metal, Label::Below);
  if (!proLine) {
    // CX40 : socle carré biseauté, soufflet et manche vus de dessus.
    body([&](float g, uint32_t col) { rect(110 - g, 14 - g, 120 + 2 * g, 122 + 2 * g, 10 + g, col); }, color, edge);
    rect(118, 22, 104, 106, 8, rgba(44, 44, 48));
    disc(170, 82, 26, rgba(20, 20, 22));
    ring(170, 82, 20, 1.6f, rgba(52, 52, 56));
    ring(170, 82, 14, 1.6f, rgba(52, 52, 56));
    disc(173, 85, 10, rgba(0, 0, 0, 120));  // ombre du manche
    disc(170, 82, 9, rgba(14, 14, 16));
    disc(167, 79, 3.5f, rgba(84, 84, 90));
    directions(170, 82, 42, rgba(150, 150, 160));
    button(RETRO_DEVICE_ID_JOYPAD_B, 132, 36, 11, kRed);
    return;
  }
  body([&](float g, uint32_t col) { rect(100 - g, 20 - g, 140 + 2 * g, 116 + 2 * g, 26 + g, col); }, color, edge);
  disc(170, 80, 26, rgba(20, 20, 22));
  disc(170, 80 + 1.5f, 12, rgba(0, 0, 0, 110));
  disc(170, 80, 12, rgba(64, 64, 68));
  directions(170, 80, 36, rgba(150, 150, 160));
  button(RETRO_DEVICE_ID_JOYPAD_B, 116, 80, 10, kRed);
  button(RETRO_DEVICE_ID_JOYPAD_A, 224, 80, 10, kRed);
}

// Lynx : longue console horizontale, écran au centre, croix à gauche, A et B à droite.
void PadPainter::lynx() {
  const uint32_t color = rgba(50, 50, 56), edge = rgba(16, 16, 18);
  body([&](float g, uint32_t col) { rect(4 - g, 30 - g, 332 + 2 * g, 96 + 2 * g, 46 + g, col); }, color, edge);
  screen(100, 38, 140, 80, rgba(30, 30, 34), rgba(28, 40, 36));
  disc(54, 78, 26, shade(color, -0.2f));
  dpad(54, 78, 14, 7.5f, rgba(30, 30, 34));
  button(RETRO_DEVICE_ID_JOYPAD_A, 300, 64, 11, rgba(72, 72, 78));
  button(RETRO_DEVICE_ID_JOYPAD_B, 276, 92, 11, rgba(72, 72, 78));
  pill(RETRO_DEVICE_ID_JOYPAD_L, 262, 44, 22, 8, rgba(96, 96, 104), Label::None);   // Option 1
  pill(RETRO_DEVICE_ID_JOYPAD_R, 262, 114, 22, 8, rgba(96, 96, 104), Label::None);  // Option 2
  pill(RETRO_DEVICE_ID_JOYPAD_START, 80, 114, 22, 8, rgba(96, 96, 104), Label::None);  // Pause
}

// Amstrad GX4000 : manette grise, façade sombre, croix et deux boutons de tir.
void PadPainter::gx4000() {
  const uint32_t color = rgba(176, 176, 182), edge = rgba(100, 100, 106);
  body([&](float g, uint32_t col) { rect(20 - g, 34 - g, 300 + 2 * g, 86 + 2 * g, 12 + g, col); }, color, edge);
  rect(30, 44, 280, 66, 6, rgba(70, 70, 80));
  dpad(86, 77, 18, 10, rgba(30, 30, 34));
  button(RETRO_DEVICE_ID_JOYPAD_B, 236, 72, 14, kRed, Label::Below);
  button(RETRO_DEVICE_ID_JOYPAD_A, 284, 72, 14, kBlue, Label::Below);
}

// GameCube : indigo, stick en haut à gauche, croix dessous, Start au centre, gros A vert, B rouge,
// X et Y gris, stick C jaune (stick droit), gâchettes L / R et bouton Z sur la tranche droite.
void PadPainter::gamecube() {
  const uint32_t color = rgba(84, 72, 170), edge = rgba(40, 34, 96), trigger = rgba(118, 108, 196);
  shoulder(RETRO_DEVICE_ID_JOYPAD_L2, 40, 2, 76, 20, trigger, edge);
  shoulder(RETRO_DEVICE_ID_JOYPAD_R, 186, 8, 44, 14, rgba(124, 112, 220), edge);  // Z
  shoulder(RETRO_DEVICE_ID_JOYPAD_R2, 234, 2, 68, 20, trigger, edge);
  body([&](float g, uint32_t col) {
    rect(18 - g, 20 - g, 304 + 2 * g, 82 + 2 * g, 40 + g, col);
    disc(72, 104, 40 + g, col);
    disc(268, 104, 40 + g, col);
  }, color, edge);
  stick(76, 56, 14, rgba(170, 170, 180), shade(color, -0.3f), -1, -1, "");
  disc(112, 102, 20, shade(color, -0.2f));
  dpad(112, 102, 11, 6, rgba(170, 170, 180));
  button(RETRO_DEVICE_ID_JOYPAD_START, 170, 64, 6, rgba(200, 200, 208), Label::Below);
  button(RETRO_DEVICE_ID_JOYPAD_X, 296, 50, 9, rgba(206, 206, 214));   // X, à droite de A
  button(RETRO_DEVICE_ID_JOYPAD_Y, 258, 32, 9, rgba(206, 206, 214));   // Y, au-dessus de A
  button(RETRO_DEVICE_ID_JOYPAD_A, 264, 60, 15, rgba(60, 176, 104));   // A
  button(RETRO_DEVICE_ID_JOYPAD_B, 236, 84, 9, rgba(206, 46, 52));     // B
  // Stick C : axes du stick droit.
  const int axis = rightAxis();
  if (axis >= 0) {
    disc(232, 112, 17, shade(color, -0.3f));
    disc(232, 113.5f, 11.5f, rgba(0, 0, 0, 100));
    disc(232, 112, 11, rgba(232, 190, 46));
    text(232, 112, "C", 12, rgba(90, 70, 10));
    mark(axis, 221, 101, 22, 22, 11);
  }
}

}  // namespace

int PadConfig::renderPad(Canvas& c, int x, int y, int w) const {
  PadPainter painter(c, (float)x, (float)y, (float)w, steps_, assigned_, joystick_ ? (int)step_ : -1);
  painter.draw(style_);
  return painter.height();
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
  const int padWidth = std::min(380, right - left);
  y += renderPad(c, cx - padWidth / 2, y, padWidth) + 10;
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
