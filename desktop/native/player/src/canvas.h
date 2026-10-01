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

  int width_, height_;
  std::vector<uint8_t> pixels_;
};

constexpr uint32_t rgba(uint8_t r, uint8_t g, uint8_t b, uint8_t a = 255) {
  return (uint32_t)r | ((uint32_t)g << 8) | ((uint32_t)b << 16) | ((uint32_t)a << 24);
}
