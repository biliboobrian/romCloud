// Menu du moteur (Échap, bouton central de la manette ou Start + Select) : reprendre, états de
// sauvegarde, redémarrage, options du cœur (un onglet par type), touches du clavier,
// configuration des manettes, joueurs des manettes, disque,
// image (filtre et ses réglages), format d'image, run-ahead, plein écran, sauvegarder et quitter, quitter.
#pragma once

#include <SDL3/SDL.h>

#include <string>
#include <utility>
#include <vector>

#include "canvas.h"

enum class Nav { Up, Down, Left, Right, Confirm, Back, TabPrev, TabNext };

enum class MenuAction { None, Resume, SaveState, LoadState, LoadHistoryState, Reset, SaveQuit, Quit, ImageSettings, FilterChoice, NextAspect, ConfigurePad, Players, FilterParam, FilterDefaults, SoundSettings, ToggleFullscreen, DiskNext, DiskPrev, LeaveNetplay, NextRunAhead };

/** État affiché par le menu (fourni par la boucle principale). */
struct MenuState {
  const char* filterLabel = "filter_sharp";  // clé du nom du filtre d'image (video.h)
  int aspect = 0;  // Aspect (video.h) : celui du cœur par défaut
  bool fullscreen = true;
  int diskIndex = -1;  // -1 : pas de changement de disque
  int diskCount = 0;
  bool hasState = false;
  // Jeu à plusieurs : partie en cours (charger un état, redémarrer, options du cœur et disque masqués :
  // ils ne changeraient que cet appareil), ou proposée.
  bool together = false;
  bool netplay = false;
  int volume = 100;  // volume du son (%), commun à tous les émulateurs
  int runAhead = 0;   // images calculées en avance (run-ahead), 0 : sans
};

/**
 * Réglage du filtre affiché dans l'écran « Réglages du filtre » : valeur montrée en pourcentage, en
 * nombre entier ([integer]) ou par le nom du choix ([choices], 0 au premier).
 */
struct FilterRow {
  std::string label;
  float value, def, min, max, step;
  bool integer = false;
  std::vector<std::string> choices;
};

class Menu {
 public:
  Menu(std::string language, std::string title, std::string core);

  void open();
  void close() { open_ = false; }
  bool isOpen() const { return open_; }
  /** Écran des options du cœur ou des touches affiché (dessiné en plus petit : nombreuses lignes). */
  bool inOptions() const { return open_ && screen_ != Screen::Main; }
  /** Écran des options du cœur, premier onglet. */
  void openOptions();

  /** Écran des touches du clavier. */
  void openKeys();
  /** Historique des états (une ligne par état : date, appareil) ; A / Entrée : LoadHistoryState. */
  void openStates(std::vector<std::string> rows);
  /** État choisi dans l'historique (index dans les lignes de openStates). */
  int stateSelected() const { return stateSelected_; }
  /**
   * Boutons proposés dans l'écran des touches (ceux de la console) : « bouton[=nom] » séparés par
   * des virgules (--buttons) ; vide : tous les boutons du RetroPad.
   */
  void setButtons(const std::string& spec);
  /** Écran des touches en attente d'une touche pour le bouton choisi. */
  bool waitingKey() const { return open_ && waitingKey_; }
  /** Touche appuyée pendant l'attente : attribuée, sauf Échap (annule) et touches réservées. */
  void keyPressed(SDL_Scancode code);

  MenuAction handle(Nav nav, const MenuState& state);
  void render(Canvas& canvas, const MenuState& state) const;

  /**
   * Écran « Image », panneau à droite pour garder le jeu visible : liste des filtres ([choices] : leurs
   * noms, [chosen] : celui affiché ; A / Entrée : FilterChoice), puis les réglages du filtre ([rows]) :
   * gauche / droite changent la valeur choisie (FilterParam), « Valeurs par défaut » les rétablit
   * toutes (FilterDefaults).
   */
  void openImageSettings(std::vector<std::pair<std::string, std::string>> choices, int chosen, std::vector<FilterRow> rows);
  /** Filtre choisi par la dernière action FilterChoice (rang dans la liste). */
  int filterChoice() const { return filterChoice_; }
  /** Filtre affiché après FilterChoice, avec ses réglages. */
  void setFilterRows(int chosen, std::vector<FilterRow> rows);
  /** Panneau en bas pour le son (volume, commun à tous les émulateurs) : FilterParam / FilterDefaults. */
  void openSoundSettings(std::vector<FilterRow> rows);
  /** Panneau du son ouvert (sinon celui du filtre). */
  bool inSoundSettings() const { return open_ && screen_ == Screen::FilterSettings && sound_; }
  const std::vector<FilterRow>& filterRows() const { return filterRows_; }
  /** Réglage changé par la dernière action FilterParam. */
  int filterRowChanged() const { return filterChanged_; }

  /** Texte traduit (menu et messages du moteur). */
  std::string tr(const char* key) const;

 private:
  enum class Screen { Main, Options, Keys, States, FilterSettings };
  enum class Item { Resume, SaveState, LoadState, Reset, Options, Keys, Pad, Players, Disk, Filter, Aspect, RunAhead, Fullscreen, Sound, LeaveNetplay, SaveQuit, Quit };

  std::vector<Item> items(const MenuState& state) const;
  int itemCount(const MenuState& state) const;
  Item itemAt(int index, const MenuState& state) const;
  std::string itemLabel(Item item, const MenuState& state) const;
  std::string itemValue(Item item, const MenuState& state) const;
  void renderOptions(Canvas& canvas) const;
  void renderKeys(Canvas& canvas) const;
  void renderStates(Canvas& canvas) const;
  void renderFilterSettings(Canvas& canvas) const;
  MenuAction handleFilterSettings(Nav nav);
  MenuAction handleKeys(Nav nav);
  std::string keyName(SDL_Scancode code) const;
  std::string buttonLabel(unsigned id) const;

  std::string language_, title_, core_;
  bool open_ = false;
  Screen screen_ = Screen::Main;
  int selected_ = 0;
  mutable int mainScroll_ = 0;
  int optionTab_ = 0;  // onglet (type d'options) affiché
  int optionSelected_ = 0;
  mutable int optionScroll_ = 0;
  std::vector<std::pair<unsigned, std::string>> keyRows_;  // bouton RetroPad, nom (vide : nom par défaut)
  int keySelected_ = 0;  // 0 : « Touches par défaut », puis keyRows_
  bool waitingKey_ = false;
  std::string keyMessage_;  // touche refusée
  bool sound_ = false;  // panneau des réglages : son plutôt qu'image
  std::vector<std::pair<std::string, std::string>> filterChoices_;  // nom, description des filtres (image)
  int filterChosen_ = 0;
  int filterChoice_ = 0;
  std::vector<FilterRow> filterRows_;
  int filterSelected_ = 0;  // filtres, réglages, puis « Valeurs par défaut »
  mutable int filterScroll_ = 0;
  int filterChanged_ = -1;
  std::vector<std::string> stateRows_;
  int stateSelected_ = 0;
  mutable int stateScroll_ = 0;
};
