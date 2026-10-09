#include "menu.h"

#include <algorithm>
#include <cctype>
#include <cstring>
#include <utility>

#include "core.h"
#include "input.h"

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
    {"filter", "Image", "Image"},
    // Noms courts : les entrées du menu sont coupées vers 14 caractères.
    {"filter_pixels", "pixels", "pixels"},
    {"filter_sharp", "nette", "sharp"},
    {"filter_soft", "douce", "soft"},
    {"filter_smooth", "lissée", "smooth"},
    {"filter_epx", "EPX", "EPX"},
    {"filter_crt", "CRT", "CRT"},
    {"filter_crtmask", "CRT+", "CRT+"},
    {"filter_lcd", "LCD", "LCD"},
    {"filter_xbr", "xBR", "xBR"},
    {"filter_fsr", "FSR", "FSR"},
    {"aspect", "Format", "Aspect"},
    {"aspect_core", "auto", "auto"},
    {"aspect_4_3", "4:3", "4:3"},
    {"aspect_16_9", "16:9", "16:9"},
    {"aspect_stretch", "étiré", "stretch"},
    {"fullscreen", "Plein écran", "Fullscreen"},
    {"save_quit", "Sauvegarder et quitter", "Save and quit"},
    {"quit", "Quitter", "Quit"},
    {"yes", "Oui", "Yes"},
    {"no", "Non", "No"},
    {"hint", "A / Entrée : OK   B / Échap : retour   Flèches : choisir", "A / Enter: OK   B / Esc: back   Arrows: move"},
    {"options_title", "Options du cœur", "Core options"},
    {"options_hint", "Mémorisées pour ce système. Certaines s'appliquent au redémarrage du jeu.",
     "Saved for this system. Some apply after restarting the game."},
    {"options_none", "Ce cœur n'a pas d'options.", "This core has no options."},
    {"options_reset", "Réinitialiser toutes les options", "Reset all options"},
    {"options_general", "Général", "General"},
    {"options_tabs_hint", "LB / RB ou Pg. préc. / Pg. suiv. : onglet", "LB / RB or Page Up / Page Down: tab"},
    {"state_saved", "État sauvegardé", "State saved"},
    {"state_loaded", "État chargé", "State loaded"},
    {"state_error", "Impossible de sauvegarder ou de charger l'état", "The state could not be saved or loaded"},
    {"no_state", "Aucun état sauvegardé pour ce jeu", "No saved state for this game"},
    {"load_failed", "Le jeu n'a pas pu démarrer avec ce cœur (fichier non pris en charge ou BIOS manquant ?).",
     "The game could not be started with this core (unsupported file or missing BIOS?)."},
    {"core_failed", "Le cœur n'a pas pu être chargé :", "The core could not be loaded:"},
    {"video_failed", "L'affichage OpenGL n'a pas pu être initialisé :", "OpenGL display could not be initialized:"},
    {"keys", "Touches du clavier", "Keyboard keys"},
    {"pad_config", "Configurer la manette", "Set up controller"},
    {"pad_title", "Configuration de la manette", "Controller setup"},
    {"pad_press_any", "Appuyez sur un bouton de la manette à configurer.", "Press any button on the controller to set up."},
    {"pad_step", "Étape", "Step"},
    {"pad_hint", "Appuyez sur ce bouton (ou cette gâchette), puis relâchez-le. Pour passer cette étape, appuyez de nouveau sur le bouton précédent.",
     "Press this button (or trigger), then release it. To skip this step, press the previous button again."},
    {"pad_hint_stick_x", "Poussez le stick vers la droite, puis relâchez-le. Pour passer cette étape, appuyez de nouveau sur le bouton précédent.",
     "Push the stick to the right, then release it. To skip this step, press the previous button again."},
    {"pad_hint_stick_y", "Poussez le stick vers le bas, puis relâchez-le. Pour passer cette étape, appuyez de nouveau sur le bouton précédent.",
     "Push the stick down, then release it. To skip this step, press the previous button again."},
    {"pad_already_used", "Ce bouton est déjà attribué.", "This button is already assigned."},
    {"pad_failed", "La configuration n'a pas pu être appliquée.", "The setup could not be applied."},
    {"pad_saved", "Configuration de la manette enregistrée", "Controller setup saved"},
    {"pad_reset", "Configuration par défaut de la manette rétablie", "Default controller setup restored"},
    {"pad_summary", "Attributions", "Assignments"},
    {"pad_keys", "Tab : passer   Suppr : configuration par défaut   Échap : annuler",
     "Tab: skip   Delete: default setup   Esc: cancel"},
    {"pad_keys_cancel", "Échap : annuler", "Esc: cancel"},
    {"pad_right_x", "Stick droit horizontal", "Right stick horizontal"},
    {"pad_right_y", "Stick droit vertical", "Right stick vertical"},
    {"pad_stick", "Stick droit", "Right stick"},
    {"pad_src_button", "Bouton", "Button"},
    {"pad_src_hat", "Croix", "Hat"},
    {"pad_src_axis", "Axe", "Axis"},
    {"pad_src_inverted", "inversé", "inverted"},
    {"keys_title", "Touches du clavier (joueur 1)", "Keyboard keys (player 1)"},
    {"keys_info", "Touches physiques, mémorisées pour tous les jeux.", "Physical keys, saved for all games."},
    {"keys_reset", "Touches par défaut", "Default keys"},
    {"keys_hint", "A / Entrée : changer   Gauche : aucune touche   B / Échap : retour",
     "A / Enter: change   Left: no key   B / Esc: back"},
    {"keys_wait", "Appuyez sur une touche...   Échap : annuler", "Press a key...   Esc: cancel"},
    {"keys_reserved", "Touche réservée au moteur (Échap, F1, F2, F4, F11)", "Key reserved by the engine (Esc, F1, F2, F4, F11)"},
    {"keys_unknown", "Cette touche n'est pas utilisable", "This key cannot be used"},
    {"key_none", "Aucune", "None"},
    {"btn_up", "Haut", "Up"},
    {"btn_down", "Bas", "Down"},
    {"btn_left", "Gauche", "Left"},
    {"btn_right", "Droite", "Right"},
    {"menu_hint", "Échap ou Start + Select : menu", "Esc or Start + Select: menu"},
    // Jeu à plusieurs en réseau local ({name} : l'autre joueur).
    {"netplay_open", "Proposé sur le réseau local : en attente d'un joueur…", "Offered on the local network: waiting for a player…"},
    {"netplay_waiting", "En attente de {name}…", "Waiting for {name}…"},
    {"netplay_playing", "À deux avec {name}", "Together with {name}"},
    {"netplay_request", "{name} veut rejoindre votre partie en joueur 2", "{name} wants to join your game as player 2"},
    {"netplay_request_hint", "A / Entrée : accepter   B / Échap : refuser", "A / Enter: accept   B / Esc: decline"},
    {"netplay_connecting", "Connexion à {name}…", "Joining {name}…"},
    {"netplay_started", "Partie à deux avec {name}", "Playing with {name}"},
    {"netplay_ended", "Partie avec {name} terminée : vous continuez seul", "Game with {name} ended: you keep playing alone"},
    {"netplay_refused", "{name} a refusé", "{name} declined"},
    {"netplay_busy", "{name} joue déjà avec quelqu'un d'autre", "{name} is already playing with someone else"},
    {"netplay_other_game", "L'autre appareil a une autre version du jeu ou de l'émulateur", "The other device runs a different version of the game or emulator"},
    {"netplay_other_version", "Mettez RomCloud à jour sur les deux appareils pour jouer ensemble", "Update RomCloud on both devices to play together"},
    {"netplay_unreachable", "Impossible de joindre {name}", "Could not reach {name}"},
    {"netplay_core_differs", "Versions de l'émulateur différentes avec {name} : risque de désynchronisation", "Different emulator versions with {name}: the game may get out of sync"},
    {"netplay_unavailable", "Jeu à plusieurs impossible (réseau)", "Playing together is unavailable (network)"},
    {"netplay_leave", "Arrêter la partie à plusieurs", "Stop playing together"},
    {"link_started", "Relié à {name}", "Linked with {name}"},
    {"link_playing", "Relié à {name}", "Linked with {name}"},
    {"link_ended", "Liaison avec {name} terminée", "Link with {name} ended"},
    {"link_request", "{name} veut relier sa console à la vôtre", "{name} wants to link their console with yours"},
};

Menu::Menu(std::string language, std::string title, std::string core)
    : language_(std::move(language)), title_(std::move(title)), core_(std::move(core)) {
  setButtons("");
}

void Menu::setButtons(const std::string& spec) {
  keyRows_.clear();
  size_t start = 0;
  while (start < spec.size()) {
    size_t end = spec.find(',', start);
    if (end == std::string::npos) end = spec.size();
    std::string entry = spec.substr(start, end - start), label;
    start = end + 1;
    size_t eq = entry.find('=');
    if (eq != std::string::npos) {
      label = entry.substr(eq + 1);
      entry.resize(eq);
    }
    for (unsigned id : Input::kButtonOrder) {
      if (entry == Input::buttonName(id)) keyRows_.emplace_back(id, label);
    }
  }
  if (keyRows_.empty()) {
    for (unsigned id : Input::kButtonOrder) keyRows_.emplace_back(id, std::string());
  }
  keySelected_ = 0;
}

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

void Menu::openOptions() {
  screen_ = Screen::Options;
  optionTab_ = 0;
  optionSelected_ = 0;
  optionScroll_ = 0;
}

void Menu::openKeys() {
  screen_ = Screen::Keys;
  keySelected_ = 0;
  waitingKey_ = false;
  keyMessage_.clear();
}

/** Entrées du menu ; partie à plusieurs : rien qui ne changerait que cet appareil. */
std::vector<Menu::Item> Menu::items(const MenuState& state) const {
  std::vector<Item> list = {Item::Resume, Item::SaveState};
  if (!state.together) list.insert(list.end(), {Item::LoadState, Item::Reset, Item::Options});
  list.insert(list.end(), {Item::Keys, Item::Pad});
  if (state.diskCount > 1 && !state.together) list.push_back(Item::Disk);
  list.insert(list.end(), {Item::Filter, Item::Aspect, Item::Fullscreen});
  if (state.netplay) list.push_back(Item::LeaveNetplay);
  list.insert(list.end(), {Item::SaveQuit, Item::Quit});
  return list;
}

int Menu::itemCount(const MenuState& state) const { return (int)items(state).size(); }

Menu::Item Menu::itemAt(int index, const MenuState& state) const { return items(state)[(size_t)index]; }

std::string Menu::itemLabel(Item item, const MenuState& state) const {
  switch (item) {
    case Item::Resume: return tr("resume");
    case Item::SaveState: return tr("save_state");
    case Item::LoadState: return tr("load_state");
    case Item::Reset: return tr("reset");
    case Item::Options: return tr("options");
    case Item::Keys: return tr("keys");
    case Item::Pad: return tr("pad_config");
    case Item::Disk:
      return tr("disk") + " : " + std::to_string(state.diskIndex + 1) + " / " + std::to_string(state.diskCount);
    case Item::Filter: {
      static const char* names[] = {"filter_pixels", "filter_sharp", "filter_soft", "filter_smooth", "filter_epx",
                                    "filter_crt", "filter_crtmask", "filter_lcd", "filter_xbr", "filter_fsr"};
      return tr("filter") + " : " + tr(names[state.filter]);
    }
    case Item::Aspect: {
      static const char* names[] = {"aspect_core", "aspect_4_3", "aspect_16_9", "aspect_stretch"};
      return tr("aspect") + " : " + tr(names[state.aspect]);
    }
    case Item::Fullscreen: return tr("fullscreen") + " : " + tr(state.fullscreen ? "yes" : "no");
    case Item::LeaveNetplay: return tr("netplay_leave");
    case Item::SaveQuit: return tr("save_quit");
    case Item::Quit: return tr("quit");
  }
  return {};
}

MenuAction Menu::handle(Nav nav, const MenuState& state) {
  if (screen_ == Screen::Keys) return handleKeys(nav);
  if (screen_ == Screen::Options) {
    const auto groups = g.options.groups();
    const int tabs = std::max(1, (int)groups.size());
    optionTab_ = std::min(optionTab_, tabs - 1);
    const std::vector<size_t> none;
    const std::vector<size_t>& shown = groups.empty() ? none : groups[(size_t)optionTab_].indices;
    int count = (int)shown.size() + 1;  // + « Réinitialiser »
    optionSelected_ = std::min(optionSelected_, count - 1);
    switch (nav) {
      case Nav::Up: optionSelected_ = (optionSelected_ + count - 1) % count; break;
      case Nav::Down: optionSelected_ = (optionSelected_ + 1) % count; break;
      case Nav::TabPrev:
      case Nav::TabNext:
        optionTab_ = (optionTab_ + (nav == Nav::TabPrev ? tabs - 1 : 1)) % tabs;
        optionSelected_ = 0;
        optionScroll_ = 0;
        break;
      case Nav::Left:
      case Nav::Right:
      case Nav::Confirm:
        if (optionSelected_ == 0) {
          if (nav == Nav::Confirm) g.options.resetAll();
        } else {
          g.options.cycle(shown[(size_t)optionSelected_ - 1], nav == Nav::Left ? -1 : 1);
        }
        break;
      case Nav::Back: screen_ = Screen::Main; break;
    }
    return MenuAction::None;
  }

  // Entrées sur deux colonnes, lues ligne par ligne : haut / bas changent de ligne, gauche /
  // droite de colonne (disque, filtre, format d'image et plein écran changent avec A / Entrée).
  int count = itemCount(state);
  selected_ = std::min(selected_, count - 1);
  Item item = itemAt(selected_, state);
  const int rows = (count + 1) / 2;
  const int row = selected_ / 2, column = selected_ % 2;
  switch (nav) {
    case Nav::Up:
    case Nav::Down: {
      int next = ((row + (nav == Nav::Up ? rows - 1 : 1)) % rows) * 2 + column;
      selected_ = std::min(next, count - 1);  // dernière ligne incomplète : seule entrée
      return MenuAction::None;
    }
    case Nav::Back: return MenuAction::Resume;
    case Nav::TabPrev:
    case Nav::TabNext: return MenuAction::None;
    case Nav::Left:
    case Nav::Right: {
      int next = row * 2 + (1 - column);
      if (next < count) selected_ = next;
      return MenuAction::None;
    }
    case Nav::Confirm:
      switch (item) {
        case Item::Resume: return MenuAction::Resume;
        case Item::SaveState: return MenuAction::SaveState;
        case Item::LoadState: return MenuAction::LoadState;
        case Item::Reset: return MenuAction::Reset;
        case Item::Options:
          openOptions();
          return MenuAction::None;
        case Item::Keys:
          openKeys();
          return MenuAction::None;
        case Item::Pad: return MenuAction::ConfigurePad;
        case Item::Disk: return MenuAction::DiskNext;
        case Item::Filter: return MenuAction::NextFilter;
        case Item::Aspect: return MenuAction::NextAspect;
        case Item::Fullscreen: return MenuAction::ToggleFullscreen;
        case Item::LeaveNetplay: return MenuAction::LeaveNetplay;
        case Item::SaveQuit: return MenuAction::SaveQuit;
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
  if (screen_ == Screen::Keys) {
    renderKeys(c);
    return;
  }
  int y = 32;
  c.text(40, y, Canvas::fit(title_, 2, c.width() - 80), 2, kText);
  y += 22;
  c.text(40, y, core_, 1, kMuted);
  y += 34;
  // Deux colonnes de même largeur, entrées lues ligne par ligne.
  const int margin = 32, gap = 12, rowHeight = 32;
  const int columnWidth = (c.width() - 2 * margin - gap) / 2;
  int count = itemCount(state);
  for (int i = 0; i < count; i++) {
    bool sel = i == selected_;
    int x = margin + (i % 2) * (columnWidth + gap);
    int top = y + (i / 2) * rowHeight;
    c.fill(x, top - 6, columnWidth, 26, sel ? kSelection : rgba(255, 255, 255, 18));
    c.text(x + 10, top, Canvas::fit(itemLabel(itemAt(i, state), state), 2, columnWidth - 20), 2, sel ? rgba(255, 255, 255) : kText);
  }
  std::string hint = tr("hint");
  c.text((c.width() - Canvas::measure(hint, 1)) / 2, c.height() - 18, hint, 1, kMuted);
}

void Menu::renderOptions(Canvas& c) const {
  const auto& list = g.options.list();
  const auto groups = g.options.groups();
  const int tab = std::min(optionTab_, std::max(0, (int)groups.size() - 1));
  const std::vector<size_t> none;
  const std::vector<size_t>& shown = groups.empty() ? none : groups[(size_t)tab].indices;
  int y = 20;
  c.text(24, y, tr("options_title") + " — " + core_, 2, kText);
  y += 22;
  c.text(24, y, Canvas::fit(tr(list.empty() ? "options_none" : "options_hint"), 1, c.width() - 48), 1, kMuted);
  y += 18;

  if (groups.size() > 1) {
    // Barre d'onglets, décalée pour que l'onglet affiché reste visible.
    std::vector<std::string> labels;
    std::vector<int> starts;
    int x = 0;
    for (const auto& group : groups) {
      labels.push_back((group.title.empty() ? tr("options_general") : group.title) + " (" +
                       std::to_string(group.indices.size()) + ")");
      starts.push_back(x);
      x += Canvas::measure(labels.back(), 1) + 16;
    }
    const int left = 16, right = c.width() - 16;
    int shift = std::max(0, starts[(size_t)tab] + Canvas::measure(labels[(size_t)tab], 1) + 16 - (right - left));
    for (size_t i = 0; i < groups.size(); i++) {
      int tx = left + starts[i] - shift, width = Canvas::measure(labels[i], 1) + 16;
      if (tx < left || tx + width > right) continue;
      bool sel = (int)i == tab;
      if (sel) {
        c.fill(tx, y - 4, width - 4, 15, kSelection);
        c.fill(tx, y + 11, width - 4, 2, kAccent);
      }
      c.text(tx + 6, y, labels[i], 1, sel ? rgba(255, 255, 255) : kMuted);
    }
    y += 16;
    c.text(24, y, tr("options_tabs_hint"), 1, kMuted);
    y += 16;
  }

  const int lineHeight = 14;
  int visible = (c.height() - y - 26) / lineHeight;
  int count = (int)shown.size() + 1;
  if (optionSelected_ < optionScroll_) optionScroll_ = optionSelected_;
  if (optionSelected_ >= optionScroll_ + visible) optionScroll_ = optionSelected_ - visible + 1;

  for (int row = optionScroll_; row < std::min(count, optionScroll_ + visible); row++) {
    bool sel = row == optionSelected_;
    if (sel) c.fill(16, y - 3, c.width() - 32, lineHeight, kSelection);
    if (row == 0) {
      c.text(24, y, tr("options_reset"), 1, sel ? rgba(255, 255, 255) : kAccent);
    } else {
      const CoreOption& o = list[shown[(size_t)row - 1]];
      std::string value = "< " + o.display() + " >";
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

// ---------------------------------------------------------------------------
// Touches du clavier : « Touches par défaut », puis un bouton par ligne ; A / Entrée attend la
// touche à attribuer (main.cpp la transmet à keyPressed), Gauche retire la touche du bouton.

MenuAction Menu::handleKeys(Nav nav) {
  const int count = (int)keyRows_.size() + 1;
  keyMessage_.clear();
  switch (nav) {
    case Nav::Up: keySelected_ = (keySelected_ + count - 1) % count; break;
    case Nav::Down: keySelected_ = (keySelected_ + 1) % count; break;
    case Nav::Left:
      if (keySelected_ > 0 && g.input) g.input->assignKey(keyRows_[(size_t)keySelected_ - 1].first, SDL_SCANCODE_UNKNOWN);
      break;
    case Nav::Confirm:
      if (!g.input) break;
      if (keySelected_ == 0) {
        g.input->resetKeys();
        g.input->saveKeys();
      } else {
        waitingKey_ = true;
      }
      break;
    case Nav::Back: screen_ = Screen::Main; break;
    default: break;
  }
  return MenuAction::None;
}

void Menu::keyPressed(SDL_Scancode code) {
  if (code == SDL_SCANCODE_ESCAPE) {
    waitingKey_ = false;
    keyMessage_.clear();
    return;
  }
  if (!Input::assignable(code)) {
    bool reserved = code == SDL_SCANCODE_F1 || code == SDL_SCANCODE_F2 || code == SDL_SCANCODE_F4 || code == SDL_SCANCODE_F11;
    keyMessage_ = tr(reserved ? "keys_reserved" : "keys_unknown");
    return;  // toujours en attente
  }
  keyMessage_.clear();
  waitingKey_ = false;
  if (g.input && keySelected_ > 0) g.input->assignKey(keyRows_[(size_t)keySelected_ - 1].first, code);
}

std::string Menu::keyName(SDL_Scancode code) const {
  if (code == SDL_SCANCODE_UNKNOWN) return tr("key_none");
  if (language_ == "fr") {
    switch (code) {
      case SDL_SCANCODE_UP: return "Flèche haut";
      case SDL_SCANCODE_DOWN: return "Flèche bas";
      case SDL_SCANCODE_LEFT: return "Flèche gauche";
      case SDL_SCANCODE_RIGHT: return "Flèche droite";
      case SDL_SCANCODE_RETURN: return "Entrée";
      case SDL_SCANCODE_SPACE: return "Espace";
      case SDL_SCANCODE_BACKSPACE: return "Retour arrière";
      case SDL_SCANCODE_LSHIFT: return "Maj gauche";
      case SDL_SCANCODE_RSHIFT: return "Maj droite";
      case SDL_SCANCODE_LCTRL: return "Ctrl gauche";
      case SDL_SCANCODE_RCTRL: return "Ctrl droite";
      case SDL_SCANCODE_LALT: return "Alt";
      case SDL_SCANCODE_RALT: return "Alt Gr";
      case SDL_SCANCODE_CAPSLOCK: return "Verr. Maj";
      case SDL_SCANCODE_DELETE: return "Suppr";
      case SDL_SCANCODE_INSERT: return "Inser";
      case SDL_SCANCODE_HOME: return "Début";
      case SDL_SCANCODE_END: return "Fin";
      default: break;
    }
  }
  // Nom selon la disposition du clavier (touche Q d'un AZERTY : « A »).
  const char* name = SDL_GetKeyName(SDL_GetKeyFromScancode(code, SDL_KMOD_NONE, false));
  return name && *name ? std::string(name) : "#" + std::to_string((int)code);
}

/** Libellé d'un bouton du RetroPad : Haut… Droite traduits, Start, Select, sinon le nom en capitales. */
std::string Menu::buttonLabel(unsigned id) const {
  std::string name = Input::buttonName(id);
  if (id >= RETRO_DEVICE_ID_JOYPAD_UP && id <= RETRO_DEVICE_ID_JOYPAD_RIGHT) return tr(("btn_" + name).c_str());
  if (name == "start") return "Start";
  if (name == "select") return "Select";
  for (auto& ch : name) ch = (char)toupper((unsigned char)ch);
  return name;
}

void Menu::renderKeys(Canvas& c) const {
  int y = 20;
  c.text(24, y, tr("keys_title"), 2, kText);
  y += 22;
  c.text(24, y, Canvas::fit(tr("keys_info"), 1, c.width() - 48), 1, kMuted);
  y += 22;
  const int lineHeight = 16, count = (int)keyRows_.size() + 1;
  const int labelWidth = 120, right = c.width() - 24;
  for (int row = 0; row < count; row++) {
    bool sel = row == keySelected_;
    if (sel) c.fill(16, y - 4, c.width() - 32, lineHeight, kSelection);
    if (row == 0) {
      c.text(24, y, tr("keys_reset"), 1, sel ? rgba(255, 255, 255) : kAccent);
    } else {
      const auto& [id, name] = keyRows_[(size_t)row - 1];
      SDL_Scancode code = g.input ? g.input->keyOf(id) : SDL_SCANCODE_UNKNOWN;
      std::string value = sel && waitingKey_ ? tr("keys_wait") : keyName(code);
      c.text(24, y, name.empty() ? buttonLabel(id) : name, 1, sel ? rgba(255, 255, 255) : kText);
      c.text(24 + labelWidth, y, Canvas::fit(value, 1, right - 24 - labelWidth), 1,
             sel ? rgba(255, 255, 255) : code == SDL_SCANCODE_UNKNOWN ? kMuted : kAccent);
    }
    y += lineHeight;
  }
  if (!keyMessage_.empty()) c.text(24, y + 6, Canvas::fit(keyMessage_, 1, c.width() - 48), 1, rgba(255, 140, 140));
  std::string hint = tr("keys_hint");
  c.text((c.width() - Canvas::measure(hint, 1)) / 2, c.height() - 16, hint, 1, kMuted);
}
