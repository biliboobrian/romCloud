#include "players.h"

#include "padconfig.h"

#include <algorithm>

static const uint32_t kText = rgba(235, 235, 245);
static const uint32_t kMuted = rgba(160, 160, 180);
static const uint32_t kAccent = rgba(140, 130, 255);

static void centered(Canvas& c, int cx, int y, const std::string& text, int scale, uint32_t color) {
  c.text(cx - Canvas::measure(text, scale) / 2, y, text, scale, color);
}

bool PlayerOrder::handleEvent(const SDL_Event& e) {
  if (finished_) return false;
  switch (e.type) {
    case SDL_EVENT_GAMEPAD_BUTTON_DOWN: {
      const SDL_JoystickID id = e.gbutton.which;
      if (input_.portOf(id) < 0) return true;  // manette au-delà de 4 : pas de joueur
      if (std::find(order_.begin(), order_.end(), id) != order_.end()) {
        finish(true);  // même manette : terminé
        return true;
      }
      order_.push_back(id);
      if ((int)order_.size() >= input_.connected()) finish(true);
      return true;
    }
    case SDL_EVENT_GAMEPAD_BUTTON_UP:
    case SDL_EVENT_GAMEPAD_AXIS_MOTION:
      return true;
    case SDL_EVENT_GAMEPAD_REMOVED:
      order_.erase(std::remove(order_.begin(), order_.end(), e.gdevice.which), order_.end());
      return false;
    case SDL_EVENT_KEY_DOWN:
      if (e.key.repeat) return true;
      if (e.key.key == SDLK_ESCAPE || e.key.key == SDLK_BACKSPACE) finish(false);
      else if (e.key.key == SDLK_RETURN || e.key.key == SDLK_KP_ENTER) finish(true);
      return true;
    default:
      return false;
  }
}

void PlayerOrder::finish(bool save) {
  finished_ = true;
  if (save && !order_.empty()) {
    input_.setPlayerOrder(order_);
    result_ = tr_("players_saved");
  }
}

void PlayerOrder::render(Canvas& c) const {
  c.fill(0, 0, c.width(), c.height(), rgba(8, 9, 14, 215));
  const int cx = c.width() / 2, width = std::min(c.width() - 48, 520);
  int y = 24;
  centered(c, cx, y, Canvas::fit(tr_("players_title"), 2, width), 2, kText);
  y += 34;

  const int connected = input_.connected();
  const int next = (int)order_.size();
  std::string hint = connected == 0 ? tr_("players_none")
                                    : tr_("players_press") + std::string(" ") + std::to_string(next + 1);
  centered(c, cx, y, Canvas::fit(hint, 1, width), 1, connected ? kAccent : kMuted);
  y += 24;

  // Joueurs 1 à 4 : manette choisie (en couleur), sinon celle d'avant (en gris), avec son illustration.
  const int left = cx - width / 2;
  const int rowHeight = 40, art = 36;
  bool anyArt = false;
  for (int port = 0; port < Input::kPorts; port++) {
    std::string name;
    uint32_t color = kMuted;
    SDL_Surface* image = nullptr;
    if (port < next) {
      const char* n = SDL_GetGamepadNameForID(order_[(size_t)port]);
      name = n ? n : "?";
      color = kText;
      image = padImage(gamepadImageName(SDL_GetGamepadTypeForID(order_[(size_t)port]), n));
    } else if (next == 0) {
      name = input_.padName(port);
      if (SDL_Gamepad* pad = input_.padAt(port)) image = padImage(gamepadImageName(SDL_GetGamepadType(pad), SDL_GetGamepadName(pad)));
    }
    anyArt = anyArt || image;
    const bool current = port == next && port < connected;
    c.roundRect((float)left, (float)y, (float)width, (float)rowHeight, 6, current ? rgba(60, 55, 120, 220) : rgba(30, 30, 44, 200));
    drawPadImage(c, image, left + 4, y + 2, art * 292 / 307, art);
    const int tx = left + 52, ty = y + (rowHeight - 8) / 2;
    c.text(tx, ty, tr_("players_player") + std::string(" ") + std::to_string(port + 1), 1, current ? kText : kMuted);
    c.text(tx + 100, ty, Canvas::fit(name.empty() ? "-" : name, 1, width - 112 - 52), 1, color);
    y += rowHeight + 6;
  }
  if (anyArt) c.text(12, c.height() - 14, Canvas::fit(tr_("pad_art_credit"), 1, c.width() - 24), 1, kMuted);
  y += 6;
  centered(c, cx, y, Canvas::fit(tr_("players_keyboard"), 1, width), 1, kMuted);
  y += 16;
  centered(c, cx, y, Canvas::fit(tr_("players_keys"), 1, width), 1, kMuted);
}
