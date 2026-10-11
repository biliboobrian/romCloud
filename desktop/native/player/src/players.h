// Joueurs des manettes (depuis le menu) : chaque joueur, dans l'ordre, appuie sur un bouton de sa
// manette (joueur 1, puis 2…). L'ordre est mémorisé par manette (Input::setPlayerOrder) : une manette
// rebranchée retrouve son joueur. Appuyer de nouveau sur une manette déjà choisie, ou Entrée, termine ;
// les manettes non choisies prennent les places suivantes.
#pragma once

#include <SDL3/SDL.h>

#include <functional>
#include <string>
#include <vector>

#include "canvas.h"
#include "input.h"

class PlayerOrder {
 public:
  using Translate = std::function<std::string(const char*)>;

  PlayerOrder(Input& input, Translate tr) : input_(input), tr_(std::move(tr)) {}

  /** Traite un évènement ; vrai s'il est pris par cet écran (clavier et boutons des manettes). */
  bool handleEvent(const SDL_Event& e);
  bool finished() const { return finished_; }
  /** Message de fin traduit (vide si annulé). */
  const std::string& result() const { return result_; }
  void render(Canvas& c) const;

 private:
  void finish(bool save);

  Input& input_;
  Translate tr_;
  std::vector<SDL_JoystickID> order_;  // manettes choisies : joueur 1, 2…
  bool finished_ = false;
  std::string result_;
};
