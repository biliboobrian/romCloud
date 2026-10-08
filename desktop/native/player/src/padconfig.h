// Configuration d'une manette pour la console, étape par étape (comme l'application Android) :
// l'utilisateur appuie sur chaque bouton demandé (bouton, croix ou gâchette analogique), puis pousse
// le stick droit. Les boutons sont lus sur la manette brute (SDL_Joystick) : les manettes inconnues
// de SDL sont aussi configurables. La première manette utilisée est celle configurée ; appuyer de
// nouveau sur le dernier bouton passe l'étape. Le résultat est une correspondance SDL (SDL_Gamepad)
// mémorisée pour le système et le modèle de manette (GUID), appliquée au lancement suivant et aussitôt.
#pragma once

#include <SDL3/SDL.h>

#include <functional>
#include <set>
#include <string>
#include <vector>

#include "canvas.h"

/** Applique les correspondances mémorisées pour le système ; avant l'ouverture des manettes. */
void applySavedPadMappings();

/** Étape : un bouton du RetroPad ([id]) ou un axe du stick droit ([stick] : 'x' ou 'y'). */
struct PadStep {
  unsigned id = 0;
  char stick = 0;
  std::string label;
};

class PadConfig {
 public:
  using Translate = std::function<std::string(const char*)>;

  /**
   * [buttons] : boutons de la console (--buttons, « bouton[=nom] ») ; [style] : sa manette
   * (--pad-style : « snes », « psx »…), pour son dessin ; [tr] : textes traduits.
   */
  PadConfig(const std::string& buttons, const std::string& style, Translate tr);
  ~PadConfig();
  PadConfig(const PadConfig&) = delete;
  PadConfig& operator=(const PadConfig&) = delete;

  /** Traite un évènement ; vrai s'il est pris par la configuration (clavier et manettes). */
  bool handleEvent(const SDL_Event& e);
  /** Configuration terminée : enregistrée, rétablie par défaut ou annulée. */
  bool finished() const { return finished_; }
  /** Message de fin traduit (vide si annulée). */
  const std::string& result() const { return result_; }
  void render(Canvas& c) const;

 private:
  void lock(SDL_JoystickID id);
  void onButton(int button, bool down);
  void onHat(int hat, Uint8 value);
  void onAxes();
  void assign(const std::string& source);
  void next();
  void save();
  void resetDevice();
  void finish(const char* message);
  float delta(int axis) const;
  bool usesAxis(int axis) const;
  std::string sourceLabel(const std::string& source) const;
  /** Manette de la console ; renvoie sa hauteur. */
  int renderPad(Canvas& c, int x, int y, int width) const;

  Translate tr_;
  std::string style_;
  std::vector<PadStep> steps_;
  std::vector<std::string> assigned_;  // source attribuée à chaque étape (« b3 », « h0.4 », « +a2 »…), vide : aucune
  std::vector<SDL_Joystick*> opened_;
  SDL_Joystick* joystick_ = nullptr;  // manette configurée
  std::string deviceName_;
  size_t step_ = 0;
  std::string message_;
  std::string lastButton_;  // dernier bouton attribué : appuyé de nouveau, l'étape est passée
  std::set<int> held_, ignoredReleases_;
  std::vector<float> rest_;  // position de repos de chaque axe (-1, 0 ou 1)
  std::set<int> leftStick_;  // axes du stick gauche : jamais pris pour une gâchette ou le stick droit
  int candidateAxis_ = -1;
  float candidateDirection_ = 0;
  int candidateHat_ = -1;
  Uint8 candidateHatValue_ = 0;
  bool settling_ = false;  // après un bouton : attendre que tous les axes soient revenus au repos
  bool finished_ = false;
  std::string result_;
};
