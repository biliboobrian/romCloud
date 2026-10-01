// Menu du moteur (Échap, bouton central de la manette ou Start + Select) : reprendre, états de
// sauvegarde, redémarrage, options du cœur (un onglet par type), disque, lissage, plein écran,
// sauvegarder et quitter, quitter.
#pragma once

#include <string>

#include "canvas.h"

enum class Nav { Up, Down, Left, Right, Confirm, Back, TabPrev, TabNext };

enum class MenuAction { None, Resume, SaveState, LoadState, Reset, SaveQuit, Quit, ToggleSmooth, ToggleFullscreen, DiskNext, DiskPrev };

/** État affiché par le menu (fourni par la boucle principale). */
struct MenuState {
  bool smooth = false;
  bool fullscreen = true;
  int diskIndex = -1;  // -1 : pas de changement de disque
  int diskCount = 0;
  bool hasState = false;
};

class Menu {
 public:
  Menu(std::string language, std::string title, std::string core);

  void open();
  void close() { open_ = false; }
  bool isOpen() const { return open_; }
  /** Écran des options du cœur, premier onglet. */
  void openOptions();

  MenuAction handle(Nav nav, const MenuState& state);
  void render(Canvas& canvas, const MenuState& state) const;

  /** Texte traduit (menu et messages du moteur). */
  std::string tr(const char* key) const;

 private:
  enum class Screen { Main, Options };
  enum class Item { Resume, SaveState, LoadState, Reset, Options, Disk, Smooth, Fullscreen, SaveQuit, Quit };

  int itemCount(const MenuState& state) const;
  Item itemAt(int index, const MenuState& state) const;
  std::string itemLabel(Item item, const MenuState& state) const;
  void renderOptions(Canvas& canvas) const;

  std::string language_, title_, core_;
  bool open_ = false;
  Screen screen_ = Screen::Main;
  int selected_ = 0;
  int optionTab_ = 0;  // onglet (type d'options) affiché
  int optionSelected_ = 0;
  mutable int optionScroll_ = 0;
};
