// NVIDIA DLSS (cartes GeForce RTX) : image du jeu agrandie par le réseau de neurones du DLSS, image
// par image. Les cœurs ne donnent ni vecteurs de mouvement ni profondeur : vecteurs nuls et historique
// oublié à chaque image (Reset), le DLSS sert d'agrandisseur d'une seule image. Direct3D 11 avec le
// cœur NGX du pilote NVIDIA (_nvngx.dll, chargé à l'exécution : pas de bibliothèque du SDK), images
// partagées avec OpenGL (WGL_NV_DX_interop2). nvngx_dlss.dll (SDK DLSS de NVIDIA) téléchargé au
// premier usage dans le dossier donné.
#pragma once

#include <atomic>
#include <string>
#include <thread>

struct ID3D11Device;
struct ID3D11DeviceContext;
struct ID3D11Texture2D;
struct NgxParameters;
struct NgxHandle;

class Dlss {
 public:
  enum class State { Off, Downloading, Ready, Failed };

  ~Dlss();
  /** Dossier de nvngx_dlss.dll (téléchargé s'il manque) et des journaux de NGX. */
  void setDirectory(const std::string& dir) { dir_ = dir; }
  /**
   * Prépare le DLSS au premier appel (carte, Direct3D 11, NGX, partage avec OpenGL ; téléchargement en
   * arrière-plan), puis renvoie l'état. Contexte OpenGL courant.
   */
  State prepare();
  /** Message à montrer (clé de menu.cpp : téléchargement, prêt, raison de l'échec), une fois ; vide sinon. */
  std::string takeMessage();

  /** Images partagées pour agrandir [inW] x [inH] en [outW] x [outH], recréées si la taille change. */
  bool resize(int inW, int inH, int outW, int outH);
  /** Textures OpenGL de l'image d'entrée (à dessiner) et de sortie (agrandie), utilisables verrouillées. */
  unsigned inputTexture() const { return glInput_; }
  unsigned outputTexture() const { return glOutput_; }
  /** Image d'entrée ou de sortie prise par OpenGL (lock) ou rendue à Direct3D (unlock). */
  bool lock(bool input);
  void unlock(bool input);
  /** Agrandit l'image d'entrée dans celle de sortie (Direct3D, entre les passes OpenGL). */
  bool evaluate();
  /** Libère tout (contexte OpenGL courant). */
  void destroy();

 private:
  bool start();
  bool initNgx();
  void releaseImages();
  void fail(const char* message, const char* reason);

  std::string dir_;
  State state_ = State::Off;
  std::string message_;
  std::thread download_;
  std::atomic<int> downloaded_{0};  // 0 : en cours, 1 : réussi, -1 : échec

  ID3D11Device* device_ = nullptr;
  ID3D11DeviceContext* context_ = nullptr;
  void* core_ = nullptr;     // _nvngx.dll
  void* interop_ = nullptr;  // wglDXOpenDeviceNV
  bool ngxReady_ = false;
  NgxParameters* params_ = nullptr;
  NgxHandle* feature_ = nullptr;

  int inW_ = 0, inH_ = 0, outW_ = 0, outH_ = 0;
  int refused_[4] = {0, 0, 0, 0};  // dernière taille refusée (entrée, sortie)
  ID3D11Texture2D* input_ = nullptr;       // dessinée par OpenGL
  ID3D11Texture2D* output_ = nullptr;      // écrite par le DLSS (accès non ordonné)
  ID3D11Texture2D* shared_ = nullptr;      // copie de la sortie, lue par OpenGL
  ID3D11Texture2D* motion_ = nullptr;      // vecteurs de mouvement nuls
  ID3D11Texture2D* depth_ = nullptr;       // profondeur nulle
  unsigned glInput_ = 0, glOutput_ = 0;
  void* inputObject_ = nullptr;   // wglDXRegisterObjectNV
  void* outputObject_ = nullptr;
};
