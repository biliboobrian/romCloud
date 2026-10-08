#include "canvas.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "util.h"

// Polices du domaine public (Daniel Hepper, d'après les polices VGA d'IBM) : une ligne par octet,
// bit de poids faible = pixel de gauche.
extern "C" {
extern char font8x8_basic[128][8];
extern char font8x8_ext_latin[96][8];
}

static const char* glyph(uint32_t cp) {
  if (cp < 128) return font8x8_basic[cp];
  if (cp >= 0xA0 && cp <= 0xFF) return font8x8_ext_latin[cp - 0xA0];
  switch (cp) {
    case 0x2019: return font8x8_basic[(int)'\''];  // apostrophe typographique
    case 0x2026: return font8x8_basic[(int)'.'];   // points de suspension (approché)
    case 0x2190: return font8x8_basic[(int)'<'];   // flèches
    case 0x2192: return font8x8_basic[(int)'>'];
    case 0x2014: case 0x2013: return font8x8_basic[(int)'-'];
    default: return font8x8_basic[(int)'?'];
  }
}

void Canvas::clear() { std::fill(pixels_.begin(), pixels_.end(), 0); }

void Canvas::blend(int x, int y, uint32_t c) {
  if (x < 0 || y < 0 || x >= width_ || y >= height_) return;
  uint8_t* p = &pixels_[((size_t)y * width_ + x) * 4];
  uint32_t a = c >> 24;
  if (a == 255 || p[3] == 0) {
    p[0] = c & 0xFF;
    p[1] = (c >> 8) & 0xFF;
    p[2] = (c >> 16) & 0xFF;
    p[3] = (uint8_t)a;
    return;
  }
  // Composition « source par-dessus » sur un fond déjà translucide.
  uint32_t outA = a + p[3] * (255 - a) / 255;
  for (int i = 0; i < 3; i++) {
    uint32_t src = (c >> (8 * i)) & 0xFF;
    p[i] = (uint8_t)((src * a + p[i] * p[3] * (255 - a) / 255) / (outA ? outA : 1));
  }
  p[3] = (uint8_t)outA;
}

void Canvas::fill(int x, int y, int w, int h, uint32_t c) {
  for (int j = std::max(0, y); j < std::min(height_, y + h); j++) {
    for (int i = std::max(0, x); i < std::min(width_, x + w); i++) blend(i, j, c);
  }
}

template <typename Coverage>
void Canvas::shape(float x, float y, float w, float h, float r, uint32_t c, Coverage coverage) {
  r = std::clamp(r, 0.0f, std::min(w, h) / 2);
  const float cx = x + w / 2, cy = y + h / 2, hx = w / 2 - r, hy = h / 2 - r;
  const uint32_t alpha = c >> 24;
  for (int j = std::max(0, (int)std::floor(y) - 1); j < std::min(height_, (int)std::ceil(y + h) + 1); j++) {
    for (int i = std::max(0, (int)std::floor(x) - 1); i < std::min(width_, (int)std::ceil(x + w) + 1); i++) {
      // Distance signée au bord (négative à l'intérieur).
      float qx = std::fabs(i + 0.5f - cx) - hx, qy = std::fabs(j + 0.5f - cy) - hy;
      float d = std::hypot(std::max(qx, 0.0f), std::max(qy, 0.0f)) + std::min(std::max(qx, qy), 0.0f) - r;
      float cover = std::clamp(coverage(d), 0.0f, 1.0f);
      if (cover <= 0) continue;
      blend(i, j, (c & 0xFFFFFF) | ((uint32_t)std::lround(alpha * cover) << 24));
    }
  }
}

void Canvas::roundRect(float x, float y, float w, float h, float r, uint32_t c) {
  shape(x, y, w, h, r, c, [](float d) { return 0.5f - d; });
}

void Canvas::roundRectOutline(float x, float y, float w, float h, float r, float t, uint32_t c) {
  shape(x, y, w, h, r, c, [t](float d) { return 0.5f - (std::fabs(d + t / 2) - t / 2); });
}

void Canvas::line(float x0, float y0, float x1, float y1, float t, uint32_t c) {
  const float r = t / 2, vx = x1 - x0, vy = y1 - y0, len2 = std::max(vx * vx + vy * vy, 1e-6f);
  const uint32_t alpha = c >> 24;
  for (int j = std::max(0, (int)std::floor(std::min(y0, y1) - r) - 1); j < std::min(height_, (int)std::ceil(std::max(y0, y1) + r) + 1); j++) {
    for (int i = std::max(0, (int)std::floor(std::min(x0, x1) - r) - 1); i < std::min(width_, (int)std::ceil(std::max(x0, x1) + r) + 1); i++) {
      // Distance au segment.
      float px = i + 0.5f - x0, py = j + 0.5f - y0;
      float k = std::clamp((px * vx + py * vy) / len2, 0.0f, 1.0f);
      float d = std::hypot(px - k * vx, py - k * vy) - r;
      float cover = std::clamp(0.5f - d, 0.0f, 1.0f);
      if (cover > 0) blend(i, j, (c & 0xFFFFFF) | ((uint32_t)std::lround(alpha * cover) << 24));
    }
  }
}

int Canvas::text(int x, int y, const std::string& s, int scale, uint32_t c) {
  int cx = x;
  for (uint32_t cp : utf8Decode(s)) {
    const char* rows = glyph(cp);
    for (int row = 0; row < 8; row++) {
      for (int col = 0; col < 8; col++) {
        if (rows[row] & (1 << col)) fill(cx + col * scale, y + row * scale, scale, scale, c);
      }
    }
    cx += 8 * scale;
  }
  return cx - x;
}

int Canvas::measure(const std::string& s, int scale) { return (int)utf8Decode(s).size() * 8 * scale; }

std::string Canvas::fit(const std::string& s, int scale, int maxWidth) {
  if (measure(s, scale) <= maxWidth) return s;
  std::vector<uint32_t> cps = utf8Decode(s);
  size_t keep = (size_t)std::max(0, maxWidth / (8 * scale) - 1);
  std::string out;
  for (size_t i = 0; i < std::min(keep, cps.size()); i++) {
    uint32_t cp = cps[i];
    if (cp < 0x80) out += (char)cp;
    else if (cp < 0x800) { out += (char)(0xC0 | (cp >> 6)); out += (char)(0x80 | (cp & 0x3F)); }
    else { out += (char)(0xE0 | (cp >> 12)); out += (char)(0x80 | ((cp >> 6) & 0x3F)); out += (char)(0x80 | (cp & 0x3F)); }
  }
  return out + ".";
}
