// Fenêtre SDL et rendu OpenGL : images des cœurs logiciels (texture), rendu matériel des cœurs
// OpenGL (framebuffer fourni au cœur, comme LibretroDroid), mise à l'échelle avec bandes noires,
// rotation et surimpression du menu.
#pragma once

#include <SDL.h>

#include <cstdint>
#include <string>

#include "libretro.h"

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

  /** Dessine la dernière image (et la surimpression RGBA si fournie) puis l'affiche. */
  void present(const uint8_t* overlay, int overlayWidth, int overlayHeight, bool swap = true);
  /** Enregistre l'image affichée dans la fenêtre (mode d'essai, avant l'échange des tampons). */
  bool saveWindow(const std::string& path);
  /** Taille maximale de l'image changée (SET_SYSTEM_AV_INFO / SET_GEOMETRY). */
  void onGeometryChanged();

  /** Enregistre la dernière image du cœur en BMP (mode d'essai). */
  bool saveFrame(const std::string& path);

  void toggleFullscreen();
  bool fullscreen() const { return fullscreen_; }
  void setSmooth(bool smooth) { smooth_ = smooth; }
  bool smooth() const { return smooth_; }
  SDL_Window* window() const { return window_; }

 private:
  bool createContext(std::string& error);
  bool loadFunctions();
  bool createProgram();
  void allocateHwFramebuffer();

  SDL_Window* window_ = nullptr;
  SDL_GLContext context_ = nullptr;
  bool coreProfile_ = false;
  bool fullscreen_ = true;
  bool smooth_ = false;

  unsigned program_ = 0, vao_ = 0, vbo_ = 0;
  int uniformTexture_ = -1, uniformAlpha_ = -1;
  unsigned frameTexture_ = 0, overlayTexture_ = 0;
  int textureWidth_ = 0, textureHeight_ = 0;
  unsigned fbo_ = 0, fboTexture_ = 0, fboDepth_ = 0;
  int fboWidth_ = 0, fboHeight_ = 0;

  // Dernière image reçue.
  bool hasFrame_ = false;
  bool frameIsHw_ = false;
  unsigned frameWidth_ = 0, frameHeight_ = 0;
};
