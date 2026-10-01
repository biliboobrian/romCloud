#include "menu.h"

#include <algorithm>
#include <cstring>
#include <utility>

#include "core.h"

static const uint32_t kText = rgba(235, 235, 245);
static const uint32_t kMuted = rgba(160, 160, 180);
static const uint32_t kAccent = rgba(140, 130, 255);
static const uint32_t kSelection = rgba(91, 79, 224, 230);

static const struct {
  const char* key;
  const char* fr;
  const char* en;
} kStrings[] = {
    {"resume", "Reprendre", "Resume"},
    {"save_state", "Sauvegarder l'état", "Save state"},
    {"load_state", "Charger l'état", "Load state"},
    {"reset", "Redémarrer le jeu", "Restart game"},
    {"options", "Options du cœur", "Core options"},
    {"disk", "Disque", "Disc"},
    {"smooth", "Lissage de l'image", "Smooth image"},
    {"fullscreen", "Plein écran", "Fullscreen"},
    {"quit", "Quitter", "Quit"},
    {"yes", "Oui", "Yes"},
    {"no", "Non", "No"},
    {"hint", "A / Entrée : OK   B / Échap : retour   ← → : changer", "A / Enter: OK   B / Esc: back   ← →: change"},
    {"options_title", "Options du cœur", "Core options"},
    {"options_hint", "Mémorisées pour ce système. Certaines s'appliquent au redémarrage du jeu.",
     "Saved for this system. Some apply after restarting the game."},
    {"options_none", "Ce cœur n'a pas d'options.", "This core has no options."},
    {"options_reset", "Réinitialiser toutes les options", "Reset all options"},
    {"state_saved", "État sauvegardé", "State saved"},
    {"state_loaded", "État chargé", "State loaded"},
    {"state_error", "Impossible de sauvegarder ou de charger l'état", "The state could not be saved or loaded"},
    {"no_state", "Aucun état sauvegardé pour ce jeu", "No saved state for this game"},
    {"load_failed", "Le jeu n'a pas pu démarrer avec ce cœur (fichier non pris en charge ou BIOS manquant ?).",
     "The game could not be started with this core (unsupported file or missing BIOS?)."},
    {"core_failed", "Le cœur n'a pas pu être chargé :", "The core could not be loaded:"},
    {"video_failed", "L'affichage OpenGL n'a pas pu être initialisé :", "OpenGL display could not be initialized:"},
    {"menu_hint", "Échap ou Start + Select : menu", "Esc or Start + Select: menu"},
};

Menu::Menu(std::string language, std::string title, std::string core)
    : language_(std::move(language)), title_(std::move(title)), core_(std::move(core)) {}

std::string Menu::tr(const char* key) const {
  for (const auto& s : kStrings) {
    if (strcmp(s.key, key) == 0) return language_ == "fr" ? s.fr : s.en;
  }
  return key;
}

void Menu::open() {
  open_ = true;
  screen_ = Screen::Main;
  selected_ = 0;
}

int Menu::itemCount(const MenuState& state) const { return state.diskCount > 1 ? 9 : 8; }

Menu::Item Menu::itemAt(int index, const MenuState& state) const {
  static const Item withDisk[] = {Item::Resume, Item::SaveState, Item::LoadState, Item::Reset, Item::Options,
                                  Item::Disk, Item::Smooth, Item::Fullscreen, Item::Quit};
  static const Item withoutDisk[] = {Item::Resume, Item::SaveState, Item::LoadState, Item::Reset,
                                     Item::Options, Item::Smooth, Item::Fullscreen, Item::Quit};
  return state.diskCount > 1 ? withDisk[index] : withoutDisk[index];
}

std::string Menu::itemLabel(Item item, const MenuState& state) const {
  switch (item) {
    case Item::Resume: return tr("resume");
    case Item::SaveState: return tr("save_state");
    case Item::LoadState: return tr("load_state");
    case Item::Reset: return tr("reset");
    case Item::Options: return tr("options");
    case Item::Disk:
      return tr("disk") + " : " + std::to_string(state.diskIndex + 1) + " / " + std::to_string(state.diskCount);
    case Item::Smooth: return tr("smooth") + " : " + tr(state.smooth ? "yes" : "no");
    case Item::Fullscreen: return tr("fullscreen") + " : " + tr(state.fullscreen ? "yes" : "no");
    case Item::Quit: return tr("quit");
  }
  return {};
}

MenuAction Menu::handle(Nav nav, const MenuState& state) {
  if (screen_ == Screen::Options) {
    int count = (int)g.options.list().size() + 1;  // + « Réinitialiser »
    switch (nav) {
      case Nav::Up: optionSelected_ = (optionSelected_ + count - 1) % count; break;
      case Nav::Down: optionSelected_ = (optionSelected_ + 1) % count; break;
      case Nav::Left:
      case Nav::Right:
      case Nav::Confirm:
        if (optionSelected_ == 0) {
          if (nav == Nav::Confirm) g.options.resetAll();
        } else {
          g.options.cycle((size_t)optionSelected_ - 1, nav == Nav::Left ? -1 : 1);
        }
        break;
      case Nav::Back: screen_ = Screen::Main; break;
    }
    return MenuAction::None;
  }

  int count = itemCount(state);
  selected_ = std::min(selected_, count - 1);
  Item item = itemAt(selected_, state);
  switch (nav) {
    case Nav::Up: selected_ = (selected_ + count - 1) % count; return MenuAction::None;
    case Nav::Down: selected_ = (selected_ + 1) % count; return MenuAction::None;
    case Nav::Back: return MenuAction::Resume;
    case Nav::Left:
    case Nav::Right:
      if (item == Item::Disk) return nav == Nav::Left ? MenuAction::DiskPrev : MenuAction::DiskNext;
      if (item == Item::Smooth) return MenuAction::ToggleSmooth;
      if (item == Item::Fullscreen) return MenuAction::ToggleFullscreen;
      return MenuAction::None;
    case Nav::Confirm:
      switch (item) {
        case Item::Resume: return MenuAction::Resume;
        case Item::SaveState: return MenuAction::SaveState;
        case Item::LoadState: return MenuAction::LoadState;
        case Item::Reset: return MenuAction::Reset;
        case Item::Options:
          screen_ = Screen::Options;
          optionSelected_ = 0;
          optionScroll_ = 0;
          return MenuAction::None;
        case Item::Disk: return MenuAction::DiskNext;
        case Item::Smooth: return MenuAction::ToggleSmooth;
        case Item::Fullscreen: return MenuAction::ToggleFullscreen;
        case Item::Quit: return MenuAction::Quit;
      }
  }
  return MenuAction::None;
}

void Menu::render(Canvas& c, const MenuState& state) const {
  c.fill(0, 0, c.width(), c.height(), rgba(8, 9, 14, 200));
  if (screen_ == Screen::Options) {
    renderOptions(c);
    return;
  }
  int y = 28;
  c.text(40, y, Canvas::fit(title_, 2, c.width() - 80), 2, kText);
  y += 22;
  c.text(40, y, core_, 1, kMuted);
  y += 26;
  int count = itemCount(state);
  for (int i = 0; i < count; i++) {
    bool sel = i == selected_;
    if (sel) c.fill(32, y - 4, c.width() - 64, 24, kSelection);
    c.text(44, y, itemLabel(itemAt(i, state), state), 2, sel ? rgba(255, 255, 255) : kText);
    y += 26;
  }
  std::string hint = tr("hint");
  c.text((c.width() - Canvas::measure(hint, 1)) / 2, c.height() - 18, hint, 1, kMuted);
}

void Menu::renderOptions(Canvas& c) const {
  const auto& list = g.options.list();
  int y = 20;
  c.text(24, y, tr("options_title") + " — " + core_, 2, kText);
  y += 22;
  c.text(24, y, Canvas::fit(tr(list.empty() ? "options_none" : "options_hint"), 1, c.width() - 48), 1, kMuted);
  y += 18;

  const int lineHeight = 14;
  int visible = (c.height() - y - 26) / lineHeight;
  int count = (int)list.size() + 1;
  if (optionSelected_ < optionScroll_) optionScroll_ = optionSelected_;
  if (optionSelected_ >= optionScroll_ + visible) optionScroll_ = optionSelected_ - visible + 1;

  for (int row = optionScroll_; row < std::min(count, optionScroll_ + visible); row++) {
    bool sel = row == optionSelected_;
    if (sel) c.fill(16, y - 3, c.width() - 32, lineHeight, kSelection);
    if (row == 0) {
      c.text(24, y, tr("options_reset"), 1, sel ? rgba(255, 255, 255) : kAccent);
    } else {
      const CoreOption& o = list[(size_t)row - 1];
      std::string value = "< " + o.value + " >";
      int valueWidth = Canvas::measure(value, 1);
      int right = c.width() - 24;
      c.text(right - valueWidth, y, value, 1, o.value == o.defaultValue() ? kText : kAccent);
      c.text(24, y, Canvas::fit(o.label, 1, right - valueWidth - 40), 1, sel ? rgba(255, 255, 255) : kText);
    }
    y += lineHeight;
  }
  std::string hint = tr("hint");
  c.text((c.width() - Canvas::measure(hint, 1)) / 2, c.height() - 16, hint, 1, kMuted);
}
