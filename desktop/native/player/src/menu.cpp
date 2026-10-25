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
    {"filter_dlss", "DLSS", "DLSS"},
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
    {"runahead_off", "Non", "Off"},
    {"runahead_unsupported", "Run-ahead impossible : ce cœur n'enregistre pas d'états", "Run-ahead unavailable: this core cannot save states"},
    {"runahead_failed", "Run-ahead coupé : état du cœur non enregistré", "Run-ahead turned off: the core state could not be saved"},
    {"fullscreen", "Plein écran", "Fullscreen"},
    {"save_quit", "Sauvegarder et quitter", "Save and quit"},
    {"quit", "Quitter", "Quit"},
    {"yes", "Oui", "Yes"},
    {"no", "Non", "No"},
    {"hint", "A / Entrée : OK   B / Échap : retour   Flèches : choisir", "A / Enter: OK   B / Esc: back   Arrows: move"},
    {"main_hint", "A / Entrée : OK   B / Échap : retour au jeu", "A / Enter: OK   B / Esc: back to game"},
    {"options_title", "Options du cœur", "Core options"},
    {"options_hint", "Mémorisées pour ce système. Certaines s'appliquent au redémarrage du jeu.",
     "Saved for this system. Some apply after restarting the game."},
    {"options_none", "Ce cœur n'a pas d'options.", "This core has no options."},
    {"options_reset", "Réinitialiser toutes les options", "Reset all options"},
    {"options_reset_confirm", "Appuyez de nouveau sur A / Entrée pour tout réinitialiser", "Press A / Enter again to reset everything"},
    {"options_general", "Général", "General"},
    {"options_resolution", "Résolution", "Resolution"},
    {"options_tabs_hint", "LB / RB ou Pg. préc. / Pg. suiv. : onglet   Tapez du texte ou Y : rechercher",
     "LB / RB or Page Up / Page Down: tab   Type text or Y: search"},
    {"options_search_hint", "Tapez du texte ou Y : rechercher", "Type text or Y: search"},
    {"search", "Recherche", "Search"},
    {"search_count", "option(s)", "option(s)"},
    {"search_none", "Aucune option ne correspond à la recherche.", "No option matches the search."},
    {"search_hint", "A / gauche / droite : changer   Y : clavier   B / Échap : effacer",
     "A / left / right: change   Y: keyboard   B / Esc: clear"},
    {"kb_space", "Espace", "Space"},
    {"kb_erase", "Effacer", "Erase"},
    {"kb_done", "Terminer", "Done"},
    {"kb_hint", "A : taper   X : effacer   Y / B : fermer le clavier", "A: type   X: erase   Y / B: close keyboard"},
    {"resolution", "Résolution interne", "Internal resolution"},
    {"textures", "Textures (texte, interface)", "Textures (text, HUD)"},
    {"options_textures", "Textures", "Textures"},
    {"other_renderer", "autre rendu", "other renderer"},
    {"resolution_restart", "S'applique au redémarrage du jeu (« Redémarrer le jeu »)", "Applies when the game restarts (\"Restart game\")"},
    {"textures_hint", "Texte, interface et logos lissés (xBRZ, HQ4x, MMPX…)", "Smoother text, HUD and logos (xBRZ, HQ4x, MMPX…)"},
    {"renderer_hint", "Pas avec ce rendu : A pour choisir le rendu (GLideN64…)", "Not with this renderer: A to pick the renderer (GLideN64…)"},
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
    {"fd_xbr", "Contours lissés en diagonale, 17 versions.", "Diagonal edge smoothing, 17 versions."},
    {"fd_fsr", "AMD FidelityFX Super Resolution 1 (jeux en 3D).", "AMD FidelityFX Super Resolution 1 (3D games)."},
    {"fd_dlss", "IA NVIDIA DLSS (GeForce RTX), sinon FSR.", "NVIDIA DLSS AI (GeForce RTX), else FSR."},
    {"dlss_downloading", "Téléchargement de NVIDIA DLSS (59 Mo)… FSR en attendant", "Downloading NVIDIA DLSS (59 MB)… FSR meanwhile"},
    {"dlss_ready", "NVIDIA DLSS actif", "NVIDIA DLSS active"},
    {"dlss_unsupported", "DLSS : carte NVIDIA GeForce RTX requise, FSR utilisé", "DLSS: NVIDIA GeForce RTX card required, using FSR"},
    {"dlss_download_failed", "Téléchargement de NVIDIA DLSS impossible, FSR utilisé", "NVIDIA DLSS could not be downloaded, using FSR"},
    {"dlss_failed", "DLSS impossible (voir player.log), FSR utilisé", "DLSS unavailable (see player.log), using FSR"},
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
    {"fp_version", "Version", "Version"},
    {"fp_strength_lv1", "Force (lv1)", "Strength (lv1)"},
    {"fp_edge_softness_lv1", "Douceur des contours (lv1)", "Edge softness (lv1)"},
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
  resetArmed_ = false;
  query_.clear();
  keyboard_ = false;
}

/** Texte en minuscules sans accents (Latin-1 en UTF-8 : « Résolution » -> « resolution »), pour la recherche. */
static std::string fold(const std::string& s) {
  // U+00C0 à U+00FF (0xC3 suivi de 0x80 à 0xBF) : lettre de base.
  static const char kBase[] = "aaaaaaaceeeeiiiidnooooo*ouuuuytsaaaaaaaceeeeiiiidnooooo/ouuuuyty";
  std::string out;
  for (size_t i = 0; i < s.size(); i++) {
    const unsigned char c = (unsigned char)s[i];
    if (c == 0xC3 && i + 1 < s.size()) out += kBase[(unsigned char)s[++i] & 0x3F];
    else out += (char)std::tolower(c);
  }
  return out;
}

/** Mots de la recherche (séparés par des espaces), sans accents. */
static std::vector<std::string> searchWords(const std::string& query) {
  std::vector<std::string> words;
  std::string word;
  for (char c : fold(query) + " ") {
    if (c != ' ') {
      word += c;
    } else if (!word.empty()) {
      words.push_back(word);
      word.clear();
    }
  }
  return words;
}

std::vector<CoreOptionGroup> Menu::optionTabs() const {
  std::vector<CoreOptionGroup> tabs;
  std::vector<size_t> resolution = g.options.enhancementOptions(Enhancement::Resolution);
  if (!resolution.empty()) tabs.push_back({tr("options_resolution"), std::move(resolution)});
  std::vector<size_t> textures = g.options.enhancementOptions(Enhancement::Textures);
  if (!textures.empty()) tabs.push_back({tr("options_textures"), std::move(textures)});
  for (auto& group : g.options.groups()) {
    if (group.title.empty()) group.title = tr("options_general");
    tabs.push_back(std::move(group));
  }
  return tabs;
}

std::vector<size_t> Menu::shownOptions(const std::vector<CoreOptionGroup>& tabs) const {
  if (query_.empty()) {
    if (tabs.empty()) return {};
    return tabs[(size_t)std::min(optionTab_, (int)tabs.size() - 1)].indices;
  }
  // Tous les mots dans le libellé, la clé, les onglets ou la description ; ceux trouvés dans le
  // libellé d'abord, puis dans la clé ou les onglets, puis dans la description.
  const auto& list = g.options.list();
  std::vector<std::string> tabOf(list.size());
  for (const auto& tab : tabs) {
    for (size_t i : tab.indices) tabOf[i] += " " + tab.title;
  }
  const std::vector<std::string> words = searchWords(query_);
  std::vector<std::pair<int, size_t>> found;
  for (size_t i = 0; i < list.size(); i++) {
    const std::string label = fold(list[i].label), named = label + " " + fold(list[i].key + " " + tabOf[i]);
    const std::string all = named + " " + fold(list[i].info);
    int score = 0;
    for (const auto& word : words) {
      if (label.find(word) != std::string::npos) continue;
      if (named.find(word) != std::string::npos) score = std::max(score, 1);
      else if (all.find(word) != std::string::npos) score = 2;
      else score = 3;
    }
    if (score < 3) found.emplace_back(score, i);
  }
  std::stable_sort(found.begin(), found.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
  std::vector<size_t> result;
  for (const auto& [score, index] : found) result.push_back(index);
  return result;
}

void Menu::typeText(const char* utf8) {
  if (!acceptsText() || !utf8) return;
  std::string text = utf8;
  if (query_.empty()) text.erase(0, text.find_first_not_of(' '));  // pas d'espace au début
  if (text.empty() || query_.size() + text.size() > 40) return;
  query_ += text;
  optionSelected_ = 0;
  optionScroll_ = 0;
  resetArmed_ = false;
}

void Menu::eraseLetter() {
  if (query_.empty()) return;
  // Dernier caractère UTF-8 (octets de suite 10xxxxxx compris).
  size_t end = query_.size() - 1;
  while (end > 0 && ((unsigned char)query_[end] & 0xC0) == 0x80) end--;
  query_.erase(end);
  optionSelected_ = 0;
  optionScroll_ = 0;
}

// Clavier à l'écran (manette) : quatre lignes de dix caractères, puis Espace, Effacer, Terminer.
static const char* const kKeyboardRows[] = {"abcdefghij", "klmnopqrst", "uvwxyz0123", "456789:.-/"};
constexpr int kKeyboardLetterRows = 4, kKeyboardCols = 10;
/** Touche spéciale (0 à 2) sous la colonne [col], et colonne au milieu de chacune. */
static int keyboardSpecial(int col) { return col * 3 / kKeyboardCols; }
static const int kKeyboardSpecialCol[] = {1, 5, 8};

MenuAction Menu::handleKeyboard(Nav nav) {
  const bool special = keyboardRow_ == kKeyboardLetterRows;
  switch (nav) {
    case Nav::Up: keyboardRow_ = (keyboardRow_ + kKeyboardLetterRows) % (kKeyboardLetterRows + 1); break;
    case Nav::Down: keyboardRow_ = (keyboardRow_ + 1) % (kKeyboardLetterRows + 1); break;
    case Nav::Left:
    case Nav::Right: {
      const int step = nav == Nav::Left ? -1 : 1;
      if (special) keyboardCol_ = kKeyboardSpecialCol[(keyboardSpecial(keyboardCol_) + step + 3) % 3];
      else keyboardCol_ = (keyboardCol_ + step + kKeyboardCols) % kKeyboardCols;
      break;
    }
    case Nav::Confirm:
      if (!special) {
        const char letter[2] = {kKeyboardRows[keyboardRow_][keyboardCol_], 0};
        typeText(letter);
      } else if (keyboardSpecial(keyboardCol_) == 0) {
        typeText(" ");
      } else if (keyboardSpecial(keyboardCol_) == 1) {
        eraseLetter();
      } else {
        keyboard_ = false;
      }
      break;
    case Nav::Erase: eraseLetter(); break;
    case Nav::Back:
    case Nav::Search: keyboard_ = false; break;
    default: break;
  }
  return MenuAction::None;
}

MenuAction Menu::handleOptions(Nav nav) {
  if (keyboard_) return handleKeyboard(nav);
  const auto tabs = optionTabs();
  const int tabCount = std::max(1, (int)tabs.size());
  optionTab_ = std::min(optionTab_, tabCount - 1);
  const std::vector<size_t> shown = shownOptions(tabs);
  const bool search = !query_.empty();
  const int count = (int)shown.size() + (search ? 0 : 1);  // + « Réinitialiser » (hors recherche)
  optionSelected_ = std::clamp(optionSelected_, 0, std::max(0, count - 1));
  const bool armed = resetArmed_;
  resetArmed_ = false;
  switch (nav) {
    case Nav::Up: if (count) optionSelected_ = (optionSelected_ + count - 1) % count; break;
    case Nav::Down: if (count) optionSelected_ = (optionSelected_ + 1) % count; break;
    case Nav::TabPrev:
    case Nav::TabNext:
      if (search) break;
      optionTab_ = (optionTab_ + (nav == Nav::TabPrev ? tabCount - 1 : 1)) % tabCount;
      optionSelected_ = 0;
      optionScroll_ = 0;
      break;
    case Nav::Search:
      keyboard_ = true;
      keyboardRow_ = keyboardCol_ = 0;
      break;
    case Nav::Erase: eraseLetter(); break;
    case Nav::Left:
    case Nav::Right:
    case Nav::Confirm:
      if (optionSelected_ < (int)shown.size()) {
        g.options.cycle(shown[(size_t)optionSelected_], nav == Nav::Left ? -1 : 1);
      } else if (nav == Nav::Confirm && count) {
        // Deux appuis : un seul effacerait par mégarde toutes les options choisies.
        if (armed) g.options.resetAll();
        else resetArmed_ = true;
      }
      break;
    case Nav::Back:
      if (search) {
        query_.clear();
        optionSelected_ = 0;
        optionScroll_ = 0;
      } else {
        screen_ = Screen::Main;
      }
      break;
  }
  return MenuAction::None;
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

Enhancement Menu::enhancementOf(Item item) { return item == Item::Textures ? Enhancement::Textures : Enhancement::Resolution; }

/** Entrées du menu ; partie à plusieurs : rien qui ne changerait que cet appareil. */
std::vector<Menu::Item> Menu::items(const MenuState& state) const {
  std::vector<Item> list = {Item::Resume, Item::SaveState};
  if (!state.together) list.insert(list.end(), {Item::LoadState, Item::Reset, Item::Options});
  // Résolution interne (2x, 4x…) et textures (texte, interface) à portée de main, sans chercher dans
  // les options du cœur ; montrées aussi quand seul un autre rendu les propose (A : choix du rendu).
  if (!state.together && !g.options.enhancementOptions(Enhancement::Resolution).empty()) list.push_back(Item::Resolution);
  if (!state.together && !g.options.enhancementOptions(Enhancement::Textures).empty()) list.push_back(Item::Textures);
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
    case Item::Resolution: return tr("resolution");
    case Item::Textures: return tr("textures");
    case Item::Keys: return tr("keys");
    case Item::Pad: return tr("pad_config");
    case Item::Players: return tr("players");
    case Item::Disk: return tr("disk");
    case Item::Filter: return tr("filter");
    case Item::Aspect: return tr("aspect");
    case Item::RunAhead: return tr("runahead");
    case Item::Fullscreen: return tr("fullscreen");
    case Item::Sound: return tr("sound");
    case Item::LeaveNetplay: return tr("netplay_leave");
    case Item::SaveQuit: return tr("save_quit");
    case Item::Quit: return tr("quit");
  }
  return {};
}

std::string Menu::itemValue(Item item, const MenuState& state) const {
  switch (item) {
    case Item::Disk: return std::to_string(state.diskIndex + 1) + " / " + std::to_string(state.diskCount);
    case Item::Resolution:
    case Item::Textures: {
      const int index = g.options.mainEnhancement(enhancementOf(item));
      return index < 0 ? tr("other_renderer") : "< " + g.options.list()[(size_t)index].display() + " >";
    }
    case Item::Filter: return tr(state.filterLabel);
    case Item::Aspect: {
      static const char* names[] = {"aspect_core", "aspect_4_3", "aspect_16_9", "aspect_stretch"};
      return tr(names[state.aspect]);
    }
    case Item::RunAhead: return state.runAhead ? std::to_string(state.runAhead) : tr("runahead_off");
    case Item::Fullscreen: return tr(state.fullscreen ? "yes" : "no");
    case Item::Sound: return std::to_string(state.volume) + " %";
    default: return {};
  }
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
    case Nav::Search:
    case Nav::Erase: break;
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
  // Nom de choix long (versions de xBR) : coupé pour laisser le libellé et la barre.
  value = Canvas::fit(value, 1, width * 45 / 100);
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
  if (screen_ == Screen::Options) return handleOptions(nav);

  // Entrées l'une sous l'autre : haut / bas changent d'entrée (disque, format d'image, run-ahead et
  // plein écran changent avec A / Entrée ; résolution interne et textures aussi avec gauche / droite,
  // ou A ouvre leur onglet quand le rendu choisi ne les propose pas).
  int count = itemCount(state);
  selected_ = std::min(selected_, count - 1);
  Item item = itemAt(selected_, state);
  const bool enhancement = item == Item::Resolution || item == Item::Textures;
  const int option = enhancement ? g.options.mainEnhancement(enhancementOf(item)) : -1;
  if (enhancement && (nav == Nav::Left || nav == Nav::Right || nav == Nav::Confirm)) {
    if (option >= 0) {
      g.options.cycle((size_t)option, nav == Nav::Left ? -1 : 1);
    } else if (nav == Nav::Confirm) {
      // Onglet de l'amélioration (choix du rendu en tête) : Résolution puis Textures.
      openOptions();
      if (item == Item::Textures && !g.options.enhancementOptions(Enhancement::Resolution).empty()) optionTab_ = 1;
    }
    return MenuAction::None;
  }
  switch (nav) {
    case Nav::Up: selected_ = (selected_ + count - 1) % count; return MenuAction::None;
    case Nav::Down: selected_ = (selected_ + 1) % count; return MenuAction::None;
    case Nav::Back: return MenuAction::Resume;
    case Nav::TabPrev:
    case Nav::TabNext:
    case Nav::Search:
    case Nav::Erase: return MenuAction::None;
    case Nav::Left:
    case Nav::Right: return MenuAction::None;
    case Nav::Confirm:
      switch (item) {
        case Item::Resume: return MenuAction::Resume;
        case Item::SaveState: return MenuAction::SaveState;
        case Item::LoadState: return MenuAction::LoadState;
        case Item::Reset: return MenuAction::Reset;
        case Item::Options:
          openOptions();
          return MenuAction::None;
        case Item::Resolution:
        case Item::Textures: return MenuAction::None;  // ci-dessus
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
  if (screen_ != Screen::Main) c.fill(0, 0, c.width(), c.height(), rgba(8, 9, 14, 200));
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
  // Panneau à droite comme celui de l'écran Image, le jeu reste visible à gauche : titre du jeu, cœur,
  // entrées l'une sous l'autre (défilement si elles dépassent).
  const int width = std::clamp(c.width() * 9 / 20, 380, 520), x = c.width() - width;
  c.fill(x, 0, width, c.height(), rgba(8, 9, 14, 220));
  c.text(x + 16, 14, Canvas::fit(title_, 2, width - 32), 2, kText);
  c.text(x + 16, 36, Canvas::fit(core_, 1, width - 32), 1, kMuted);
  const int rowHeight = 20, top = 58, bottom = c.height() - 26;
  const int visible = std::max(1, (bottom - top) / rowHeight);
  const int count = itemCount(state);
  if (selected_ < mainScroll_) mainScroll_ = selected_;
  if (selected_ >= mainScroll_ + visible) mainScroll_ = selected_ - visible + 1;
  mainScroll_ = std::clamp(mainScroll_, 0, std::max(0, count - visible));
  int y = top;
  for (int i = mainScroll_; i < std::min(count, mainScroll_ + visible); i++) {
    const bool sel = i == selected_;
    c.fill(x + 8, y - 5, width - 16, 18, sel ? kSelection : rgba(255, 255, 255, 18));
    // Libellé à gauche, valeur réglée (filtre, format…) à droite.
    const Item item = itemAt(i, state);
    const std::string value = Canvas::fit(itemValue(item, state), 1, (width - 32) / 2);
    const int valueWidth = value.empty() ? 0 : Canvas::measure(value, 1);
    // Amélioration d'un autre rendu que celui choisi : grisée.
    const bool inactive = (item == Item::Resolution || item == Item::Textures) && g.options.mainEnhancement(enhancementOf(item)) < 0;
    const uint32_t valueColor = sel ? rgba(255, 255, 255) : inactive ? kMuted : kAccent;
    if (!value.empty()) c.text(x + width - 16 - valueWidth, y, value, 1, valueColor);
    c.text(x + 16, y, Canvas::fit(itemLabel(item, state), 1, width - 48 - valueWidth), 1,
           sel ? rgba(255, 255, 255) : inactive ? kMuted : kText);
    y += rowHeight;
  }
  // Aide de la résolution interne et des textures (redémarrage, rendu à choisir) à la place de l'aide.
  const char* notice = nullptr;
  if (selected_ < count) {
    const Item item = itemAt(selected_, state);
    if (item == Item::Resolution || item == Item::Textures) {
      const int option = g.options.mainEnhancement(enhancementOf(item));
      if (option < 0) notice = "renderer_hint";
      else if (g.options.needsRestart((size_t)option)) notice = "resolution_restart";
      else if (item == Item::Textures) notice = "textures_hint";
    }
  }
  std::string hint = Canvas::fit(tr(notice ? notice : "main_hint"), 1, width - 24);
  c.text(x + (width - Canvas::measure(hint, 1)) / 2, c.height() - 16, hint, 1, notice ? kAccent : kMuted);
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

/** Texte réparti sur [maxLines] lignes au plus de [maxWidth] pixels (coupé avec « . » au-delà). */
static std::vector<std::string> wrapText(const std::string& text, int maxWidth, size_t maxLines) {
  std::vector<std::string> lines;
  std::string line, word;
  auto place = [&](const std::string& w) {
    if (w.empty()) return;
    if (line.empty() || Canvas::measure(line + " " + w, 1) <= maxWidth) {
      line += (line.empty() ? "" : " ") + w;
    } else {
      lines.push_back(line);
      line = w;
    }
  };
  for (char ch : text + " ") {
    if (ch == ' ' || ch == '\n' || ch == '\t') {
      place(word);
      word.clear();
    } else {
      word += ch;
    }
  }
  if (!line.empty()) lines.push_back(line);
  if (lines.size() > maxLines) {
    lines.resize(maxLines);
    lines.back() = Canvas::fit(lines.back() + " ...", 1, maxWidth - 8);
  }
  for (auto& l : lines) l = Canvas::fit(l, 1, maxWidth);
  return lines;
}

void Menu::renderKeyboard(Canvas& c, int top) const {
  const int cell = std::min(44, (c.width() - 48) / kKeyboardCols), left = (c.width() - cell * kKeyboardCols) / 2;
  c.fill(0, top, c.width(), (kKeyboardLetterRows + 1) * 18 + 12, rgba(20, 21, 32, 235));
  int y = top + 8;
  for (int row = 0; row <= kKeyboardLetterRows; row++) {
    for (int key = 0; key < (row < kKeyboardLetterRows ? kKeyboardCols : 3); key++) {
      // Touches spéciales : colonnes 0 à 3, 4 à 6 et 7 à 9 (keyboardSpecial).
      const int first = row < kKeyboardLetterRows ? key : key == 0 ? 0 : key == 1 ? 4 : 7;
      const int span = row < kKeyboardLetterRows ? 1 : key == 0 ? 4 : 3;
      const bool sel = keyboardRow_ == row && (row < kKeyboardLetterRows ? keyboardCol_ == key : keyboardSpecial(keyboardCol_) == key);
      const std::string label = row < kKeyboardLetterRows ? std::string(1, kKeyboardRows[row][key])
                                : tr(key == 0 ? "kb_space" : key == 1 ? "kb_erase" : "kb_done");
      const int x = left + first * cell, w = span * cell - 4;
      c.fill(x, y - 4, w, 16, sel ? kSelection : rgba(255, 255, 255, 28));
      c.text(x + (w - Canvas::measure(label, 1)) / 2, y, label, 1, sel ? rgba(255, 255, 255) : kText);
    }
    y += 18;
  }
}

void Menu::renderOptions(Canvas& c) const {
  const auto& list = g.options.list();
  const auto tabs = optionTabs();
  const int tab = std::min(optionTab_, std::max(0, (int)tabs.size() - 1));
  const std::vector<size_t> shown = shownOptions(tabs);
  const bool search = !query_.empty() || keyboard_;
  int y = 20;
  c.text(24, y, tr("options_title") + " — " + core_, 2, kText);
  y += 22;
  c.text(24, y, Canvas::fit(tr(list.empty() ? "options_none" : "options_hint"), 1, c.width() - 48), 1, kMuted);
  y += 18;

  if (search) {
    // Barre de recherche : texte tapé (curseur à la fin), nombre d'options trouvées.
    const std::string count = std::to_string(shown.size()) + " " + tr("search_count");
    const int countWidth = Canvas::measure(count, 1);
    c.fill(16, y - 4, c.width() - 32, 16, rgba(255, 255, 255, 28));
    c.fill(16, y + 11, c.width() - 32, 2, kAccent);
    const std::string text = tr("search") + " : " + query_ + "_";
    c.text(24, y, Canvas::fit(text, 1, c.width() - 72 - countWidth), 1, rgba(255, 255, 255));
    c.text(c.width() - 24 - countWidth, y, count, 1, kMuted);
    y += 22;
  } else if (tabs.size() > 1) {
    // Barre d'onglets, décalée pour que l'onglet affiché reste visible.
    std::vector<std::string> labels;
    std::vector<int> starts;
    int x = 0;
    for (const auto& group : tabs) {
      labels.push_back(group.title + " (" + std::to_string(group.indices.size()) + ")");
      starts.push_back(x);
      x += Canvas::measure(labels.back(), 1) + 16;
    }
    const int left = 16, right = c.width() - 16;
    int shift = std::max(0, starts[(size_t)tab] + Canvas::measure(labels[(size_t)tab], 1) + 16 - (right - left));
    for (size_t i = 0; i < tabs.size(); i++) {
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
    c.text(24, y, Canvas::fit(tr("options_tabs_hint"), 1, c.width() - 48), 1, kMuted);
    y += 16;
  } else if (!list.empty()) {
    c.text(24, y, tr("options_search_hint"), 1, kMuted);
    y += 16;
  }

  // Bas de l'écran : clavier à l'écran, sinon description de l'option choisie (deux lignes).
  const int keyboardHeight = (kKeyboardLetterRows + 1) * 18 + 12;
  const int keyboardTop = c.height() - 26 - keyboardHeight;
  const int listBottom = keyboard_ ? keyboardTop - 4 : c.height() - 26 - 30;
  const int lineHeight = 14;
  const int visible = std::max(1, (listBottom - y) / lineHeight);
  const int count = (int)shown.size() + (query_.empty() ? 1 : 0);
  if (optionSelected_ < optionScroll_) optionScroll_ = optionSelected_;
  if (optionSelected_ >= optionScroll_ + visible) optionScroll_ = optionSelected_ - visible + 1;
  optionScroll_ = std::clamp(optionScroll_, 0, std::max(0, count - visible));

  // Résultats de la recherche : onglet de chaque option après son libellé (celui du cœur, après
  // l'onglet « Résolution »).
  std::vector<std::string> tabOf(list.size());
  for (const auto& group : tabs) {
    for (size_t i : group.indices) tabOf[i] = group.title;
  }
  if (!query_.empty() && shown.empty()) c.text(24, y, tr("search_none"), 1, kMuted);
  for (int row = optionScroll_; row < std::min(count, optionScroll_ + visible); row++) {
    const bool sel = row == optionSelected_ && !keyboard_;
    if (sel) c.fill(16, y - 3, c.width() - 32, lineHeight, kSelection);
    if (row == (int)shown.size()) {
      c.text(24, y, tr(resetArmed_ ? "options_reset_confirm" : "options_reset"), 1, sel ? rgba(255, 255, 255) : kAccent);
    } else {
      const CoreOption& o = list[shown[(size_t)row]];
      std::string value = "< " + o.display() + " >";
      int valueWidth = Canvas::measure(value, 1);
      int right = c.width() - 24;
      c.text(right - valueWidth, y, value, 1, o.value == o.defaultValue() ? kText : kAccent);
      const int labelRoom = right - valueWidth - 40;
      const std::string label = Canvas::fit(o.label, 1, labelRoom);
      // Résolution d'un autre rendu que celui choisi (GLideN64 avec ParaLLEl-RDP…) : grisée.
      const bool inactive = g.options.otherRenderer(shown[(size_t)row]);
      const int labelWidth = c.text(24, y, label, 1, sel ? rgba(255, 255, 255) : inactive ? kMuted : kText);
      if (!query_.empty() && !tabOf[shown[(size_t)row]].empty() && labelWidth + 40 < labelRoom) {
        c.text(24 + labelWidth + 12, y, Canvas::fit(tabOf[shown[(size_t)row]], 1, labelRoom - labelWidth - 12), 1,
               sel ? rgba(220, 220, 240) : kMuted);
      }
    }
    y += lineHeight;
  }

  if (keyboard_) {
    renderKeyboard(c, keyboardTop);
  } else if (optionSelected_ < (int)shown.size()) {
    const CoreOption& o = list[shown[(size_t)optionSelected_]];
    int infoY = c.height() - 26 - 26;
    for (const auto& line : wrapText(o.info, c.width() - 48, 2)) {
      c.text(24, infoY, line, 1, kMuted);
      infoY += 12;
    }
  }
  std::string hint = Canvas::fit(tr(keyboard_ ? "kb_hint" : !query_.empty() ? "search_hint" : "hint"), 1, c.width() - 24);
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
