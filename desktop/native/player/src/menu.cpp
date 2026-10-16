#include "menu.h"

#include <cmath>

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
    {"filter_pixels", "Nearest", "Nearest"},
    {"filter_sharp", "Sharp Bilinear", "Sharp Bilinear"},
    {"filter_soft", "Bicubic", "Bicubic"},
    {"filter_smooth", "Bilinear", "Bilinear"},
    {"filter_epx", "Scale2x", "Scale2x"},
    {"filter_crt", "CRT", "CRT"},
    {"filter_crtmask", "CRT", "CRT"},
    {"filter_lcd", "LCD", "LCD"},
    {"filter_xbr", "xBR", "xBR"},
    {"filter_fsr", "FSR", "FSR"},
    {"filter_cut", "CUT", "CUT"},
    {"filter_cut2", "CUT2", "CUT2"},
    {"filter_cut3", "CUT3", "CUT3"},
    {"filter_handheld", "GBA / GBC", "GBA / GBC"},
    {"filter_dotmatrix", "Game Boy", "Game Boy"},
    {"aspect", "Format", "Aspect"},
    {"aspect_core", "auto", "auto"},
    {"aspect_4_3", "4:3", "4:3"},
    {"aspect_16_9", "16:9", "16:9"},
    {"aspect_stretch", "étiré", "stretch"},
    {"runahead", "Run-ahead", "Run-ahead"},
    {"runahead_off", "non", "off"},
    {"runahead_unsupported", "Run-ahead impossible : ce cœur n'enregistre pas d'états", "Run-ahead unavailable: this core cannot save states"},
    {"runahead_failed", "Run-ahead coupé : état du cœur non enregistré", "Run-ahead turned off: the core state could not be saved"},
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
    {"states_title", "Charger un état", "Load a state"},
    {"states_info", "États de ce PC et de votre profil en ligne (téléchargés avant la partie).",
     "States of this PC and of your online profile (downloaded before playing)."},
    {"states_hint", "A / Entrée : charger   B / Échap : retour", "A / Enter: load   B / Esc: back"},
    {"this_pc", "Ce PC", "This PC"},
    {"pinned", "épinglé", "pinned"},
    {"load_failed", "Le jeu n'a pas pu démarrer avec ce cœur (fichier non pris en charge ou BIOS manquant ?).",
     "The game could not be started with this core (unsupported file or missing BIOS?)."},
    {"core_failed", "Le cœur n'a pas pu être chargé :", "The core could not be loaded:"},
    {"video_failed", "L'affichage OpenGL n'a pas pu être initialisé :", "OpenGL display could not be initialized:"},
    {"keys", "Touches du clavier", "Keyboard keys"},
    {"pad_config", "Configurer la manette", "Set up controller"},
    {"players", "Joueurs des manettes", "Controller players"},
    {"shader_settings", "Réglages", "Settings"},
    {"sound", "Son", "Sound"},
    {"sound_settings", "Son (tous les émulateurs)", "Sound (all emulators)"},
    {"volume", "Volume", "Volume"},
    {"filter_list", "Shader", "Shader"},
    {"image_hint", "A : choisir   Gauche / droite : régler", "A: choose   Left / right: adjust"},
    {"image_hint2", "LB / RB : autre shader   B : retour", "LB / RB: other shader   B: back"},
    {"fd_pixels", "Pixels bruts (plus proche voisin), sans lissage.", "Raw pixels (nearest neighbor), no smoothing."},
    {"fd_sharp", "Pixels nets et de même taille, bordure lissée. Conseillé.", "Sharp, even pixels with smoothed edges. Recommended."},
    {"fd_soft", "Lissage bicubique (Mitchell-Netravali).", "Bicubic smoothing (Mitchell-Netravali)."},
    {"fd_smooth", "Lissage bilinéaire, flou.", "Blurry bilinear smoothing."},
    {"fd_epx", "EPX : contours du pixel art arrondis.", "EPX: rounded pixel art edges."},
    {"fd_crt", "Téléviseur cathodique : lignes de balayage, masque RGB.", "CRT television: scanlines, RGB mask."},
    {"fd_crtmask", "Téléviseur cathodique : lignes de balayage, masque RGB.", "CRT television: scanlines, RGB mask."},
    {"fd_lcd", "Écran de console portable : grille.", "Handheld console screen: grid."},
    {"fd_xbr", "Contours redessinés en diagonale (niveau 1).", "Edges redrawn diagonally (level 1)."},
    {"fd_fsr", "AMD FidelityFX Super Resolution 1 (jeux en 3D).", "AMD FidelityFX Super Resolution 1 (3D games)."},
    {"fd_cut", "Lissage des contours en une passe (LibretroDroid), doux.", "One-pass edge smoothing (LibretroDroid), soft."},
    {"fd_cut2", "Lissage des contours en deux passes, proche d'un dessin.", "Two-pass edge smoothing, close to a drawing."},
    {"fd_cut3", "Lissage des contours en trois passes (celui de Lemuroid).", "Three-pass edge smoothing (Lemuroid's)."},
    {"fd_handheld", "Écran de GBA / GBC : couleurs d'origine, grille, rémanence.", "GBA / GBC screen: original colors, grid, ghosting."},
    {"fd_dotmatrix", "Game Boy : palette verte, matrice de points, rémanence.", "Game Boy: green palette, dot matrix, ghosting."},
    {"fp_softness", "Largeur de la transition", "Transition width"},
    {"fp_scanlines", "Lignes de balayage", "Scanlines"},
    {"fp_glow", "Éclat des lignes", "Scanline glow"},
    {"fp_mask", "Masque RGB", "RGB mask"},
    {"fp_curvature", "Courbure de l'écran", "Screen curvature"},
    {"fp_vignette", "Coins assombris", "Vignette"},
    {"fp_grid", "Grille", "Grid"},
    {"fp_grid_width", "Épaisseur de la grille", "Grid width"},
    {"fp_sharpness", "Netteté", "Sharpness"},
    {"fp_blur", "Flou (B)", "Blur (B)"},
    {"fp_ringing", "Netteté (C)", "Sharpness (C)"},
    {"fp_threshold", "Seuil des couleurs", "Color threshold"},
    {"fp_strength", "Force", "Strength"},
    {"fp_edge_softness", "Douceur des contours", "Edge softness"},
    {"fp_ghosting", "Rémanence", "Ghosting"},
    {"fp_screen_colors", "Couleurs de l'écran", "Screen colors"},
    {"fp_screen", "Écran", "Screen"},
    {"fc_gba", "GBA", "GBA"},
    {"fc_gbc", "GBC", "GBC"},
    {"fp_palette", "Palette", "Palette"},
    {"fc_dmg", "Game Boy", "Game Boy"},
    {"fc_pocket", "Pocket", "Pocket"},
    {"fc_game_colors", "Couleurs du jeu", "Game colors"},
    {"fp_dots", "Points", "Dots"},
    {"fp_dot_gap", "Espace entre les points", "Dot gap"},
    {"fp_dynamic_blend", "Mélange dynamique", "Dynamic blend"},
    {"fp_blend_min_contrast", "Contraste min. du mélange", "Blend min. contrast"},
    {"fp_blend_max_contrast", "Contraste max. du mélange", "Blend max. contrast"},
    {"fp_blend_min_sharpness", "Netteté min. du mélange", "Blend min. sharpness"},
    {"fp_blend_max_sharpness", "Netteté max. du mélange", "Blend max. sharpness"},
    {"fp_static_sharpness", "Netteté fixe", "Static sharpness"},
    {"fp_fast_luma", "Luminance rapide", "Fast luma"},
    {"fp_edge_min_value", "Seuil des contours", "Edge threshold"},
    {"fp_edge_min_contrast", "Contraste des contours", "Edge contrast"},
    {"fp_soft_edges", "Renfort des contours doux", "Soft edge sharpening"},
    {"fp_soft_edges_amount", "Force du renfort", "Sharpening amount"},
    {"fp_hard_edges_error", "Tolérance des contours nets", "Hard edge tolerance"},
    {"fp_hard_edges_distance", "Distance de recherche", "Search distance"},
    {"fp_dedither", "Lissage du tramage", "Dither smoothing"},
    {"fp_brightness", "Luminosité", "Brightness"},
    {"fp_contrast", "Contraste", "Contrast"},
    {"fp_saturation", "Saturation", "Saturation"},
    {"fp_gamma", "Gamma", "Gamma"},
    {"fp_defaults", "Valeurs par défaut", "Default values"},
    {"fp_hint", "Gauche / droite : régler   Échap : retour", "Left / right: adjust   Esc: back"},
    {"players_title", "Joueurs des manettes", "Controller players"},
    {"players_press", "Appuyez sur un bouton de la manette du joueur", "Press a button on the controller of player"},
    {"players_none", "Aucune manette branchée.", "No controller connected."},
    {"players_player", "Joueur", "Player"},
    {"players_keyboard", "Le clavier commande toujours le joueur 1.", "The keyboard always controls player 1."},
    {"players_keys", "Même manette ou Entrée : terminer   Échap : annuler", "Same controller or Enter: done   Esc: cancel"},
    {"players_saved", "Joueurs des manettes enregistrés", "Controller players saved"},
    {"pad_title", "Configuration de la manette", "Controller setup"},
    {"pad_press_any", "Appuyez sur un bouton de la manette à configurer.", "Press any button on the controller to set up."},
    {"pad_step", "Étape", "Step"},
    {"pad_hint", "Appuyez sur ce bouton (ou cette gâchette), puis relâchez-le. Pour passer cette étape, appuyez de nouveau sur le bouton précédent.",
     "Press this button (or trigger), then release it. To skip this step, press the previous button again."},
    {"pad_hint_stick_x", "Poussez le stick vers la droite, puis relâchez-le. Pour passer cette étape, appuyez de nouveau sur le bouton précédent.",
     "Push the stick to the right, then release it. To skip this step, press the previous button again."},
    {"pad_hint_half", "Appuyez sur ce bouton, ou poussez le stick dans cette direction, puis relâchez-le. Pour passer cette étape, appuyez de nouveau sur le bouton précédent.",
     "Press this button, or push the stick in this direction, then release it. To skip this step, press the previous button again."},
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
    {"pad_left_x", "Stick gauche horizontal", "Left stick horizontal"},
    {"pad_left_y", "Stick gauche vertical", "Left stick vertical"},
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
    {"netplay_paused_title", "Pause", "Paused"},
    {"netplay_remote_paused", "{name} a mis le jeu en pause", "{name} paused the game"},
    {"netplay_playing", "À deux avec {name}", "Together with {name}"},
    {"netplay_request", "{name} veut rejoindre votre partie en joueur 2", "{name} wants to join your game as player 2"},
    {"netplay_joined", "{name} a rejoint votre partie", "{name} joined your game"},
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

void Menu::openStates(std::vector<std::string> rows) {
  screen_ = Screen::States;
  stateRows_ = std::move(rows);
  stateSelected_ = 0;
  stateScroll_ = 0;
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
  list.insert(list.end(), {Item::Keys, Item::Pad, Item::Players});
  if (state.diskCount > 1 && !state.together) list.push_back(Item::Disk);
  list.push_back(Item::Filter);
  list.push_back(Item::Aspect);
  // Partie à plusieurs : images rejouées par l'échange des touches, sans run-ahead.
  if (!state.together) list.push_back(Item::RunAhead);
  list.insert(list.end(), {Item::Fullscreen, Item::Sound});
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
    case Item::Players: return tr("players");
    case Item::Disk:
      return tr("disk") + " : " + std::to_string(state.diskIndex + 1) + " / " + std::to_string(state.diskCount);
    case Item::Filter: return tr("filter") + " : " + tr(state.filterLabel);
    case Item::Aspect: {
      static const char* names[] = {"aspect_core", "aspect_4_3", "aspect_16_9", "aspect_stretch"};
      return tr("aspect") + " : " + tr(names[state.aspect]);
    }
    case Item::RunAhead: return tr("runahead") + " : " + (state.runAhead ? std::to_string(state.runAhead) : tr("runahead_off"));
    case Item::Fullscreen: return tr("fullscreen") + " : " + tr(state.fullscreen ? "yes" : "no");
    case Item::Sound: return tr("sound") + " : " + std::to_string(state.volume) + " %";
    case Item::LeaveNetplay: return tr("netplay_leave");
    case Item::SaveQuit: return tr("save_quit");
    case Item::Quit: return tr("quit");
  }
  return {};
}

void Menu::openSoundSettings(std::vector<FilterRow> rows) {
  screen_ = Screen::FilterSettings;
  sound_ = true;
  filterChoices_.clear();
  filterRows_ = std::move(rows);
  filterSelected_ = 0;
  filterScroll_ = 0;
  filterChanged_ = -1;
}

void Menu::openImageSettings(std::vector<std::pair<std::string, std::string>> choices, int chosen, std::vector<FilterRow> rows) {
  screen_ = Screen::FilterSettings;
  sound_ = false;
  filterChoices_ = std::move(choices);
  filterChosen_ = chosen;
  filterRows_ = std::move(rows);
  filterSelected_ = chosen;  // curseur sur le filtre affiché
  filterScroll_ = 0;
  filterChanged_ = -1;
}

void Menu::setFilterRows(int chosen, std::vector<FilterRow> rows) {
  filterChosen_ = chosen;
  filterRows_ = std::move(rows);
  filterChanged_ = -1;
}

MenuAction Menu::handleFilterSettings(Nav nav) {
  // Lignes : filtres (écran « Image »), réglages, puis « Valeurs par défaut ».
  const int choices = (int)filterChoices_.size();
  const int count = choices + (int)filterRows_.size() + 1;
  filterSelected_ = std::min(filterSelected_, count - 1);
  switch (nav) {
    case Nav::Up: filterSelected_ = (filterSelected_ + count - 1) % count; break;
    case Nav::Down: filterSelected_ = (filterSelected_ + 1) % count; break;
    case Nav::TabPrev:
    case Nav::TabNext:
      // LB / RB : filtre précédent ou suivant, d'où que soit le curseur.
      if (choices < 2) break;
      filterChoice_ = (filterChosen_ + (nav == Nav::TabPrev ? choices - 1 : 1)) % choices;
      if (filterSelected_ < choices) filterSelected_ = filterChoice_;
      return MenuAction::FilterChoice;
    case Nav::Left:
    case Nav::Right:
    case Nav::Confirm: {
      if (filterSelected_ < choices) {
        if (nav != Nav::Confirm || filterSelected_ == filterChosen_) break;
        filterChoice_ = filterSelected_;
        return MenuAction::FilterChoice;
      }
      if (filterSelected_ == count - 1) {
        if (nav != Nav::Confirm) break;
        for (auto& row : filterRows_) row.value = row.def;
        return MenuAction::FilterDefaults;
      }
      if (nav == Nav::Confirm) break;
      const int index = filterSelected_ - choices;
      FilterRow& row = filterRows_[(size_t)index];
      float next = row.value + (nav == Nav::Left ? -row.step : row.step);
      next = std::round(next / row.step) * row.step;  // pas exacts malgré les arrondis
      next = std::min(row.max, std::max(row.min, next));
      if (next == row.value) break;
      row.value = next;
      filterChanged_ = index;
      return MenuAction::FilterParam;
    }
    case Nav::Back: screen_ = Screen::Main; break;
  }
  return MenuAction::None;
}

/**
 * Ligne d'un réglage : libellé, barre de la valeur entre ses bornes, valeur en pourcentage, en nombre
 * entier ou nom du choix.
 */
static void renderFilterRow(Canvas& c, int x, int y, int width, const FilterRow& row, bool sel) {
  std::string value = std::to_string((int)std::lround(row.value * 100)) + " %";
  const int index = (int)std::lround(row.value - row.min);
  if (!row.choices.empty() && index >= 0 && index < (int)row.choices.size()) value = row.choices[(size_t)index];
  else if (row.integer) value = std::to_string((int)std::lround(row.value));
  const int valueWidth = std::max(46, Canvas::measure(value, 1));
  const int barWidth = std::min(220, width / 4), barX = x + width - barWidth - valueWidth - 10;
  const uint32_t color = sel ? rgba(255, 255, 255) : kText;
  c.text(x + 8, y, Canvas::fit(row.label, 1, barX - x - 16), 1, color);
  const float t = row.max > row.min ? (row.value - row.min) / (row.max - row.min) : 0.0f;
  c.fill(barX, y + 2, barWidth, 4, rgba(255, 255, 255, 50));
  c.fill(barX, y + 2, (int)(barWidth * t), 4, kText);
  c.text(barX + barWidth + 10, y, value, 1, color);
}

void Menu::renderFilterSettings(Canvas& c) const {
  const int rowHeight = 20;
  if (sound_) {
    // Son : panneau en bas.
    const int count = (int)filterRows_.size() + 1;
    const int height = 52 + count * rowHeight + 20;
    const int top = c.height() - height;
    c.fill(0, top, c.width(), height, rgba(8, 9, 14, 215));
    int y = top + 14;
    c.text(24, y, Canvas::fit(tr("sound_settings"), 2, c.width() - 48), 2, kText);
    y += 34;
    for (int i = 0; i < count; i++) {
      const bool sel = i == filterSelected_;
      c.fill(16, y - 5, c.width() - 32, 18, sel ? kSelection : rgba(255, 255, 255, 18));
      if (i == count - 1) {
        c.text(24, y, tr("fp_defaults"), 1, sel ? rgba(255, 255, 255) : kText);
      } else {
        renderFilterRow(c, 16, y, c.width() - 32, filterRows_[(size_t)i], sel);
      }
      y += rowHeight;
    }
    std::string hint = tr("fp_hint");
    c.text((c.width() - Canvas::measure(hint, 1)) / 2, c.height() - 14, hint, 1, kMuted);
    return;
  }

  // Image : panneau à droite, le jeu reste visible à gauche pour juger de l'effet. Lignes : titre
  // de la liste, filtres, description du filtre affiché, titre des réglages, réglages, valeurs par
  // défaut ; défilement si elles dépassent.
  const int width = std::clamp(c.width() * 9 / 20, 380, 520), x = c.width() - width;
  c.fill(x, 0, width, c.height(), rgba(8, 9, 14, 220));
  c.text(x + 16, 14, tr("filter"), 2, kText);
  const int choices = (int)filterChoices_.size();
  const bool hasChosen = filterChosen_ >= 0 && filterChosen_ < choices;
  struct Line {
    int row;  // ligne sélectionnable (-1 : titre ou description)
    std::string text;
  };
  std::vector<Line> lines;
  lines.push_back({-1, tr("filter_list")});
  for (int i = 0; i < choices; i++) lines.push_back({i, filterChoices_[(size_t)i].first});
  if (hasChosen) lines.push_back({-1, filterChoices_[(size_t)filterChosen_].second});
  lines.push_back({-1, tr("shader_settings") + (hasChosen ? " — " + filterChoices_[(size_t)filterChosen_].first : std::string())});
  for (int i = 0; i < (int)filterRows_.size(); i++) lines.push_back({choices + i, std::string()});
  lines.push_back({choices + (int)filterRows_.size(), tr("fp_defaults")});

  const int top = 44, bottom = c.height() - 40;
  const int visible = std::max(1, (bottom - top) / rowHeight);
  int selectedLine = 0;
  for (int i = 0; i < (int)lines.size(); i++) {
    if (lines[(size_t)i].row == filterSelected_) selectedLine = i;
  }
  // Titres juste au-dessus de la ligne choisie gardés visibles (titre de la liste, des réglages).
  int wanted = selectedLine;
  while (wanted > 0 && lines[(size_t)wanted - 1].row < 0) wanted--;
  if (wanted < filterScroll_) filterScroll_ = wanted;
  if (selectedLine >= filterScroll_ + visible) filterScroll_ = selectedLine - visible + 1;
  filterScroll_ = std::clamp(filterScroll_, 0, std::max(0, (int)lines.size() - visible));

  int y = top;
  for (int i = filterScroll_; i < std::min((int)lines.size(), filterScroll_ + visible); i++) {
    const Line& line = lines[(size_t)i];
    if (line.row < 0) {
      c.text(x + 16, y + 2, Canvas::fit(line.text, 1, width - 32), 1, kMuted);
      y += rowHeight;
      continue;
    }
    const bool sel = line.row == filterSelected_;
    c.fill(x + 8, y - 5, width - 16, 18, sel ? kSelection : rgba(255, 255, 255, 18));
    const uint32_t color = sel ? rgba(255, 255, 255) : kText;
    if (line.row < choices) {
      // Filtre affiché repéré par un carré.
      const bool chosen = line.row == filterChosen_;
      if (chosen) c.fill(x + 16, y + 1, 6, 6, sel ? rgba(255, 255, 255) : kAccent);
      c.text(x + 30, y, Canvas::fit(line.text, 1, width - 46), 1, chosen && !sel ? kAccent : color);
    } else if (line.row < choices + (int)filterRows_.size()) {
      renderFilterRow(c, x + 8, y, width - 16, filterRows_[(size_t)(line.row - choices)], sel);
    } else {
      c.text(x + 16, y, line.text, 1, color);
    }
    y += rowHeight;
  }
  for (int i = 0; i < 2; i++) {
    std::string hint = Canvas::fit(tr(i ? "image_hint2" : "image_hint"), 1, width - 24);
    c.text(x + (width - Canvas::measure(hint, 1)) / 2, c.height() - 30 + i * 14, hint, 1, kMuted);
  }
}

MenuAction Menu::handle(Nav nav, const MenuState& state) {
  if (screen_ == Screen::FilterSettings) return handleFilterSettings(nav);
  if (screen_ == Screen::Keys) return handleKeys(nav);
  if (screen_ == Screen::States) {
    const int count = (int)stateRows_.size();
    switch (nav) {
      case Nav::Up: if (count) stateSelected_ = (stateSelected_ + count - 1) % count; break;
      case Nav::Down: if (count) stateSelected_ = (stateSelected_ + 1) % count; break;
      case Nav::Confirm: if (count) return MenuAction::LoadHistoryState; break;
      case Nav::Back: screen_ = Screen::Main; break;
      default: break;
    }
    return MenuAction::None;
  }
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
        case Item::Players: return MenuAction::Players;
        case Item::Disk: return MenuAction::DiskNext;
        case Item::Filter: return MenuAction::ImageSettings;
        case Item::Aspect: return MenuAction::NextAspect;
        case Item::RunAhead: return MenuAction::NextRunAhead;
        case Item::Fullscreen: return MenuAction::ToggleFullscreen;
        case Item::Sound: return MenuAction::SoundSettings;
        case Item::LeaveNetplay: return MenuAction::LeaveNetplay;
        case Item::SaveQuit: return MenuAction::SaveQuit;
        case Item::Quit: return MenuAction::Quit;
      }
  }
  return MenuAction::None;
}

void Menu::render(Canvas& c, const MenuState& state) const {
  if (screen_ == Screen::FilterSettings) {
    renderFilterSettings(c);
    return;
  }
  c.fill(0, 0, c.width(), c.height(), rgba(8, 9, 14, 200));
  if (screen_ == Screen::Options) {
    renderOptions(c);
    return;
  }
  if (screen_ == Screen::Keys) {
    renderKeys(c);
    return;
  }
  if (screen_ == Screen::States) {
    renderStates(c);
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

void Menu::renderStates(Canvas& c) const {
  int y = 20;
  c.text(24, y, tr("states_title") + " — " + title_, 2, kText);
  y += 22;
  c.text(24, y, Canvas::fit(tr("states_info"), 1, c.width() - 48), 1, kMuted);
  y += 22;
  if (stateRows_.empty()) c.text(24, y, tr("no_state"), 2, kText);
  const int lineHeight = 26;
  int visible = std::max(1, (c.height() - y - 26) / lineHeight);
  if (stateSelected_ < stateScroll_) stateScroll_ = stateSelected_;
  if (stateSelected_ >= stateScroll_ + visible) stateScroll_ = stateSelected_ - visible + 1;
  for (int row = stateScroll_; row < std::min((int)stateRows_.size(), stateScroll_ + visible); row++) {
    bool sel = row == stateSelected_;
    c.fill(16, y - 6, c.width() - 32, 22, sel ? kSelection : rgba(255, 255, 255, 18));
    c.text(24, y, Canvas::fit(stateRows_[(size_t)row], 2, c.width() - 48), 2, sel ? rgba(255, 255, 255) : kText);
    y += lineHeight;
  }
  std::string hint = tr("states_hint");
  c.text((c.width() - Canvas::measure(hint, 1)) / 2, c.height() - 16, hint, 1, kMuted);
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
