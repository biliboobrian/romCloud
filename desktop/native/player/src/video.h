// Fenêtre SDL et rendu OpenGL : images des cœurs logiciels (texture), rendu matériel des cœurs
// OpenGL (framebuffer fourni au cœur, comme LibretroDroid), mise à l'échelle avec bandes noires,
// rotation et surimpression du menu. Cœurs Vulkan : image recopiée par vulkan.h, affichée comme
// celle d'un cœur logiciel.
#pragma once

#include <SDL3/SDL.h>

#include <cstdint>
#include <string>
#include <vector>

#include "libretro.h"

/**
 * Filtre d'image (pixels de la console agrandis à l'écran), dans l'ordre du menu : pixels nets,
 * lissage net (pixels nets, transition lissée sur un pixel de l'écran : ni flou ni pixels inégaux),
 * lissage doux (bicubique), lissage bilinéaire, EPX (Scale2x : contours du pixel art arrondis),
 * écran cathodique (lignes de balayage), écran cathodique avec masque RGB, écran LCD (grille).
 * Le rang sert de numéro de mode au shader (u_mode).
 */
enum class Filter { Pixels, Sharp, Soft, Smooth, Epx, Crt, CrtMask, Lcd, Xbr, Fsr };
constexpr int kFilterCount = 10;
/** Filtre suivant dans l'ordre du menu (lissages, agrandisseurs, écrans). */
Filter nextFilter(Filter filter);
/** Identifiant mémorisé dans les réglages (« sharp »…) et inverse (lissage net par défaut). */
const char* filterId(Filter filter);
Filter filterFromId(const std::string& id);

/**
 * Réglage d'un filtre : identifiant mémorisé pour le système (« romcloud_<filtre>_<réglage> »), clé du
 * libellé, composante du shader (u_params), valeur par défaut, bornes et pas.
 */
struct FilterParam {
  const char* id;
  const char* label;
  int slot;
  float def, min, max, step;
};
/** Réglages proposés pour le filtre (aucun : pixels, lissages, EPX, xBR). */
std::vector<FilterParam> filterParams(Filter filter);

/** Format de l'image : celui du cœur, 4:3, 16:9, ou étiré sur toute la fenêtre (ordre du menu). */
enum class Aspect { Core, Ratio4_3, Ratio16_9, Stretch };
constexpr int kAspectCount = 4;
Aspect nextAspect(Aspect aspect);
/** Identifiant mémorisé dans les réglages (« core »…) et inverse (celui du cœur par défaut). */
const char* aspectId(Aspect aspect);
Aspect aspectFromId(const std::string& id);

class Video {
 public:
  /** Crée la fenêtre et un contexte OpenGL adapté au cœur ; appelé après retro_load_game. */
  bool create(const std::string& title, bool fullscreen, bool hidden, std::string& error);
  /** Prévient le cœur de la fin du contexte OpenGL (avant retro_unload_game, comme RetroArch). */
  void destroyCoreContext();
  void destroy();

  // Callbacks libretro.
  void onFrame(const void* data, unsigned width, unsigned height, size_t pitch);
  uintptr_t currentFramebuffer() const { return fbo_; }
  static retro_proc_address_t procAddress(const char* sym);

  /**
   * Dessine la dernière image (et la surimpression RGBA si fournie) puis l'affiche ; [overlayChanged]
   * faux : surimpression identique à la précédente, pas renvoyée à la carte graphique.
   */
  void present(const uint8_t* overlay, int overlayWidth, int overlayHeight, bool swap = true, bool overlayChanged = true);
  /** Enregistre l'image affichée dans la fenêtre (mode d'essai, avant l'échange des tampons). */
  bool saveWindow(const std::string& path);
  /** Taille maximale de l'image changée (SET_SYSTEM_AV_INFO / SET_GEOMETRY). */
  void onGeometryChanged();

  /** Enregistre la dernière image du cœur en BMP (mode d'essai). */
  bool saveFrame(const std::string& path);
  /** Copie de la dernière image du cœur (RGBA, à libérer avec SDL_DestroySurface), nulle sans image. */
  SDL_Surface* captureFrame();

  void toggleFullscreen();
  bool fullscreen() const { return fullscreen_; }
  void setFilter(Filter filter) { filter_ = filter; }
  /** Valeur d'un réglage du filtre (composante [slot] de u_params). */
  void setFilterParam(int slot, float value) {
    if (slot >= 0 && slot < 4) params_[slot] = value;
  }
  Filter filter() const { return filter_; }
  void setAspect(Aspect aspect) { aspect_ = aspect; }
  Aspect aspect() const { return aspect_; }
  SDL_Window* window() const { return window_; }

 private:
  bool createContext(std::string& error);
  bool loadFunctions();
  bool createProgram();
  /** Dessin de [present], état OpenGL du cœur gardé autour. */
  void draw(const uint8_t* overlay, int overlayWidth, int overlayHeight, bool overlayChanged);
  void allocateHwFramebuffer();
  bool preparePostTarget(int width, int height);
  // Cœurs Vulkan : image partagée avec OpenGL (sans recopie), sinon recopiée (vulkan::readFrame).
  void setupInterop();
  bool showShared(unsigned width, unsigned height);
  void releaseShared();
  void dropShared();

  SDL_Window* window_ = nullptr;
  SDL_GLContext context_ = nullptr;
  bool coreProfile_ = false;
  bool fullscreen_ = true;
  Filter filter_ = Filter::Sharp;
  Aspect aspect_ = Aspect::Core;

  unsigned program_ = 0, vao_ = 0, vbo_ = 0;
  int uniformTexture_ = -1, uniformAlpha_ = -1, uniformMode_ = -1, uniformSize_ = -1, uniformScale_ = -1;
  int uniformParams_ = -1, uniformRect_ = -1;
  float params_[4] = {1.0f, 0.0f, 0.0f, 0.0f};
  unsigned frameTexture_ = 0, overlayTexture_ = 0;
  int overlayTextureWidth_ = 0, overlayTextureHeight_ = 0;  // taille de la surimpression envoyée
  // Image partagée avec Vulkan : texture sur sa mémoire, sémaphores « prête » et « rendue » ;
  // [releaseOwed_] : image tenue par OpenGL, à rendre à Vulkan avant la copie suivante.
  bool interopTried_ = false, interopOk_ = false, sharedActive_ = false, releaseOwed_ = false, sharedBgra_ = false;
  unsigned sharedTexture_ = 0, sharedMemory_ = 0, readySemaphore_ = 0, releasedSemaphore_ = 0;
  int sharedWidth_ = 0, sharedHeight_ = 0;
  int textureWidth_ = 0, textureHeight_ = 0;
  unsigned fbo_ = 0, fboTexture_ = 0, fboDepth_ = 0;
  // Image intermédiaire de FSR (agrandissement, puis netteté à l'écran).
  unsigned postFbo_ = 0, postTexture_ = 0;
  int postWidth_ = 0, postHeight_ = 0;
  bool postOk_ = false;
  int fboWidth_ = 0, fboHeight_ = 0;

  // Dernière image reçue.
  bool hasFrame_ = false;
  bool frameIsHw_ = false;
  unsigned frameWidth_ = 0, frameHeight_ = 0;
};
