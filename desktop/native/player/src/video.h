// Fenêtre SDL et rendu OpenGL : images des cœurs logiciels (texture), rendu matériel des cœurs
// OpenGL (framebuffer fourni au cœur, comme LibretroDroid), mise à l'échelle avec bandes noires,
// rotation et surimpression du menu. Cœurs Vulkan : image recopiée par vulkan.h, affichée comme
// celle d'un cœur logiciel.
#pragma once

#include <SDL3/SDL.h>

#include <cstdint>
#include <string>
#include <vector>

#include "dlss.h"
#include "libretro.h"
#include "shaderchain.h"

/**
 * Shader d'image (pixels de la console agrandis à l'écran) : Nearest (pixels bruts), Sharp Bilinear
 * (pixels nets, transition lissée sur un pixel de l'écran : ni flou ni pixels inégaux), Bicubic,
 * Bilinear, Scale2x (EPX : contours du pixel art arrondis), CRT (lignes de balayage, masque RGB),
 * LCD (grille), xBR, FSR, CUT, CUT2 et CUT3 (ceux de LibretroDroid, comme sous Android), écran de
 * GBA / GBC (couleurs corrigées, grille, rémanence), Game Boy (matrice de points, palette verte,
 * rémanence), NVIDIA DLSS (dlss.h ; FSR à sa place sans carte GeForce RTX). Le rang sert de numéro
 * de mode au shader (u_mode) ; CrtMask : ancien « CRT+ », lu comme CRT (masque réglable dans CRT).
 */
enum class Filter { Pixels, Sharp, Soft, Smooth, Epx, Crt, CrtMask, Lcd, Xbr, Fsr, Cut, Cut2, Cut3, Handheld, DotMatrix, Dlss };
constexpr int kFilterCount = 16;
/** Shaders proposés dans la liste du menu. */
constexpr int kFilterListCount = 15;
/** Shader de rang [index] dans la liste du menu (lissages, agrandisseurs, écrans) et rang inverse. */
Filter filterAt(int index);
int filterIndex(Filter filter);
/** Clé du nom affiché (menu) et de la description du filtre. */
const char* filterLabel(Filter filter);
const char* filterDescription(Filter filter);
/** Identifiant mémorisé dans les réglages (« sharp »…) et inverse (lissage net par défaut). */
const char* filterId(Filter filter);
Filter filterFromId(const std::string& id);

/** Valeur d'un réglage affichée en pourcentage, en nombre entier ou par son nom (choix, oui / non). */
enum class ParamFormat { Percent, Integer, Choice };

/**
 * Réglage d'un filtre : identifiant mémorisé pour le système (« romcloud_<filtre>_<réglage> », sauf
 * le lissage du tramage : « romcloud_dedither », commun aux filtres), clé du libellé, composante du
 * shader (0 à 7 : u_params et u_params2 ; 8 à 11 : couleurs, u_color ; 12 : rémanence, 13 : lissage
 * du tramage, passe préalable ; 14 à 24 : réglages de CUT, CUT2 et CUT3), valeur par défaut, bornes,
 * pas, affichage et noms des choix (clés séparées par « | », 0 au premier).
 */
struct FilterParam {
  const char* id;
  const char* label;
  int slot;
  float def, min, max, step;
  ParamFormat format = ParamFormat::Percent;
  const char* choices = nullptr;
};
constexpr int kFilterSlots = 25;
constexpr int kGhostSlot = 12;
constexpr int kDeditherSlot = 13;
constexpr int kCutSlot = 14;
/**
 * Réglages du filtre : les siens, puis luminosité, contraste, saturation, gamma et lissage du tramage
 * (tous les filtres).
 */
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
  void setFilter(Filter filter) {
    filter_ = filter;
    prepDirty_ = true;
  }
  /** Valeur d'un réglage du filtre (composante [slot] de u_params, u_params2 ou u_color). */
  void setFilterParam(int slot, float value) {
    if (slot >= 0 && slot < kFilterSlots && params_[slot] != value) {
      params_[slot] = value;
      prepDirty_ = true;
    }
  }
  Filter filter() const { return filter_; }
  /** Dossier de nvngx_dlss.dll (filtre DLSS). */
  void setDlssDirectory(const std::string& dir) { dlss_.setDirectory(dir); }
  /** Message du DLSS à montrer (clé du menu : téléchargement, prêt, échec), une fois ; vide sinon. */
  std::string takeDlssMessage() { return dlss_.takeMessage(); }
  void setAspect(Aspect aspect) { aspect_ = aspect; }
  Aspect aspect() const { return aspect_; }
  SDL_Window* window() const { return window_; }

  /** Fréquence de l'écran de la fenêtre (Hz), 0 si inconnue. */
  double refreshRate() const;
  /**
   * Rafraîchissements de l'écran par image du jeu pour la synchro verticale (1 : 60 Hz pour un jeu
   * à 60 images/s, 2 : 120 Hz…), 0 si l'écran ne suit pas le rythme du jeu (jeu PAL à 50 Hz sur
   * un écran à 60 Hz…).
   */
  int syncInterval(double fps) const;
  /** Synchro verticale : échange des tampons tous les [interval] rafraîchissements (0 : sans). */
  void setSwapInterval(int interval);
  int swapInterval() const { return swapInterval_; }

 private:
  bool createContext(std::string& error);
  bool loadFunctions();
  bool createProgram();
  /** Dessin de [present], état OpenGL du cœur gardé autour. */
  void draw(const uint8_t* overlay, int overlayWidth, int overlayHeight, bool overlayChanged);
  void allocateHwFramebuffer();
  bool preparePostTarget(int width, int height);
  /**
   * DLSS : image du jeu ([source], coordonnées u1, vTop, vBottom) recopiée dans l'image d'entrée,
   * agrandie (jusqu'à 3x), puis dessinée dans le rectangle de l'image (demi-taille [x1], [y1] en
   * coordonnées normalisées, [w] x [h] pixels), netteté RCAS réglable ; faux si impossible (FSR).
   */
  bool drawDlss(unsigned source, float u1, float vTop, float vBottom, float x1, float y1, float w, float h, int winW, int winH);
  /**
   * xBR, version choisie au-delà du niveau 1 (préréglage de libretro, shaderchain.h) : image du jeu
   * recopiée à sa taille exacte, passes du préréglage, puis image finale dessinée dans le rectangle de
   * l'image (bicubique si elle n'est pas déjà à la taille de l'écran) ; faux si impossible (niveau 1).
   */
  bool drawChain(unsigned source, float u1, float vTop, float vBottom, float x1, float y1, float w, float h, int winW, int winH);
  // Cœurs Vulkan : image partagée avec OpenGL (sans recopie), sinon recopiée (vulkan::readFrame).
  void setupInterop();
  bool showShared(unsigned width, unsigned height);
  void releaseShared();
  void dropShared();
  /**
   * Passe préalable à la taille du jeu (lissage du tramage, rémanence des écrans LCD, image exacte
   * pour CUT) : image du cœur [source] (coordonnées u1, vTop, vBottom dans une texture de texW x texH)
   * recopiée dans prepTexture_[prepOut_], refaite seulement pour une nouvelle image ou des réglages
   * changés ; faux si l'image intermédiaire n'a pas pu être créée.
   */
  bool runPrep(unsigned source, float u1, float vTop, float vBottom, float texW, float texH);
  /** Programmes des passes de CUT (shaders de LibretroDroid adaptés à OpenGL), recréés si les réglages changent. */
  bool prepareCut();
  /** Passes de CUT : intermédiaires à la taille du jeu, la dernière dans le rectangle [quad] de l'écran. */
  void drawCut(const void* quad, float screenDensity);
  void deleteCut();

  SDL_Window* window_ = nullptr;
  SDL_GLContext context_ = nullptr;
  bool coreProfile_ = false;
  bool fullscreen_ = true;
  int swapInterval_ = 0;
  bool vsyncFailed_ = false;  // pilote sans synchro verticale : rythme donné par le son
  Filter filter_ = Filter::Sharp;
  Aspect aspect_ = Aspect::Core;

  unsigned program_ = 0, vao_ = 0, vbo_ = 0;
  int uniformTexture_ = -1, uniformAlpha_ = -1, uniformMode_ = -1, uniformSize_ = -1, uniformScale_ = -1;
  int uniformParams_ = -1, uniformParams2_ = -1, uniformColor_ = -1, uniformRect_ = -1;
  float params_[kFilterSlots] = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f};
  // Passe préalable : programme, deux images à la taille du jeu (l'une garde l'image précédente pour
  // la rémanence), image de sortie, image du cœur déjà traitée ([frameSerial_] : images reçues).
  unsigned prepProgram_ = 0;
  int prepUniformSrc_ = -1, prepUniformHist_ = -1, prepUniformRect_ = -1, prepUniformSize_ = -1, prepUniformParams_ = -1;
  unsigned prepFbo_[2] = {0, 0}, prepTexture_[2] = {0, 0};
  int prepWidth_ = 0, prepHeight_ = 0, prepOut_ = 0;
  bool prepOk_ = false, prepDirty_ = true;
  int prepFrames_ = 0;  // images traitées depuis la création des images (rémanence)
  unsigned long long frameSerial_ = 0, prepSerial_ = 0;
  // CUT : programmes des passes (uniformes de LibretroDroid), images intermédiaires, réglages compilés.
  struct CutPass {
    unsigned program = 0;
    int texture = -1, previous = -1, size = -1, density = -1;
  };
  std::vector<CutPass> cutPasses_;
  std::vector<unsigned> cutFbo_, cutTexture_;
  int cutWidth_ = 0, cutHeight_ = 0;
  std::string cutKey_;
  unsigned frameTexture_ = 0, overlayTexture_ = 0;
  int overlayTextureWidth_ = 0, overlayTextureHeight_ = 0;  // taille de la surimpression envoyée
  // Image partagée avec Vulkan : texture sur sa mémoire, sémaphores « prête » et « rendue » ;
  // [releaseOwed_] : image tenue par OpenGL, à rendre à Vulkan avant la copie suivante.
  bool interopTried_ = false, interopOk_ = false, sharedActive_ = false, releaseOwed_ = false, sharedBgra_ = false;
  unsigned sharedTexture_ = 0, sharedMemory_ = 0, readySemaphore_ = 0, releasedSemaphore_ = 0;
  int sharedWidth_ = 0, sharedHeight_ = 0;
  int textureWidth_ = 0, textureHeight_ = 0;
  int textureFormat_ = 0;  // format interne de frameTexture_
  bool rgb565_ = false;    // format interne 5-6-5 disponible
  unsigned fbo_ = 0, fboTexture_ = 0, fboDepth_ = 0;
  // Image intermédiaire de FSR (agrandissement, puis netteté à l'écran).
  unsigned postFbo_ = 0, postTexture_ = 0;
  int postWidth_ = 0, postHeight_ = 0;
  bool postOk_ = false;
  // DLSS : Direct3D 11 et NGX, images partagées ; framebuffer de l'image d'entrée.
  Dlss dlss_;
  unsigned dlssFbo_ = 0;
  // Préréglage de xBR : passes, image d'entrée à la taille du jeu, version de GLSL du contexte.
  ShaderChain chain_;
  unsigned chainFbo_ = 0, chainTexture_ = 0;
  int chainWidth_ = 0, chainHeight_ = 0;
  int glslVersion_ = 0;
  int fboWidth_ = 0, fboHeight_ = 0;

  // Dernière image reçue.
  bool hasFrame_ = false;
  bool frameIsHw_ = false;
  unsigned frameWidth_ = 0, frameHeight_ = 0;
};
