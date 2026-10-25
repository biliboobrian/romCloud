// Préréglages de shaders de libretro (.glslp, GLSL de RetroArch) intégrés au moteur (shaders/, données
// générées par CMake) : passes enchaînées dans des images intermédiaires à l'échelle de l'image source
// ou de l'écran, avec les conventions de RetroArch (VertexCoord, TexCoord, MVPMatrix, Texture,
// TextureSize, InputSize, OutputSize, OrigTexture, PassPrevNTexture, #pragma parameter et leurs valeurs
// du préréglage). Sert aux déclinaisons de xBR (shaders/xbr). Contexte OpenGL courant.
#pragma once

#include <cstddef>
#include <string>
#include <vector>

/** Fichier intégré (chemin relatif à shaders/, contenu). */
struct EmbeddedFile {
  const char* path;
  const unsigned char* data;
  size_t size;
};
extern const EmbeddedFile kEmbeddedShaders[];
extern const size_t kEmbeddedShaderCount;

class ShaderChain {
 public:
  ~ShaderChain() = default;
  /** Préréglages de xBR proposés (noms affichés), dans l'ordre du menu. */
  static const std::vector<std::string>& xbrPresets();

  /**
   * Charge le préréglage xBR [index] (rang dans xbrPresets) : sources et programmes ; [glslVersion] :
   * version de GLSL du contexte (150 : profil core, 130 ou 120 : compatibilité). Rien à refaire si
   * c'est déjà celui chargé ; faux si un programme ne compile pas.
   */
  bool load(int index, int glslVersion);
  int loaded() const { return loaded_; }

  /**
   * Passes sur [source] ([inW] x [inH], texture de la taille exacte de l'image) ; [viewW] x [viewH] :
   * taille de l'image à l'écran (passes à l'échelle de l'écran). Renvoie la texture finale (0 en cas
   * d'erreur), sa taille, et si elle est déjà à la taille de l'écran. Framebuffer, programme et
   * viewport changés ; tampon des sommets lié (GL_ARRAY_BUFFER) utilisé, mêmes attributs 0 et 1.
   */
  unsigned run(unsigned source, int inW, int inH, int viewW, int viewH, unsigned frameCount, int& outW, int& outH, bool& atViewport);
  void destroy();

 private:
  struct Pass {
    unsigned program = 0;
    bool viewport = false;  // à la taille de l'écran (sinon de l'image source)
    float scaleX = 1.0f, scaleY = 1.0f;
    bool linear = false;  // entrée lue avec filtrage linéaire
    std::vector<std::pair<int, float>> params;  // uniformes des #pragma parameter, valeur
    int mvp = -1, frameCount = -1, frameDirection = -1, outputSize = -1, textureSize = -1, inputSize = -1, texture = -1;
    int origTexture = -1, origTextureSize = -1, origInputSize = -1;
    int prevTexture[8], prevTextureSize[8], prevInputSize[8];  // PassPrev2 à PassPrev9
    unsigned fbo = 0, target = 0;
    int width = 0, height = 0;
  };
  bool compilePass(Pass& pass, const std::string& source, const std::vector<std::pair<std::string, float>>& overrides, int glslVersion);

  std::vector<Pass> passes_;
  int loaded_ = -1;
};
