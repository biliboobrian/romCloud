// Menu du moteur (Échap, bouton central de la manette ou Start + Select) : reprendre, états de
// sauvegarde, redémarrage, options du cœur (un onglet par type), touches du clavier,
// configuration des manettes, joueurs des manettes, disque,
// filtre et format d'image, plein écran, sauvegarder et quitter, quitter.
#pragma once

#include <SDL3/SDL.h>

#include <string>
#include <utility>
#include <vector>

#include "canvas.h"

enum class Nav { Up, Down, Left, Right, Confirm, Back, TabPrev, TabNext };

enum class MenuAction { None, Resume, SaveState, LoadState, LoadHistoryState, Reset, SaveQuit, Quit, NextFilter, NextAspect, ConfigurePad, Players, FilterSettings, FilterParam, FilterDefaults, SoundSettings, ToggleFullscreen, DiskNext, DiskPrev, LeaveNetplay };

/** État affiché par le menu (fourni par la boucle principale). */
struct MenuState {
  int filter = 1;  // Filter (video.h) : lissage net par défaut
  int aspect = 0;  // Aspect (video.h) : celui du cœur par défaut
  bool fullscreen = true;
  int diskIndex = -1;  // -1 : pas de changement de disque
  int diskCount = 0;
  bool hasState = false;
  // Jeu à plusieurs : partie en cours (charger un état, redémarrer, options du cœur et disque masqués :
  // ils ne changeraient que cet appareil), ou proposée.
  bool together = false;
  bool netplay = false;
  bool filterSettings = false;  // le filtre choisi a des réglages
  int volume = 100;  // volume du son (%), commun à tous les émulateurs
};

/** Réglage du filtre affiché dans l'écran « Réglages du filtre » (valeur montrée en pourcentage). */
struct FilterRow {
  std::string label;
  float value, def, min, max, step;
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
   * Écran « Réglages du filtre » ([filter] : son nom), dessiné en bas pour garder le jeu visible :
   * gauche / droite changent la valeur choisie (FilterParam), « Valeurs par défaut » les rétablit
   * toutes (FilterDefaults).
   */
  void openFilterSettings(std::string filter, std::vector<FilterRow> rows);
  /** Même panneau pour le son (volume, commun à tous les émulateurs) : FilterParam / FilterDefaults. */
  void openSoundSettings(std::vector<FilterRow> rows);
  /** Panneau du son ouvert (sinon celui du filtre). */
  bool inSoundSettings() const { return open_ && screen_ == Screen::FilterSettings && sound_; }
  const std::vector<FilterRow>& filterRows() const { return filterRows_; }
  /** Réglage changé par la dernière action FilterParam. */
  int filterRowChanged() const { return filterChanged_; }
  /** Écran des réglages du filtre affiché (surimpression réduite, jeu visible). */
  bool inFilterSettings() const { return open_ && screen_ == Screen::FilterSettings; }

  /** Texte traduit (menu et messages du moteur). */
  std::string tr(const char* key) const;

 private:
  enum class Screen { Main, Options, Keys, States, FilterSettings };
  enum class Item { Resume, SaveState, LoadState, Reset, Options, Keys, Pad, Players, Disk, Filter, FilterSettings, Aspect, Fullscreen, Sound, LeaveNetplay, SaveQuit, Quit };

  std::vector<Item> items(const MenuState& state) const;
  int itemCount(const MenuState& state) const;
  Item itemAt(int index, const MenuState& state) const;
  std::string itemLabel(Item item, const MenuState& state) const;
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
  int optionTab_ = 0;  // onglet (type d'options) affiché
  int optionSelected_ = 0;
  mutable int optionScroll_ = 0;
  std::vector<std::pair<unsigned, std::string>> keyRows_;  // bouton RetroPad, nom (vide : nom par défaut)
  int keySelected_ = 0;  // 0 : « Touches par défaut », puis keyRows_
  bool waitingKey_ = false;
  std::string keyMessage_;  // touche refusée
  std::string filterName_;
  bool sound_ = false;  // panneau des réglages : son plutôt que filtre
  std::vector<FilterRow> filterRows_;
  int filterSelected_ = 0;  // réglages, puis « Valeurs par défaut »
  int filterChanged_ = -1;
  std::vector<std::string> stateRows_;
  int stateSelected_ = 0;
  mutable int stateScroll_ = 0;
};
