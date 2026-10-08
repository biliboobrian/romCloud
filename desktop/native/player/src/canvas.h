// Image RGBA dessinée sur le processeur (menu, messages), affichée par-dessus le jeu.
// Texte avec la police bitmap 8x8 du domaine public (ASCII et Latin-1 : accents français).
#pragma once

#include <cstdint>
#include <string>
#include <vector>

class Canvas {
 public:
  Canvas(int width, int height) : width_(width), height_(height), pixels_((size_t)width * height * 4, 0) {}

  /** Change la taille (proportions de la fenêtre) ; contenu effacé. */
  void resize(int width, int height) {
    width_ = width;
    height_ = height;
    pixels_.assign((size_t)width * height * 4, 0);
  }
  void clear();
  void fill(int x, int y, int w, int h, uint32_t rgba);
  /** Rectangle aux coins arrondis (rayon [r] ; moitié du côté : disque), bords lissés. */
  void roundRect(float x, float y, float w, float h, float r, uint32_t rgba);
  /** Contour intérieur d'épaisseur [t] du même rectangle. */
  void roundRectOutline(float x, float y, float w, float h, float r, float t, uint32_t rgba);
  /** Trait d'épaisseur [t] (bouts arrondis), bords lissés. */
  void line(float x0, float y0, float x1, float y1, float t, uint32_t rgba);
  void circle(float cx, float cy, float r, uint32_t rgba) { roundRect(cx - r, cy - r, 2 * r, 2 * r, r, rgba); }
  void circleOutline(float cx, float cy, float r, float t, uint32_t rgba) { roundRectOutline(cx - r, cy - r, 2 * r, 2 * r, r, t, rgba); }
  /** Dessine le texte (UTF-8) ; renvoie la largeur en pixels. */
  int text(int x, int y, const std::string& s, int scale, uint32_t rgba);
  static int measure(const std::string& s, int scale);
  /** Texte coupé avec « … » pour tenir dans [maxWidth]. */
  static std::string fit(const std::string& s, int scale, int maxWidth);

  int width() const { return width_; }
  int height() const { return height_; }
  const uint8_t* data() const { return pixels_.data(); }

 private:
  void blend(int x, int y, uint32_t rgba);
  /** Forme d'un rectangle arrondi : couverture de chaque pixel d'après sa distance au bord. */
  template <typename Coverage>
  void shape(float x, float y, float w, float h, float r, uint32_t rgba, Coverage coverage);

  int width_, height_;
  std::vector<uint8_t> pixels_;
};

constexpr uint32_t rgba(uint8_t r, uint8_t g, uint8_t b, uint8_t a = 255) {
  return (uint32_t)r | ((uint32_t)g << 8) | ((uint32_t)b << 16) | ((uint32_t)a << 24);
}
