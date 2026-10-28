// romcloud-player : moteur d'émulation intégré de RomCloud pour Windows.
//
//   romcloud-player --core <cœur.dll> --rom <jeu> [--system-dir <BIOS>] [--save-dir <dossier>]
//                   [--state-dir <dossier>] [--options <fichier>] [--title <nom>] [--lang fr|en]
//                   [--windowed] [--resume] [--state-name <nom>] [--option-default <clé>=<valeur>]…
//                   [--options-catalog <fichier JSON des options déclarées par le cœur, écrit en quittant>]
//                   [--keys-file <touches du clavier>] [--buttons <boutons de la console>]
//                   [--pad-style <manette de la console>] [--pad-images <illustrations des manettes>]
//                   [--history-dir <historique des états> --history-device <nom du PC>] [--state-file <état>]
//                   [--disk <disque inséré au démarrage, 0 pour le premier>]
//                   [--netplay-host | --netplay-join <adresse:port> --netplay-peer <nom de l'hôte>]
//                   [--netplay-game <jeu (JSON)>] [--device-id <identifiant>] [--device-name <nom>]
//                   [--netplay-link <gb|gba|psp> [--netplay-packets] [--netplay-multi]] [--linked <nom>]
//                   [--netplay-port <port d'écoute de l'hôte>] [--netplay-delay <images de délai demandées>]
//                   [--option <clé>=<valeur>]…
//   romcloud-player --pad-service --options <fichier> --keys-file <fichier> [--buttons <…>] [--pad-style <…>] [--lang fr|en]
//
// --pad-service : sans fenêtre, manettes du moteur pour la fiche d'un jeu de l'application (padservice.h).
//
// Jeu à plusieurs en réseau local (netplay.h) : partie proposée (--netplay-host) ou rejointe
// (--netplay-join), même protocole que l'application Android. Liaison entre consoles (--netplay-link) :
// chacun son jeu ; paquets du cœur échangés ici (--netplay-packets, gpSP) ou connexion ouverte par le
// cœur (options imposées par --option ; invité accepté avant le lancement : --linked <nom de l'hôte>).
//
// Historique des états (history.h) : chaque état sauvegardé y est ajouté avec sa miniature ;
// « Charger l'état » liste ceux de ce PC et du profil en ligne ; --state-file : état chargé au
// lancement (choisi dans la fiche du jeu). Jeu en plusieurs disques (liste .m3u) : disque inséré au
// démarrage (--disk, choisi dans la fiche), disque en cours noté avec chaque état et remis avant de
// charger un état fait sur un autre disque.
//
// Fonctionnement calqué sur LibretroDroid : le cœur est chargé, le jeu démarré, puis une boucle
// exécute retro_run au rythme de l'audio et affiche chaque image avec OpenGL. Codes de sortie :
// 0 = fin normale, 1 = erreur (message affiché).
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "audio.h"
#include "volumekeys.h"
#include "canvas.h"
#include "core.h"
#include "history.h"
#include "input.h"
#include "menu.h"
#include "netplay.h"
#include "padconfig.h"
#include "padservice.h"
#include "players.h"
#include "util.h"
#include "video.h"


// PC portables à deux cartes graphiques (NVIDIA Optimus, AMD PowerXpress) : le moteur demande la
// carte dédiée, sans quoi le pilote le lance sur la puce intégrée (beaucoup moins rapide).
extern "C" {
__declspec(dllexport) unsigned long NvOptimusEnablement = 1;
__declspec(dllexport) int AmdPowerXpressRequestHighPerformance = 1;
}

namespace {

struct Args {
  std::string core, rom, systemDir, saveDir, stateDir, options, title, lang = "fr";
  std::string stateName;  // nom de l'état de sauvegarde (par défaut celui de la ROM)
  std::vector<std::string> optionDefaults;  // « clé=valeur » propres au jeu (--option-default)
  std::string optionsCatalog;  // options déclarées par le cœur, écrites en JSON en quittant (--options-catalog)
  std::string keysFile;  // touches du clavier « bouton=scancode », modifiées depuis le menu
  std::string buttons;  // boutons de la console proposés dans l'écran des touches (--buttons)
  std::string padStyle;  // manette de la console dessinée dans la configuration (--pad-style)
  std::string padImages;  // illustrations des manettes (PNG), dossier (--pad-images)
  std::string historyDir, historyDevice;  // historique des états du jeu, nom du PC
  std::string stateFile;  // état de l'historique chargé au lancement
  std::string disk;  // disque inséré au démarrage (jeu en plusieurs disques), 0 pour le premier
  bool windowed = false;
  bool resume = false;  // reprend la partie à l'état sauvegardé
  // Jeu à plusieurs : partie proposée, ou adresse de l'hôte (« 192.168.1.20:40123 ») et son nom ;
  // jeu annoncé (JSON : gameId, systemId, title, fileName, size, core) ; appareil.
  bool netplayHost = false;
  std::string netplayJoin, netplayPeer, netplayGame, deviceId, deviceName;
  std::string netplayLink, linked;
  std::string netplayPort, netplayDelay;  // jeu par Internet : port relié au relais, délai demandé
  bool netplayPackets = false, netplayMulti = false;
  std::vector<std::string> optionsForced;  // options imposées (liaison entre consoles)
  // Mode d'essai : fenêtre cachée, N images au plus vite, dernière image enregistrée en BMP.
  std::string testFrames, screenshot, testSeconds, testOptions, testSearch;
  std::string testLoadAt;  // essai : état chargé après N images (au lieu de la première)
  bool testWindow = false;  // essai dans une fenêtre visible : affichage et menu capturés
  bool testMenu = false;
  bool testKeys = false;  // essai : écran des touches du clavier
  bool testPad = false;  // essai : écran de configuration des manettes
  bool testPlayers = false;  // essai : écran des joueurs des manettes
  bool testFilterSettings = false;  // essai : écran « Image » (filtres et réglages)
  bool testStates = false;  // essai : historique des états (« Charger l'état »)
  bool testKeyboard = false;  // essai : clavier à l'écran de la recherche dans les options
  bool testSaveState = false;  // essai : état enregistré après les N images
  bool testAudio = false;  // essai : amplitude maximale du son des N images
  bool testReload = false;  // essai : état rechargé en cours de partie, depuis le menu (« Charger l'état »)
};

Args parseArgs(int argc, char** argv) {
  Args a;
  std::map<std::string, std::string*> values = {
      {"--core", &a.core},           {"--rom", &a.rom},         {"--system-dir", &a.systemDir},
      {"--save-dir", &a.saveDir},    {"--state-dir", &a.stateDir}, {"--options", &a.options},
      {"--title", &a.title},         {"--lang", &a.lang},       {"--state-name", &a.stateName},
      {"--keys-file", &a.keysFile}, {"--buttons", &a.buttons}, {"--pad-style", &a.padStyle},
      {"--pad-images", &a.padImages},
      {"--test-frames", &a.testFrames}, {"--screenshot", &a.screenshot}, {"--test-seconds", &a.testSeconds},
      {"--test-options", &a.testOptions},  // essai : écran des options du cœur, onglet N
      {"--test-search", &a.testSearch},    // essai : recherche dans les options du cœur
      {"--test-load-at", &a.testLoadAt},
      {"--netplay-join", &a.netplayJoin}, {"--netplay-peer", &a.netplayPeer}, {"--netplay-game", &a.netplayGame},
      {"--device-id", &a.deviceId},     {"--device-name", &a.deviceName},
      {"--netplay-link", &a.netplayLink}, {"--linked", &a.linked},
      {"--netplay-port", &a.netplayPort}, {"--netplay-delay", &a.netplayDelay},
      {"--history-dir", &a.historyDir}, {"--history-device", &a.historyDevice}, {"--state-file", &a.stateFile},
      {"--disk", &a.disk},       {"--options-catalog", &a.optionsCatalog},
  };
  for (int i = 1; i < argc; i++) {
    std::string arg = argv[i];
    if (arg == "--windowed") a.windowed = true;
    else if (arg == "--resume") a.resume = true;
    else if (arg == "--netplay-host") a.netplayHost = true;
    else if (arg == "--netplay-packets") a.netplayPackets = true;
    else if (arg == "--netplay-multi") a.netplayMulti = true;
    else if (arg == "--option" && i + 1 < argc) a.optionsForced.push_back(argv[++i]);
    else if (arg == "--test-window") a.testWindow = true;
    else if (arg == "--test-menu") a.testMenu = true;
    else if (arg == "--test-keys") a.testKeys = true;
    else if (arg == "--test-pad") a.testPad = true;
    else if (arg == "--test-players") a.testPlayers = true;
    else if (arg == "--test-filter-settings") a.testFilterSettings = true;
    else if (arg == "--test-states") a.testStates = true;
    else if (arg == "--test-keyboard") a.testKeyboard = true;
    else if (arg == "--test-save-state") a.testSaveState = true;
    else if (arg == "--test-audio") a.testAudio = true;
    else if (arg == "--test-reload") a.testReload = true;
    else if (arg == "--option-default" && i + 1 < argc) a.optionDefaults.push_back(argv[++i]);
    else if (values.count(arg) && i + 1 < argc) *values[arg] = argv[++i];
  }
  return a;
}

class Player {
 public:
  explicit Player(Args args)
      : args_(std::move(args)),
        menu_(args_.lang, title(), coreName()),
        history_(args_.historyDir, coreName(), args_.historyDevice.empty() ? "PC" : args_.historyDevice) {}

  int run();

 private:
  std::string title() const { return args_.title.empty() ? baseName(args_.rom) : args_.title; }
  std::string coreName() const {
    std::string name = baseName(args_.core);
    size_t suffix = name.find("_libretro");
    return suffix == std::string::npos ? name : name.substr(0, suffix);
  }
  std::string sramPath() const { return joinPath(args_.saveDir, baseName(args_.rom) + ".srm"); }
  std::string statePath() const {
    return joinPath(args_.stateDir, (args_.stateName.empty() ? baseName(args_.rom) : args_.stateName) + ".state");
  }

  int fail(const std::string& message);
  bool loadGame(std::string& error);
  void loadSram();
  void saveSram();
  bool saveState();
  bool loadState();
  bool loadStateFile(const std::string& path);
  /** « Charger l'état » : historique des états (sinon l'état unique du jeu). */
  void openStates();
  void changeDisk(int direction);
  /** Disque inséré (jeu en plusieurs disques), -1 sinon. */
  int currentDisk() const;
  /** Insère le disque [index] s'il existe et n'est pas déjà dans le lecteur. */
  void insertDisk(int index);
  MenuState menuState() const;
  void openMenu();
  void closeMenu();
  /** Volume du son changé de [percent] (touches de volume) : entendu aussitôt, mémorisé pour tous les émulateurs. */
  void changeVolume(int percent) {
    const int volume = std::clamp((int)std::lround(audio_.volume() * 100) + percent, 0, 100);
    audio_.setVolume(volume / 100.0f);
    global_.setSetting("romcloud_volume", std::to_string(volume));
    // Panneau du son ouvert : sa valeur suit.
    if (menu_.inSoundSettings()) menu_.openSoundSettings({{menu_.tr("volume"), audio_.volume(), 1.0f, 0.0f, 1.0f, 0.05f}});
    toast(menu_.tr("sound") + " : " + std::to_string(volume) + " %");
  }

  void toast(const std::string& text) {
    toast_ = text;
    toastUntil_ = SDL_GetTicks() + 2200;
  }
  bool handleEvents();
  void onMenuAction(MenuAction action);
  void startPadConfig() {
    padConfig_ = std::make_unique<PadConfig>(args_.buttons, args_.padStyle, [this](const char* key) { return menu_.tr(key); });
  }
  /**
   * Clé mémorisée (options du système) d'un réglage du filtre choisi ; lissage du tramage commun à
   * tous les filtres (gardé en changeant de filtre).
   */
  std::string filterParamKey(const FilterParam& p) const {
    if (p.slot == kDeditherSlot) return "romcloud_dedither";
    return std::string("romcloud_") + filterId(video_.filter()) + "_" + p.id;
  }
  float filterParamValue(const FilterParam& p) const {
    const std::string v = g.options.setting(filterParamKey(p), "");
    return v.empty() ? p.def : std::clamp((float)atof(v.c_str()), p.min, p.max);
  }
  /** Réglages mémorisés du filtre choisi, donnés au rendu. */
  void loadFilterParams() {
    for (const auto& p : filterParams(video_.filter())) video_.setFilterParam(p.slot, filterParamValue(p));
  }
  /** Réglages du filtre choisi, affichés dans l'écran « Image ». */
  std::vector<FilterRow> filterRows() const {
    std::vector<FilterRow> rows;
    for (const auto& p : filterParams(video_.filter())) {
      FilterRow row{menu_.tr(p.label), filterParamValue(p), p.def, p.min, p.max, p.step};
      row.integer = p.format == ParamFormat::Integer;
      // Noms des choix : clés séparées par « | ».
      for (std::string keys = p.choices ? p.choices : ""; !keys.empty();) {
        const size_t bar = keys.find('|');
        row.choices.push_back(menu_.tr(keys.substr(0, bar).c_str()));
        keys = bar == std::string::npos ? std::string() : keys.substr(bar + 1);
      }
      rows.push_back(std::move(row));
    }
    return rows;
  }
  void startPlayers() {
    players_ = std::make_unique<PlayerOrder>(input_, [this](const char* key) { return menu_.tr(key); });
  }
  void runFrame();
  /**
   * Image du jeu avec le run-ahead choisi : image réelle (son gardé, image écartée), état gardé,
   * [runAhead_] images d'avance avec les mêmes touches (muettes, seule la dernière affichée), puis
   * état réel rétabli. Sans run-ahead (ou en partie à plusieurs) : runFrame.
   */
  void runFrameAhead();
  void present();
  void shutdown();
  void startNetplay();
  /** Texte du moteur avec le nom de l'autre joueur à la place de {name}. */
  std::string trName(const char* key, const std::string& name) const {
    std::string text = menu_.tr(key);
    size_t at = text.find("{name}");
    if (at != std::string::npos) text.replace(at, 6, name);
    return text;
  }

  Args args_;
  Menu menu_;
  StateHistory history_;
  std::vector<HistoryEntry> shownStates_;  // états listés dans le menu
  Video video_;
  Audio audio_;
  // Réglages communs à tous les émulateurs (volume) : player.cfg, à côté des touches du clavier.
  CoreOptions global_;
  Input input_;
  Netplay netplay_;
  std::unique_ptr<PadConfig> padConfig_;  // configuration d'une manette en cours (depuis le menu)
  std::unique_ptr<PlayerOrder> players_;  // choix des joueurs des manettes en cours (depuis le menu)
  Canvas overlay_{640, 360};
  std::vector<uint8_t> romData_;
  std::string romPath_;
  bool quit_ = false;
  bool comboLatched_ = false;
  std::string toast_;
  Uint64 toastUntil_ = 0;
  std::string notice_;  // notification en haut de l'écran (invité arrivé)
  std::string overlayKey_;  // contenu de la surimpression déjà envoyée (vide : à redessiner)
  Uint64 noticeUntil_ = 0;
  double nextFrame_ = 0;
  int runAhead_ = 0;  // images d'avance (run-ahead), mémorisé pour le système
  std::vector<uint8_t> runAheadState_;
};

/**
 * Images exécutées avant de reprendre une partie (état chargé au lancement). Une seule en général ;
 * Yabause (Saturn) n'a plus de son si l'état est chargé avant le démarrage de son processeur de son
 * (environ 1,4 s après le lancement) : 3 s d'émulation, plus rapides que le temps réel.
 */
/** Run-ahead : images d'avance au plus (choix du menu). */
static constexpr int kMaxRunAhead = 3;

static int resumeWarmupFrames(const std::string& core) {
  return core == "yabause" ? 180 : 1;
}

int Player::fail(const std::string& message) {
  logf("Erreur : %s", message.c_str());
  if (!args_.testFrames.empty()) return 1;
  SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "RomCloud", message.c_str(), video_.window());
  return 1;
}

bool Player::loadGame(std::string& error) {
  retro_system_info info{};
  g.api.get_system_info(&info);
  logf("Cœur : %s %s (extensions : %s)", info.library_name ? info.library_name : "?",
       info.library_version ? info.library_version : "", info.valid_extensions ? info.valid_extensions : "");

  romPath_ = compatiblePath(args_.rom);
  retro_game_info game{};
  game.path = romPath_.c_str();
  if (!info.need_fullpath) {
    if (!readFile(args_.rom, romData_)) {
      error = args_.rom;
      return false;
    }
    game.data = romData_.data();
    game.size = romData_.size();
  }
  // Disque choisi au démarrage : indiqué au cœur avant le chargement (interface étendue), sinon inséré
  // juste après (avant la première image).
  const int disk = args_.disk.empty() ? 0 : std::max(0, atoi(args_.disk.c_str()));
  if (disk > 0 && g.diskInitialImage && !g.diskInitialImage((unsigned)disk, romPath_.c_str())) logf("Disque %d refusé au démarrage", disk + 1);
  if (!g.api.load_game(&game)) {
    error = menu_.tr("load_failed");
    // Raison donnée par le cœur (Azahar : « This ROM is encrypted… »).
    if (!g.message.empty()) error += "\n" + g.message;
    return false;
  }
  if (disk > 0) {
    insertDisk(disk);
    logf("Disque inséré au démarrage : %d / %d", currentDisk() + 1, g.hasDiskControl && g.disk.get_num_images ? (int)g.disk.get_num_images() : 1);
  }
  g.api.get_system_av_info(&g.av);
  selectControllers(Input::kPorts);
  logf("Image %ux%u (max %ux%u), %.3f images/s, audio %.1f Hz", g.av.geometry.base_width, g.av.geometry.base_height,
       g.av.geometry.max_width, g.av.geometry.max_height, g.av.timing.fps, g.av.timing.sample_rate);
  return true;
}

void Player::loadSram() {
  size_t size = g.api.get_memory_size(RETRO_MEMORY_SAVE_RAM);
  void* data = g.api.get_memory_data(RETRO_MEMORY_SAVE_RAM);
  std::vector<uint8_t> saved;
  if (size && data && readFile(sramPath(), saved)) {
    memcpy(data, saved.data(), std::min(size, saved.size()));
    logf("Sauvegarde chargée : %s", sramPath().c_str());
  }
}

void Player::saveSram() {
  size_t size = g.api.get_memory_size(RETRO_MEMORY_SAVE_RAM);
  void* data = g.api.get_memory_data(RETRO_MEMORY_SAVE_RAM);
  if (size && data) writeFile(sramPath(), data, size);
}

bool Player::saveState() {
  size_t size = g.api.serialize_size();
  if (!size) return false;
  std::vector<uint8_t> buffer(size);
  if (!g.api.serialize(buffer.data(), size) || !writeFile(statePath(), buffer.data(), size)) return false;
  // Historique : l'état et l'image affichée (miniature).
  SDL_Surface* frame = video_.captureFrame();
  if (history_.enabled() && !history_.add(buffer, frame, currentDisk())) logf("Historique des états : ajout impossible");
  if (frame) SDL_DestroySurface(frame);
  return true;
}

bool Player::loadState() { return loadStateFile(statePath()); }

bool Player::loadStateFile(const std::string& path) {
  std::vector<uint8_t> buffer;
  return readFile(path, buffer) && g.api.unserialize(buffer.data(), buffer.size());
}

void Player::openStates() {
  shownStates_ = history_.list();
  if (shownStates_.empty()) {
    // Pas d'historique (état d'une version précédente) : l'état unique du jeu.
    toast(menu_.tr(!fileExists(statePath()) ? "no_state" : loadState() ? "state_loaded" : "state_error"));
    closeMenu();
    return;
  }
  std::vector<std::string> rows;
  for (const auto& entry : shownStates_) {
    std::string row = formatStateDate(entry.createdAt, args_.lang) + "   " + (entry.online ? entry.device : menu_.tr("this_pc"));
    if (entry.disk >= 0 && currentDisk() >= 0) row += "   " + menu_.tr("disk") + " " + std::to_string(entry.disk + 1);
    if (entry.pinned) row += " (" + menu_.tr("pinned") + ")";
    rows.push_back(row);
  }
  menu_.openStates(std::move(rows));
}

void Player::changeDisk(int direction) {
  if (!g.hasDiskControl || !g.disk.get_num_images) return;
  int count = (int)g.disk.get_num_images();
  if (count < 2) return;
  int index = ((int)g.disk.get_image_index() + direction + count) % count;
  g.disk.set_eject_state(true);
  g.disk.set_image_index((unsigned)index);
  g.disk.set_eject_state(false);
}

int Player::currentDisk() const {
  if (!g.hasDiskControl || !g.disk.get_num_images || !g.disk.get_image_index || g.disk.get_num_images() < 2) return -1;
  return (int)g.disk.get_image_index();
}

void Player::insertDisk(int index) {
  const int current = currentDisk();
  if (current < 0 || index < 0 || index == current || index >= (int)g.disk.get_num_images()) return;
  g.disk.set_eject_state(true);
  g.disk.set_image_index((unsigned)index);
  g.disk.set_eject_state(false);
}

MenuState Player::menuState() const {
  MenuState s;
  s.filterLabel = filterLabel(video_.filter());
  s.aspect = (int)video_.aspect();
  s.fullscreen = video_.fullscreen();
  s.volume = (int)std::lround(audio_.volume() * 100);
  s.runAhead = runAhead_;
  if (g.hasDiskControl && g.disk.get_num_images) {
    s.diskCount = (int)g.disk.get_num_images();
    s.diskIndex = (int)g.disk.get_image_index();
  }
  s.hasState = fileExists(statePath());
  // Liaison ouverte par le cœur : état ou options changés ici couperaient la liaison.
  s.together = netplay_.playing() || !netplay_.linked().empty();
  s.netplay = (netplay_.playing() || netplay_.open()) && (!netplay_.isLink() || netplay_.packets());
  return s;
}

void Player::openMenu() {
  if (menu_.isOpen()) return;
  menu_.open();
  audio_.setPaused(true);
  audio_.clear();
  saveSram();  // au cas où l'utilisateur ferme la fenêtre depuis le menu
}

void Player::closeMenu() {
  menu_.close();
  audio_.setPaused(false);
  nextFrame_ = 0;
}

void Player::onMenuAction(MenuAction action) {
  switch (action) {
    case MenuAction::None: break;
    case MenuAction::Resume: closeMenu(); break;
    case MenuAction::SaveState:
      toast(menu_.tr(saveState() ? "state_saved" : "state_error"));
      closeMenu();
      break;
    case MenuAction::LoadState: openStates(); break;
    case MenuAction::LoadHistoryState: {
      size_t index = (size_t)menu_.stateSelected();
      if (index < shownStates_.size()) {
        // État fait sur un autre disque : celui-ci remis d'abord dans le lecteur.
        insertDisk(shownStates_[index].disk);
        toast(menu_.tr(loadStateFile(shownStates_[index].statePath) ? "state_loaded" : "state_error"));
      }
      closeMenu();
      break;
    }
    case MenuAction::Reset:
      g.api.reset();
      closeMenu();
      break;
    case MenuAction::Quit: quit_ = true; break;
    case MenuAction::SaveQuit:
      // Quitte seulement si l'état a bien été enregistré.
      if (saveState()) {
        quit_ = true;
      } else {
        toast(menu_.tr("state_error"));
        closeMenu();
      }
      break;
    case MenuAction::ImageSettings: {
      // Liste des shaders (nom, description), puis réglages de celui choisi.
      std::vector<std::pair<std::string, std::string>> choices;
      for (int i = 0; i < kFilterListCount; i++)
        choices.emplace_back(menu_.tr(filterLabel(filterAt(i))), menu_.tr(filterDescription(filterAt(i))));
      menu_.openImageSettings(std::move(choices), filterIndex(video_.filter()), filterRows());
      break;
    }
    case MenuAction::FilterChoice:
      // Visible aussitôt à côté du panneau ; mémorisé pour le système.
      video_.setFilter(filterAt(menu_.filterChoice()));
      g.options.setSetting("romcloud_filter", filterId(video_.filter()));
      loadFilterParams();
      menu_.setFilterRows(menu_.filterChoice(), filterRows());
      break;
    case MenuAction::ConfigurePad: startPadConfig(); break;
    case MenuAction::Players: startPlayers(); break;
    case MenuAction::SoundSettings:
      menu_.openSoundSettings({{menu_.tr("volume"), audio_.volume(), 1.0f, 0.0f, 1.0f, 0.05f}});
      break;
    case MenuAction::FilterParam:
    case MenuAction::FilterDefaults: {
      // Son : entendu aussitôt ; mémorisé pour tous les émulateurs.
      if (menu_.inSoundSettings()) {
        if (menu_.filterRows().empty()) break;
        audio_.setVolume(menu_.filterRows()[0].value);
        global_.setSetting("romcloud_volume", std::to_string((int)std::lround(audio_.volume() * 100)));
        break;
      }
      // Filtre : visible aussitôt à côté du panneau ; mémorisé pour le système.
      const auto params = filterParams(video_.filter());
      const auto& rows = menu_.filterRows();
      for (size_t i = 0; i < params.size() && i < rows.size(); i++) {
        if (action == MenuAction::FilterParam && (int)i != menu_.filterRowChanged()) continue;
        char value[32];
        snprintf(value, sizeof(value), "%.2f", rows[i].value);
        g.options.setSetting(filterParamKey(params[i]), value);
        video_.setFilterParam(params[i].slot, rows[i].value);
      }
      break;
    }
    case MenuAction::NextAspect:
      video_.setAspect(nextAspect(video_.aspect()));
      g.options.setSetting("romcloud_aspect", aspectId(video_.aspect()));
      break;
    case MenuAction::NextRunAhead: {
      // Désactivé, puis 1 à 3 images d'avance ; cœur sans états : impossible.
      const int next = (runAhead_ + 1) % (kMaxRunAhead + 1);
      if (next > 0 && !g.api.serialize_size()) {
        toast(menu_.tr("runahead_unsupported"));
        break;
      }
      runAhead_ = next;
      g.options.setSetting("romcloud_runahead", std::to_string(runAhead_));
      break;
    }
    case MenuAction::ToggleFullscreen: video_.toggleFullscreen(); break;
    case MenuAction::DiskNext: changeDisk(1); break;
    case MenuAction::DiskPrev: changeDisk(-1); break;
    case MenuAction::LeaveNetplay:
      netplay_.leave();
      closeMenu();
      break;
  }
}

/** Traite les évènements ; renvoie faux quand il faut quitter. */
bool Player::handleEvents() {
  // Options du cœur affichées : les lettres tapées au clavier y font une recherche.
  if (menu_.acceptsText() != SDL_TextInputActive(video_.window())) {
    if (menu_.acceptsText()) SDL_StartTextInput(video_.window());
    else SDL_StopTextInput(video_.window());
  }
  SDL_Event e;
  while (SDL_PollEvent(&e)) {
    input_.handleEvent(e);
    // Configuration d'une manette : clavier et manettes lui reviennent ; menu affiché à la fin.
    if (padConfig_ && padConfig_->handleEvent(e)) {
      if (padConfig_->finished()) {
        if (!padConfig_->result().empty()) toast(padConfig_->result());
        padConfig_.reset();
      }
      continue;
    }
    if (padConfig_ && padConfig_->finished()) padConfig_.reset();
    // Joueurs des manettes : boutons des manettes et clavier lui reviennent.
    if (players_ && players_->handleEvent(e)) {
      if (players_->finished()) {
        if (!players_->result().empty()) toast(players_->result());
        players_.reset();
      }
      continue;
    }
    std::string requester;
    if (netplay_.requestPending(requester)) {
      if (e.type == SDL_EVENT_KEY_DOWN && !e.key.repeat) {
        if (e.key.key == SDLK_RETURN || e.key.key == SDLK_KP_ENTER) {
          netplay_.answer(true);
          continue;
        }
        if (e.key.key == SDLK_ESCAPE || e.key.key == SDLK_BACKSPACE) {
          netplay_.answer(false);
          continue;
        }
      } else if (e.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN) {
        if (e.gbutton.button == SDL_GAMEPAD_BUTTON_SOUTH) netplay_.answer(true);
        else if (e.gbutton.button == SDL_GAMEPAD_BUTTON_EAST) netplay_.answer(false);
        continue;
      }
    }
    switch (e.type) {
      case SDL_EVENT_QUIT: quit_ = true; break;

      case SDL_EVENT_TEXT_INPUT: menu_.typeText(e.text.text); break;

      case SDL_EVENT_WINDOW_FOCUS_LOST:
        // Fenêtre quittée (Alt+Tab…) : le jeu se met en pause.
        if (args_.testSeconds.empty()) openMenu();
        break;

      case SDL_EVENT_KEY_DOWN: {
        // Écran des touches du menu : la touche appuyée est attribuée au bouton choisi.
        if (menu_.waitingKey()) {
          if (!e.key.repeat) menu_.keyPressed(e.key.scancode);
          break;
        }
        SDL_Keycode key = e.key.key;
        bool alt = (e.key.mod & SDL_KMOD_ALT) != 0;
        if (key == SDLK_F11 || (key == SDLK_RETURN && alt)) {
          video_.toggleFullscreen();
          break;
        }
        if (!menu_.isOpen()) {
          if (key == SDLK_ESCAPE || key == SDLK_F1) openMenu();
          else if (key == SDLK_F2) toast(menu_.tr(saveState() ? "state_saved" : "state_error"));
          // Partie à plusieurs : un état chargé ne le serait que sur cet appareil.
          else if (key == SDLK_F4 && !netplay_.playing()) toast(menu_.tr(!fileExists(statePath()) ? "no_state" : loadState() ? "state_loaded" : "state_error"));
          break;
        }
        Nav nav;
        switch (key) {
          case SDLK_UP: nav = Nav::Up; break;
          case SDLK_DOWN: nav = Nav::Down; break;
          case SDLK_LEFT: nav = Nav::Left; break;
          case SDLK_RIGHT: nav = Nav::Right; break;
          case SDLK_RETURN: case SDLK_KP_ENTER: nav = Nav::Confirm; break;
          // Recherche dans les options en cours : l'espace s'ajoute au texte (SDL_EVENT_TEXT_INPUT),
          // retour arrière efface une lettre.
          case SDLK_SPACE:
            if (menu_.searching()) continue;
            nav = Nav::Confirm;
            break;
          case SDLK_BACKSPACE: nav = menu_.searching() ? Nav::Erase : Nav::Back; break;
          case SDLK_ESCAPE: case SDLK_F1: nav = Nav::Back; break;
          case SDLK_PAGEUP: nav = Nav::TabPrev; break;
          case SDLK_PAGEDOWN: nav = Nav::TabNext; break;
          case SDLK_TAB: nav = (e.key.mod & SDL_KMOD_SHIFT) ? Nav::TabPrev : Nav::TabNext; break;
          default: continue;
        }
        onMenuAction(menu_.handle(nav, menuState()));
        break;
      }

      case SDL_EVENT_GAMEPAD_BUTTON_DOWN: {
        auto button = (SDL_GamepadButton)e.gbutton.button;
        if (button == SDL_GAMEPAD_BUTTON_GUIDE) {
          if (menu_.isOpen()) closeMenu();
          else openMenu();
          break;
        }
        if (!menu_.isOpen()) break;
        if (menu_.waitingKey()) {
          if (button == SDL_GAMEPAD_BUTTON_EAST) menu_.keyPressed(SDL_SCANCODE_ESCAPE);  // annule
          break;
        }
        Nav nav;
        switch (button) {
          case SDL_GAMEPAD_BUTTON_DPAD_UP: nav = Nav::Up; break;
          case SDL_GAMEPAD_BUTTON_DPAD_DOWN: nav = Nav::Down; break;
          case SDL_GAMEPAD_BUTTON_DPAD_LEFT: nav = Nav::Left; break;
          case SDL_GAMEPAD_BUTTON_DPAD_RIGHT: nav = Nav::Right; break;
          case SDL_GAMEPAD_BUTTON_SOUTH: nav = Nav::Confirm; break;  // bouton du bas
          case SDL_GAMEPAD_BUTTON_EAST: nav = Nav::Back; break;     // bouton de droite
          case SDL_GAMEPAD_BUTTON_NORTH: nav = Nav::Search; break;  // bouton du haut (Y)
          case SDL_GAMEPAD_BUTTON_WEST: nav = Nav::Erase; break;    // bouton de gauche (X)
          case SDL_GAMEPAD_BUTTON_LEFT_SHOULDER: nav = Nav::TabPrev; break;
          case SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER: nav = Nav::TabNext; break;
          default: continue;
        }
        onMenuAction(menu_.handle(nav, menuState()));
        break;
      }
    }
  }
  // Invité : l'hôte a quitté la partie, le jeu se ferme aussi (retour à l'application).
  if (netplay_.hostLeft()) {
    logf("Netplay : l'hôte a quitté la partie, fermeture du jeu");
    quit_ = true;
  }
  // Touches de volume du clavier (reçues pendant le traitement des messages ci-dessus) : son de RomCloud.
  if (int steps = takeVolumeSteps()) changeVolume(steps * 5);
  // Start + Select : ouvre le menu (une fois par appui, sinon il se rouvrirait aussitôt fermé).
  bool combo = input_.menuCombo();
  if (combo && !comboLatched_ && !menu_.isOpen()) openMenu();
  comboLatched_ = combo;
  return !quit_;
}

void Player::runFrame() {
  if (g.hasFrameTime && g.frameTime.callback) {
    double fps = g.av.timing.fps > 0 ? g.av.timing.fps : 60.0;
    g.frameTime.callback(g.frameTime.reference ? g.frameTime.reference : (retro_usec_t)(1e6 / fps));
  }
  runCore();
  if (g.avChanged) {
    g.avChanged = false;
    if (std::fabs(g.av.timing.sample_rate - audio_.coreRate()) > 1.0) audio_.open(g.av.timing.sample_rate);
  }
}

void Player::runFrameAhead() {
  const bool together = netplay_.enabled() || !netplay_.linked().empty();
  const size_t size = runAhead_ > 0 && !together ? g.api.serialize_size() : 0;
  if (!size) {
    runFrame();
    return;
  }
  g.hideVideo = true;
  runFrame();
  runAheadState_.resize(size);
  g.runAheadState = true;
  bool saved = g.api.serialize(runAheadState_.data(), size);
  g.runAheadState = false;
  if (saved) {
    g.muteAudio = true;
    for (int i = 1; i <= runAhead_; i++) {
      g.hideVideo = i < runAhead_;
      runFrame();
    }
    g.muteAudio = false;
  }
  g.hideVideo = false;
  g.runAheadState = true;
  saved = saved && g.api.unserialize(runAheadState_.data(), size);
  g.runAheadState = false;
  if (!saved) {
    // Cœur sans états fiables : run-ahead coupé pour la partie (réglage gardé).
    logf("Run-ahead coupé : état du cœur non enregistré ou non rechargé");
    toast(menu_.tr("runahead_failed"));
    runAhead_ = 0;
    runAheadState_.clear();
  }
}

void Player::present() {
  // DLSS : téléchargement, prêt ou impossible (FSR à sa place).
  if (const std::string message = video_.takeDlssMessage(); !message.empty()) toast(menu_.tr(message.c_str()));
  // Surimpression aux proportions de la fenêtre (360 lignes, texte à l'échelle de l'écran) ;
  // menu sur 480 lignes (deux colonnes : libellés plus longs) ; options du cœur sur 540 lignes :
  // texte 1,5 fois plus petit et plus d'options visibles.
  int winW = 16, winH = 9;
  SDL_GetWindowSizeInPixels(video_.window(), &winW, &winH);
  const int canvasHeight = menu_.inOptions() ? 540 : menu_.isOpen() ? 480 : 360;
  int canvasWidth = std::clamp(winH > 0 ? (int)std::lround((double)canvasHeight * winW / winH) : canvasHeight * 16 / 9,
                               canvasHeight * 11 / 9, canvasHeight * 32 / 9);
  if (canvasWidth != overlay_.width() || canvasHeight != overlay_.height()) overlay_.resize(canvasWidth, canvasHeight);
  for (const auto& [key, name] : netplay_.takeMessages()) {
    // Invité arrivé : notification en haut de l'écran (pas de confirmation demandée à l'hôte).
    if (key == "netplay_joined" || key == "link_started") {
      notice_ = trName(key.c_str(), name);
      noticeUntil_ = SDL_GetTicks() + 4000;
    } else {
      toast(trName(key.c_str(), name));
    }
  }
  const bool showNotice = SDL_GetTicks() < noticeUntil_ && !notice_.empty();
  bool showToast = SDL_GetTicks() < toastUntil_;
  if (!showToast && g.messageFrames > 0) {
    toast_ = g.message;
    g.messageFrames--;
    showToast = true;
  }
  auto drawToast = [&] {
    std::string text = Canvas::fit(toast_, 1, overlay_.width() - 60);
    int w = Canvas::measure(text, 1) + 24;
    int x = (overlay_.width() - w) / 2, y = overlay_.height() - 40;
    overlay_.fill(x, y, w, 22, rgba(0, 0, 0, 190));
    overlay_.text(x + 12, y + 7, text, 1, rgba(240, 240, 250));
  };
  // Jeu à plusieurs : bandeau en haut (attente, partie en cours, partie proposée), demande d'un invité.
  std::string banner, requester;
  const bool request = netplay_.requestPending(requester);
  const std::string linked = netplay_.linked();
  // Pause de l'autre joueur : affichée au centre (à la place du bandeau d'attente).
  const bool remotePaused = netplay_.remotePaused();
  if (remotePaused) banner.clear();
  else if (netplay_.waiting()) banner = trName("netplay_waiting", netplay_.partner());
  else if (netplay_.playing()) banner = trName(netplay_.isLink() ? "link_playing" : "netplay_playing", netplay_.partner());
  else if (!linked.empty()) banner = trName("link_playing", linked);
  else if (netplay_.open()) banner = menu_.tr("netplay_open");
  auto drawNetplay = [&] {
    if (!banner.empty()) {
      std::string text = Canvas::fit(banner, 1, overlay_.width() - 60);
      int w = Canvas::measure(text, 1) + 24, x = (overlay_.width() - w) / 2;
      overlay_.fill(x, 10, w, 20, rgba(0, 0, 0, 150));
      overlay_.text(x + 12, 16, text, 1, rgba(230, 230, 245));
    }
    if (showNotice) {
      std::string text = Canvas::fit(notice_, 1, overlay_.width() - 60);
      int w = Canvas::measure(text, 1) + 28, x = (overlay_.width() - w) / 2, y = banner.empty() ? 10 : 36;
      overlay_.roundRect((float)x, (float)y, (float)w, 24, 6, rgba(40, 34, 90, 230));
      overlay_.text(x + 14, y + 8, text, 1, rgba(245, 245, 255));
    }
    if (remotePaused && !request) {
      std::string title = menu_.tr("netplay_paused_title");
      std::string text = Canvas::fit(trName("netplay_remote_paused", netplay_.partner()), 1, overlay_.width() - 80);
      int w = std::max(Canvas::measure(title, 3), Canvas::measure(text, 1)) + 56;
      int x = (overlay_.width() - w) / 2, y = overlay_.height() / 2 - 40;
      overlay_.roundRect((float)x, (float)y, (float)w, 80, 8, rgba(0, 0, 0, 190));
      overlay_.text(x + (w - Canvas::measure(title, 3)) / 2, y + 16, title, 3, rgba(245, 245, 255));
      overlay_.text(x + (w - Canvas::measure(text, 1)) / 2, y + 56, text, 1, rgba(210, 210, 230));
    }
    if (request) {
      std::string title = Canvas::fit(trName(netplay_.isLink() ? "link_request" : "netplay_request", requester), 2, overlay_.width() - 80);
      std::string hint = menu_.tr("netplay_request_hint");
      int w = std::max(Canvas::measure(title, 2), Canvas::measure(hint, 1)) + 48;
      int x = (overlay_.width() - w) / 2, y = overlay_.height() / 2 - 34;
      overlay_.roundRect((float)x, (float)y, (float)w, 68, 8, rgba(20, 18, 40, 235));
      overlay_.roundRectOutline((float)x, (float)y, (float)w, 68, 8, 2, rgba(140, 130, 255));
      overlay_.text(x + (w - Canvas::measure(title, 2)) / 2, y + 14, title, 2, rgba(245, 245, 255));
      overlay_.text(x + (w - Canvas::measure(hint, 1)) / 2, y + 44, hint, 1, rgba(190, 190, 215));
    }
  };
  if (menu_.isOpen()) {
    overlay_.clear();
    if (padConfig_) padConfig_->render(overlay_);
    else if (players_) players_->render(overlay_);
    else menu_.render(overlay_, menuState());
    if (showToast && !toast_.empty()) drawToast();  // fin de la configuration d'une manette
    if (request) drawNetplay();
    overlayKey_.clear();  // menu dessiné dans la surimpression : à refaire ensuite
    video_.present(overlay_.data(), overlay_.width(), overlay_.height());
  } else if ((showToast && !toast_.empty()) || !banner.empty() || request || showNotice || remotePaused) {
    // Surimpression redessinée et renvoyée à la carte graphique seulement si elle a changé (bandeau
    // du jeu à plusieurs affiché pendant toute la partie : sinon refaite à chaque image).
    std::string key = std::to_string(overlay_.width()) + "x" + std::to_string(overlay_.height()) + '' +
                      (showToast ? toast_ : std::string()) + '' + banner + '' + (showNotice ? notice_ : std::string()) +
                      '' + (request ? requester : std::string()) + '' + (remotePaused ? netplay_.partner() : std::string());
    const bool changed = key != overlayKey_;
    if (changed) {
      overlay_.clear();
      if (showToast && !toast_.empty()) drawToast();
      drawNetplay();
      overlayKey_ = std::move(key);
    }
    video_.present(overlay_.data(), overlay_.width(), overlay_.height(), true, changed);
  } else {
    overlayKey_.clear();
    video_.present(nullptr, 0, 0);
  }
}

int Player::run() {
  if (args_.core.empty() || args_.rom.empty()) return fail("romcloud-player --core <cœur.dll> --rom <jeu>");
  g.language = args_.lang;
  g.systemDir = args_.systemDir.empty() ? joinPath(args_.saveDir, "system") : args_.systemDir;
  if (args_.saveDir.empty()) args_.saveDir = g.systemDir;
  if (args_.stateDir.empty()) args_.stateDir = args_.saveDir;
  makeDirs(g.systemDir);
  makeDirs(args_.saveDir);
  makeDirs(args_.stateDir);
  // nvngx_dlss.dll (filtre DLSS) à côté du dossier des BIOS : « libretro\dlss ».
  const size_t slash = g.systemDir.find_last_of("\\/");
  video_.setDlssDirectory(joinPath(slash == std::string::npos ? g.systemDir : g.systemDir.substr(0, slash), "dlss"));
  g.systemDir = compatiblePath(g.systemDir);
  g.saveDir = compatiblePath(args_.saveDir);
  g.options.load(args_.options);
  for (const auto& assignment : args_.optionDefaults) g.options.setGameDefault(assignment);
  for (const auto& assignment : args_.optionsForced) g.options.setForced(assignment);

  // Réseau prêt avant le cœur : certains ouvrent eux-mêmes leurs connexions (câble Game Link de
  // Gambatte, ad hoc de PPSSPP), sans initialiser Winsock comme le fait RetroArch.
  WSADATA wsa;
  WSAStartup(MAKEWORD(2, 2), &wsa);
  std::string error;
  if (!loadCore(args_.core, error)) return fail(menu_.tr("core_failed") + "\n" + args_.core + "\n" + error);
  g.video = &video_;
  g.audio = &audio_;
  g.input = &input_;
  initCore();
  if (!loadGame(error)) {
    g.api.deinit();
    return fail(error);
  }

  // Filtre d'image du système ; ancien réglage « lissage » (oui / non) repris s'il n'y en a pas.
  const std::string oldSmooth = g.options.setting("romcloud_smooth", "") == "true" ? "smooth" : "pixels";
  const bool hasOld = !g.options.setting("romcloud_smooth", "").empty();
  const std::string filter = g.options.setting("romcloud_filter", hasOld ? oldSmooth : "sharp");
  if (filter == "crtmask") {
    // Ancien « CRT+ » : devenu CRT avec ses réglages (masque RGB compris).
    g.options.setSetting("romcloud_filter", "crt");
    for (const auto& p : filterParams(Filter::Crt)) {
      std::string old = g.options.setting(std::string("romcloud_crtmask_") + p.id, "");
      if (old.empty() && std::string(p.id) == "mask") old = "0.30";  // masque par défaut de CRT+
      if (!old.empty()) g.options.setSetting(std::string("romcloud_crt_") + p.id, old);
    }
  }
  video_.setFilter(filterFromId(filter));
  video_.setAspect(aspectFromId(g.options.setting("romcloud_aspect", "core")));
  runAhead_ = std::clamp(atoi(g.options.setting("romcloud_runahead", "0").c_str()), 0, kMaxRunAhead);
  if (runAhead_) logf("Run-ahead : %d image(s) d'avance", runAhead_);
  loadFilterParams();
  const int testFrames = args_.testFrames.empty() ? 0 : std::max(1, atoi(args_.testFrames.c_str()));
  // Essais : fenêtre cachée (sauf --test-window), y compris pour la boucle réelle (--test-seconds).
  const bool hidden = (testFrames > 0 || !args_.testSeconds.empty()) && !args_.testWindow;
  if (!video_.create("RomCloud — " + title(), !args_.windowed && !testFrames, hidden, error)) {
    g.api.unload_game();
    g.api.deinit();
    return fail(menu_.tr("video_failed") + "\n" + error);
  }
  // Touches de volume du clavier pour le son de RomCloud (pas en essai : fenêtre souvent cachée).
  if (!hidden) startVolumeKeys(video_.window());
  input_.loadKeys(args_.keysFile);
  if (!args_.keysFile.empty()) {
    const size_t slash = args_.keysFile.find_last_of("/\\");
    global_.load((slash == std::string::npos ? std::string() : args_.keysFile.substr(0, slash + 1)) + "player.cfg");
  }
  audio_.setVolume(std::clamp(atoi(global_.setting("romcloud_volume", "100").c_str()), 0, 100) / 100.0f);
  menu_.setButtons(args_.buttons);
  setPadImagesDir(args_.padImages);
  setConsolePadStyle(args_.padStyle);
  applySavedPadMappings();  // manettes configurées pour ce système (avant leur ouverture)
  input_.init();
  loadSram();  // avant l'état de sauvegarde, qui la contient aussi
  // Invité d'une partie à plusieurs : l'état de l'hôte remplace la partie reprise.
  if ((args_.resume || !args_.stateFile.empty()) && args_.netplayJoin.empty()) {
    // Quelques images d'abord : certains cœurs n'acceptent un état qu'une fois le jeu démarré
    // (resumeWarmupFrames) ; leur son est écarté.
    const int warmup = args_.testLoadAt.empty() ? resumeWarmupFrames(coreName()) : std::max(1, atoi(args_.testLoadAt.c_str()));
    for (int i = 0; i < warmup; i++) runFrame();
    const std::string path = args_.stateFile.empty() ? statePath() : args_.stateFile;
    const char* result = !fileExists(path) ? "no_state" : loadStateFile(path) ? "state_loaded" : "state_error";
    audio_.clear();
    logf("Reprise de la partie (%s) : %s", path.c_str(), result);
    toast(menu_.tr(result));
  }
  if (testFrames) {
    audio_.resetPeak();
    for (int i = 0; i < testFrames; i++) {
      runFrameAhead();
      if (args_.testWindow) {
        SDL_PumpEvents();
        present();
      }
    }
    bool saved = true;
    if (args_.testWindow) {
      if (args_.testMenu || args_.testKeys || args_.testPad || args_.testPlayers || args_.testFilterSettings || args_.testStates || !args_.testOptions.empty() ||
          !args_.testSearch.empty() || args_.testKeyboard) menu_.open();
      if (args_.testKeys) menu_.openKeys();
      if (args_.testStates) openStates();
      if (args_.testPad) startPadConfig();
      if (args_.testPlayers) startPlayers();
      if (args_.testFilterSettings) onMenuAction(MenuAction::ImageSettings);
      if (!args_.testOptions.empty() || !args_.testSearch.empty() || args_.testKeyboard) {
        menu_.openOptions();
        for (int i = atoi(args_.testOptions.c_str()); i > 0; i--) menu_.handle(Nav::TabNext, menuState());
        menu_.typeText(args_.testSearch.c_str());
        if (args_.testKeyboard) menu_.handle(Nav::Search, menuState());
      }
      present();  // image affichée (et menu), relue avant l'échange des tampons
      if (!args_.screenshot.empty()) {
        overlay_.clear();
        if (padConfig_) padConfig_->render(overlay_);
        else if (players_) players_->render(overlay_);
        else if (menu_.isOpen()) menu_.render(overlay_, menuState());
        video_.present(menu_.isOpen() ? overlay_.data() : nullptr, overlay_.width(), overlay_.height(), false);
        saved = video_.saveWindow(args_.screenshot);
      }
    } else if (!args_.screenshot.empty()) {
      saved = video_.saveFrame(args_.screenshot);
    }
    if (args_.testSaveState) logf("Essai : état %s", saveState() ? "enregistré" : "impossible");
    // Comme « Charger l'état » du menu : premier état de l'historique, puis la partie continue.
    if (args_.testReload) {
      openMenu();
      openStates();
      onMenuAction(menu_.isOpen() ? MenuAction::LoadHistoryState : MenuAction::None);
      logf("Essai : état rechargé en cours de partie (%s)", toast_.c_str());
      for (int i = 0; i < testFrames; i++) runFrame();
      if (!args_.screenshot.empty()) video_.saveFrame(args_.screenshot + ".reload.bmp");
    }
    if (args_.testAudio) logf("Essai : son, amplitude maximale %d", audio_.peak());
    logf("Essai : %d images exécutées, capture %s", testFrames, saved ? "enregistrée" : "impossible");
    shutdown();
    return saved ? 0 : 1;
  }
  audio_.open(g.av.timing.sample_rate);
  raiseGamePriority();
  toast(menu_.tr("menu_hint"));
  startNetplay();

  const double freq = (double)SDL_GetPerformanceFrequency();
  // Essai de la boucle réelle (rythme audio) : arrêt au bout de N secondes, cadence mesurée.
  const double testSeconds = args_.testSeconds.empty() ? 0 : atof(args_.testSeconds.c_str());
  const double startTime = (double)SDL_GetPerformanceCounter() / freq;
  long frames = 0;
  while (handleEvents()) {
    // Partie synchronisée : pause (menu ouvert) annoncée à l'autre joueur.
    netplay_.setPaused(menu_.isOpen());
    if (testSeconds > 0 && (double)SDL_GetPerformanceCounter() / freq - startTime > testSeconds) {
      logf("Essai : %ld images en %.1f s (%.2f images/s, cœur %.2f)", frames, testSeconds, frames / testSeconds, g.av.timing.fps);
      // Dernière image du jeu enregistrée (--screenshot) : vérifie l'affichage dans la boucle réelle.
      if (!args_.screenshot.empty()) logf("Essai : capture %s", video_.saveFrame(args_.screenshot) ? "enregistrée" : "impossible");
      break;
    }
    if (menu_.isOpen()) {
      present();
      if (!video_.swapInterval()) SDL_Delay(16);
      continue;
    }
    double fps = g.av.timing.fps > 0 ? g.av.timing.fps : 60.0;
    double now = (double)SDL_GetPerformanceCounter() / freq;
    // Écran au rythme du jeu : synchro verticale, une image du jeu par rafraîchissement (ou par 2…),
    // affichée régulièrement et sans déchirure ; le son suit, légèrement accéléré ou ralenti.
    // Écran plus rapide que le jeu sans en être un multiple (60 images/s sur 144 Hz, jeu PAL sur
    // 60 Hz…) : synchro verticale aussi, sans déchirure (G-Sync / FreeSync : écran au rythme exact
    // du jeu), rythme toujours donné par le son.
    const int interval = video_.syncInterval(fps);
    video_.setSwapInterval(interval > 0 ? interval : video_.refreshRate() > fps * 1.05 ? 1 : 0);
    if (interval > 0) {
      nextFrame_ = 0;
      if (audio_.isOpen() && audio_.producing()) {
        const double frameAudio = audio_.rate() / fps;
        const double queued = (double)audio_.queuedFrames();
        // Tampons échangés sans attendre l'écran (fenêtre réduite, synchro forcée par le pilote) :
        // le son garde le rythme.
        if (queued > frameAudio * 8) {
          SDL_Delay(1);
          continue;
        }
        const double target = frameAudio * 3;
        const double error = std::clamp((queued - target) / target, -1.0, 1.0);
        audio_.setSpeed(video_.refreshRate() / interval / fps * (1.0 + 0.005 * error));
        // File presque vide (démarrage, image trop lente) : une image de plus, non affichée.
        if (queued < frameAudio && !netplay_.enabled()) runFrame();
      }
    } else if (audio_.isOpen() && audio_.producing()) {
      audio_.setSpeed(1.0);
      // Rythme donné par le son : quelques images d'avance dans la file audio, pas plus.
      double frameAudio = audio_.rate() / fps;
      if ((double)audio_.queuedFrames() > frameAudio * 3) {
        SDL_Delay(1);
        continue;
      }
      nextFrame_ = now;
    } else {
      // Cœur silencieux : rythme donné par l'horloge.
      if (nextFrame_ == 0 || now - nextFrame_ > 0.25) nextFrame_ = now;
      if (now < nextFrame_) {
        SDL_Delay(1);
        continue;
      }
      nextFrame_ += 1.0 / fps;
    }
    // Jeu à plusieurs : image émulée seulement quand les touches de l'autre joueur sont arrivées
    // (en attendant, la fenêtre reste réactive et affiche l'attente).
    if (netplay_.enabled()) {
      auto local = [this](unsigned port, unsigned device, unsigned index, unsigned id) { return input_.state(port, device, index, id); };
      if (netplay_.prepare(local) == Netplay::Frame::Wait) {
        present();
        SDL_Delay(1);
        continue;
      }
    }
    runFrameAhead();
    netplay_.finishFrame();
    present();
    frames++;
  }

  saveSram();
  g.options.writeCatalog(args_.optionsCatalog);
  audio_.close();
  input_.shutdown();
  shutdown();
  return 0;
}

/** Jeu à plusieurs demandé au lancement : partie proposée sur le réseau local, ou rejointe. */
void Player::startNetplay() {
  if (!args_.linked.empty()) {
    netplay_.setLinked(args_.linked);
    netplay_.announce(args_.deviceId, args_.deviceName.empty() ? "PC" : args_.deviceName);
    toast(trName("link_started", args_.linked));
  }
  if (!args_.netplayHost && args_.netplayJoin.empty()) return;
  Netplay::Config config;
  config.role = args_.netplayHost ? Netplay::Role::Host : Netplay::Role::Guest;
  std::string value;
  NetplayGame& game = config.game;
  if (jsonField(args_.netplayGame, "gameId", value)) game.gameId = atoll(value.c_str());
  jsonField(args_.netplayGame, "systemId", game.systemId);
  jsonField(args_.netplayGame, "title", game.title);
  jsonField(args_.netplayGame, "fileName", game.fileName);
  jsonField(args_.netplayGame, "core", game.core);
  jsonField(args_.netplayGame, "session", game.session);
  config.listenPort = atoi(args_.netplayPort.c_str());
  config.delay = atoi(args_.netplayDelay.c_str());
  if (jsonField(args_.netplayGame, "size", value)) game.size = atoll(value.c_str());
  config.deviceId = args_.deviceId;
  config.deviceName = args_.deviceName.empty() ? "PC" : args_.deviceName;
  const size_t colon = args_.netplayJoin.rfind(':');
  if (colon != std::string::npos) {
    config.address = args_.netplayJoin.substr(0, colon);
    config.port = atoi(args_.netplayJoin.c_str() + colon + 1);
  }
  config.peerName = args_.netplayPeer;
  config.link = args_.netplayLink;
  config.packets = !config.link.empty() && args_.netplayPackets;
  config.multi = !config.link.empty() && args_.netplayMulti;
  WIN32_FILE_ATTRIBUTE_DATA attributes;
  if (GetFileAttributesExW(widen(args_.core).c_str(), GetFileExInfoStandard, &attributes)) {
    config.coreSize = ((long long)attributes.nFileSizeHigh << 32) | attributes.nFileSizeLow;
  }
  if (!netplay_.start(config)) {
    toast(menu_.tr("netplay_unavailable"));
    return;
  }
  g.netplay = &netplay_;
}

/** Ordre de RetroArch et LibretroDroid : contexte du cœur, jeu, cœur, puis fenêtre. */
void Player::shutdown() {
  netplay_.stop();
  g.netplay = nullptr;
  stopVolumeKeys();
  video_.destroyCoreContext();
  g.api.unload_game();
  g.api.deinit();
  video_.destroy();
  unloadCore();
}

}  // namespace

int main(int argc, char** argv) {
  SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
  SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_PS3, "1");  // DualShock 3 (pilote Sixaxis de Windows)
  // Fenêtre au premier plan même si le jeu a été lancé à la manette (Windows ne donne ce droit
  // qu'après une action au clavier ou à la souris).
  SDL_SetHint(SDL_HINT_FORCE_RAISEWINDOW, "1");
  for (int i = 1; i < argc; i++) {
    if (strcmp(argv[i], "--pad-service") != 0) continue;
    const Args a = parseArgs(argc, argv);
    if (!SDL_Init(SDL_INIT_GAMEPAD)) return 1;
    int code = runPadService({a.options, a.keysFile, a.buttons, a.lang, a.padStyle});
    SDL_Quit();
    return code;
  }
  if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_GAMEPAD | SDL_INIT_HAPTIC)) {
    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "RomCloud", SDL_GetError(), nullptr);
    return 1;
  }
  int code = Player(parseArgs(argc, argv)).run();
  SDL_Quit();
  return code;
}
