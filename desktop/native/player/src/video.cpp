#include "video.h"

#include <SDL_opengl.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#include "core.h"
#include "util.h"

// ---------------------------------------------------------------------------
// Fonctions OpenGL (chargées à l'exécution : opengl32.dll n'exporte que l'OpenGL 1.1)
// ---------------------------------------------------------------------------

#define GL_FUNCTIONS(X)                                                         \
  X(PFNGLACTIVETEXTUREPROC, glActiveTexture)                                    \
  X(PFNGLGENBUFFERSPROC, glGenBuffers)                                          \
  X(PFNGLBINDBUFFERPROC, glBindBuffer)                                          \
  X(PFNGLBUFFERDATAPROC, glBufferData)                                          \
  X(PFNGLDELETEBUFFERSPROC, glDeleteBuffers)                                    \
  X(PFNGLCREATESHADERPROC, glCreateShader)                                      \
  X(PFNGLSHADERSOURCEPROC, glShaderSource)                                      \
  X(PFNGLCOMPILESHADERPROC, glCompileShader)                                    \
  X(PFNGLGETSHADERIVPROC, glGetShaderiv)                                        \
  X(PFNGLGETSHADERINFOLOGPROC, glGetShaderInfoLog)                              \
  X(PFNGLDELETESHADERPROC, glDeleteShader)                                      \
  X(PFNGLCREATEPROGRAMPROC, glCreateProgram)                                    \
  X(PFNGLATTACHSHADERPROC, glAttachShader)                                      \
  X(PFNGLBINDATTRIBLOCATIONPROC, glBindAttribLocation)                          \
  X(PFNGLLINKPROGRAMPROC, glLinkProgram)                                        \
  X(PFNGLGETPROGRAMIVPROC, glGetProgramiv)                                      \
  X(PFNGLGETPROGRAMINFOLOGPROC, glGetProgramInfoLog)                            \
  X(PFNGLDELETEPROGRAMPROC, glDeleteProgram)                                    \
  X(PFNGLUSEPROGRAMPROC, glUseProgram)                                          \
  X(PFNGLGETUNIFORMLOCATIONPROC, glGetUniformLocation)                          \
  X(PFNGLUNIFORM1IPROC, glUniform1i)                                            \
  X(PFNGLUNIFORM1FPROC, glUniform1f)                                            \
  X(PFNGLUNIFORM2FPROC, glUniform2f)                                            \
  X(PFNGLVERTEXATTRIBPOINTERPROC, glVertexAttribPointer)                        \
  X(PFNGLENABLEVERTEXATTRIBARRAYPROC, glEnableVertexAttribArray)                \
  X(PFNGLGENFRAMEBUFFERSPROC, glGenFramebuffers)                                \
  X(PFNGLBINDFRAMEBUFFERPROC, glBindFramebuffer)                                \
  X(PFNGLFRAMEBUFFERTEXTURE2DPROC, glFramebufferTexture2D)                      \
  X(PFNGLCHECKFRAMEBUFFERSTATUSPROC, glCheckFramebufferStatus)                  \
  X(PFNGLDELETEFRAMEBUFFERSPROC, glDeleteFramebuffers)                          \
  X(PFNGLGENRENDERBUFFERSPROC, glGenRenderbuffers)                              \
  X(PFNGLBINDRENDERBUFFERPROC, glBindRenderbuffer)                              \
  X(PFNGLRENDERBUFFERSTORAGEPROC, glRenderbufferStorage)                        \
  X(PFNGLFRAMEBUFFERRENDERBUFFERPROC, glFramebufferRenderbuffer)                \
  X(PFNGLDELETERENDERBUFFERSPROC, glDeleteRenderbuffers)

// Facultatives (absentes des contextes OpenGL 2.1).
#define GL_OPTIONAL_FUNCTIONS(X)                                                \
  X(PFNGLGENVERTEXARRAYSPROC, glGenVertexArrays)                                \
  X(PFNGLBINDVERTEXARRAYPROC, glBindVertexArray)                                \
  X(PFNGLBINDSAMPLERPROC, glBindSampler)

#define DECLARE(type, name) static type p_##name = nullptr;
GL_FUNCTIONS(DECLARE)
GL_OPTIONAL_FUNCTIONS(DECLARE)
#undef DECLARE

#ifndef GL_PIXEL_UNPACK_BUFFER
#define GL_PIXEL_UNPACK_BUFFER 0x88EC
#endif
#ifndef GL_PIXEL_PACK_BUFFER
#define GL_PIXEL_PACK_BUFFER 0x88EB
#endif
#ifndef GL_FRAMEBUFFER_SRGB
#define GL_FRAMEBUFFER_SRGB 0x8DB9
#endif

retro_proc_address_t Video::procAddress(const char* sym) {
  return reinterpret_cast<retro_proc_address_t>(SDL_GL_GetProcAddress(sym));
}

bool Video::loadFunctions() {
  bool ok = true;
#define LOAD(type, name)                                                  \
  p_##name = reinterpret_cast<type>(SDL_GL_GetProcAddress(#name));        \
  if (!p_##name) {                                                        \
    logf("Fonction OpenGL absente : %s", #name);                          \
    ok = false;                                                           \
  }
  GL_FUNCTIONS(LOAD)
#undef LOAD
#define LOAD_OPTIONAL(type, name) p_##name = reinterpret_cast<type>(SDL_GL_GetProcAddress(#name));
  GL_OPTIONAL_FUNCTIONS(LOAD_OPTIONAL)
#undef LOAD_OPTIONAL
  return ok;
}

// ---------------------------------------------------------------------------
// Fenêtre et contexte
// ---------------------------------------------------------------------------

static bool tryContext(SDL_Window* window, int major, int minor, int profile, SDL_GLContext& out) {
  SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, major);
  SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, minor);
  SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, profile);
  out = SDL_GL_CreateContext(window);
  if (!out) logf("Contexte OpenGL %d.%d (%s) refusé : %s", major, minor,
                 profile == SDL_GL_CONTEXT_PROFILE_CORE ? "core" : "compat", SDL_GetError());
  return out != nullptr;
}

bool Video::createContext(std::string& error) {
  SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);
  SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 24);
  SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 8);
  const int core = SDL_GL_CONTEXT_PROFILE_CORE;
  const int compat = SDL_GL_CONTEXT_PROFILE_COMPATIBILITY;
  bool ok;
  if (g.hwRender && g.hw.context_type == RETRO_HW_CONTEXT_OPENGL_CORE) {
    int major = g.hw.version_major ? (int)g.hw.version_major : 3;
    int minor = g.hw.version_major ? (int)g.hw.version_minor : 2;
    ok = tryContext(window_, major, minor, core, context_);
    coreProfile_ = true;
  } else if (g.hwRender) {
    // Profil de compatibilité : les cœurs « OpenGL » peuvent utiliser les fonctions anciennes.
    int major = g.hw.version_major ? (int)g.hw.version_major : 2;
    int minor = g.hw.version_major ? (int)g.hw.version_minor : 1;
    ok = tryContext(window_, major, minor, compat, context_) || tryContext(window_, 2, 1, compat, context_);
    coreProfile_ = false;
  } else {
    ok = tryContext(window_, 3, 3, core, context_);
    coreProfile_ = ok;
    if (!ok) ok = tryContext(window_, 2, 1, compat, context_);
  }
  if (!ok) {
    error = SDL_GetError();
    return false;
  }
  SDL_GL_MakeCurrent(window_, context_);
  SDL_GL_SetSwapInterval(0);  // rythme donné par l'audio
  logf("OpenGL : %s — %s", (const char*)glGetString(GL_VERSION), (const char*)glGetString(GL_RENDERER));
  return true;
}

bool Video::create(const std::string& title, bool fullscreen, bool hidden, std::string& error) {
  fullscreen_ = fullscreen;
  const retro_game_geometry& geo = g.av.geometry;
  int scale = 3;
  int w = std::max(640, (int)geo.base_width * scale), h = std::max(480, (int)geo.base_height * scale);
  Uint32 flags = SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE | SDL_WINDOW_ALLOW_HIGHDPI;
  if (fullscreen) flags |= SDL_WINDOW_FULLSCREEN_DESKTOP;
  if (hidden) flags |= SDL_WINDOW_HIDDEN;
  window_ = SDL_CreateWindow(title.c_str(), SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED, w, h, flags);
  if (!window_) {
    error = SDL_GetError();
    return false;
  }
  if (!createContext(error)) return false;
  if (!loadFunctions()) {
    error = "OpenGL 2.1 minimum";
    return false;
  }
  if (!createProgram()) {
    error = "shaders";
    return false;
  }
  glGenTextures(1, &frameTexture_);
  glGenTextures(1, &overlayTexture_);
  if (g.hwRender) {
    allocateHwFramebuffer();
    if (g.hw.context_reset) g.hw.context_reset();
  }
  SDL_ShowCursor(fullscreen ? SDL_DISABLE : SDL_ENABLE);
  return true;
}

void Video::destroyCoreContext() {
  if (context_ && g.hwRender && g.hw.context_destroy) {
    SDL_GL_MakeCurrent(window_, context_);
    g.hw.context_destroy();
  }
  g.hw.context_destroy = nullptr;
}

void Video::destroy() {
  if (context_) SDL_GL_DeleteContext(context_);
  if (window_) SDL_DestroyWindow(window_);
  context_ = nullptr;
  window_ = nullptr;
}

bool Video::saveFrame(const std::string& path) {
  if (!hasFrame_ || !frameWidth_ || !frameHeight_) return false;
  int w = (int)frameWidth_, h = (int)frameHeight_;
  std::vector<uint8_t> pixels((size_t)w * h * 4);
  p_glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
  glPixelStorei(GL_PACK_ALIGNMENT, 4);
  bool flip;
  if (frameIsHw_) {
    p_glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
    p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
    flip = g.hw.bottom_left_origin;
  } else {
    std::vector<uint8_t> full((size_t)textureWidth_ * textureHeight_ * 4);
    glBindTexture(GL_TEXTURE_2D, frameTexture_);
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, full.data());
    for (int y = 0; y < h; y++) memcpy(&pixels[(size_t)y * w * 4], &full[(size_t)y * textureWidth_ * 4], (size_t)w * 4);
    flip = false;
  }
  if (flip) {
    for (int y = 0; y < h / 2; y++) std::swap_ranges(&pixels[(size_t)y * w * 4], &pixels[(size_t)(y + 1) * w * 4], &pixels[(size_t)(h - 1 - y) * w * 4]);
  }
  SDL_Surface* surface = SDL_CreateRGBSurfaceWithFormatFrom(pixels.data(), w, h, 32, w * 4, SDL_PIXELFORMAT_RGBA32);
  if (!surface) return false;
  bool ok = SDL_SaveBMP(surface, path.c_str()) == 0;
  SDL_FreeSurface(surface);
  return ok;
}

void Video::toggleFullscreen() {
  fullscreen_ = !fullscreen_;
  SDL_SetWindowFullscreen(window_, fullscreen_ ? SDL_WINDOW_FULLSCREEN_DESKTOP : 0);
  SDL_ShowCursor(fullscreen_ ? SDL_DISABLE : SDL_ENABLE);
}

// ---------------------------------------------------------------------------
// Framebuffer du rendu matériel
// ---------------------------------------------------------------------------

void Video::allocateHwFramebuffer() {
  int w = (int)std::max(1u, g.av.geometry.max_width), h = (int)std::max(1u, g.av.geometry.max_height);
  if (fbo_ && w <= fboWidth_ && h <= fboHeight_) return;
  fboWidth_ = std::max(w, fboWidth_);
  fboHeight_ = std::max(h, fboHeight_);
  if (!fbo_) {
    p_glGenFramebuffers(1, &fbo_);
    glGenTextures(1, &fboTexture_);
  }
  glBindTexture(GL_TEXTURE_2D, fboTexture_);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, fboWidth_, fboHeight_, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  p_glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
  p_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTexture_, 0);
  if (g.hw.depth) {
    if (!fboDepth_) p_glGenRenderbuffers(1, &fboDepth_);
    p_glBindRenderbuffer(GL_RENDERBUFFER, fboDepth_);
    p_glRenderbufferStorage(GL_RENDERBUFFER, g.hw.stencil ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT24, fboWidth_, fboHeight_);
    p_glFramebufferRenderbuffer(GL_FRAMEBUFFER, g.hw.stencil ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT,
                                GL_RENDERBUFFER, fboDepth_);
  }
  GLenum status = p_glCheckFramebufferStatus(GL_FRAMEBUFFER);
  if (status != GL_FRAMEBUFFER_COMPLETE) logf("Framebuffer incomplet : 0x%x", status);
  glClearColor(0, 0, 0, 1);
  glClear(GL_COLOR_BUFFER_BIT);
  p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
  logf("Framebuffer du cœur : %dx%d", fboWidth_, fboHeight_);
}

/** Image intermédiaire à la taille de l'affichage (FSR) ; faux si elle n'a pas pu être créée. */
bool Video::preparePostTarget(int width, int height) {
  if (width <= 0 || height <= 0) return false;
  if (!postFbo_) {
    p_glGenFramebuffers(1, &postFbo_);
    glGenTextures(1, &postTexture_);
  }
  GLint previous = 0;
  glGetIntegerv(GL_TEXTURE_BINDING_2D, &previous);
  if (width != postWidth_ || height != postHeight_) {
    postWidth_ = width;
    postHeight_ = height;
    glBindTexture(GL_TEXTURE_2D, postTexture_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    p_glBindFramebuffer(GL_FRAMEBUFFER, postFbo_);
    p_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, postTexture_, 0);
    postOk_ = p_glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (!postOk_) logf("Image intermédiaire FSR incomplète (%dx%d)", width, height);
    p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, (GLuint)previous);
  }
  return postOk_;
}

void Video::onGeometryChanged() {
  if (context_ && g.hwRender) allocateHwFramebuffer();
}

// ---------------------------------------------------------------------------
// Shaders
// ---------------------------------------------------------------------------

static const char* kVertex150 =
    "#version 150\n"
    "in vec2 a_pos; in vec2 a_uv; out vec2 v_uv;\n"
    "void main() { v_uv = a_uv; gl_Position = vec4(a_pos, 0.0, 1.0); }\n";
// Filtre d'image (u_mode : rang dans Filter) ; u_size : taille de la texture en pixels, u_scale :
// pixels de l'écran par pixel de l'image. Lissage net (et base de l'écran cathodique) d'après
// « sharp-bilinear-simple » de libretro : chaque pixel reste net, seule sa bordure est lissée.
// Modes (rang dans Filter) : 0 pixels, 1 lissage net, 2 doux (bicubique Catmull-Rom), 3 bilinéaire,
// 4 EPX / Scale2x, 5 cathodique, 6 cathodique + masque RGB (grille d'ouverture), 7 LCD, 8 xBR
// (niveau 1 : angles des contours redessinés en diagonale), 9 FSR 1 EASU (agrandissement suivant la
// direction des contours, d'après AMD FidelityFX Super Resolution 1), 10 FSR 1 RCAS (netteté
// adaptée au contraste, seconde passe sur l'image agrandie).
#define FILTER_EXTRA \
  "float lum(vec3 c) { return c.g + 0.5 * (c.r + c.b); }\n" \
  "vec3 at(sampler2D tex, vec2 p) { return TEX(tex, (p + 0.5) / u_size).rgb; }\n" \
  "float diff(vec3 a, vec3 b) { return dot(abs(a - b), vec3(0.299, 0.587, 0.114)); }\n" \
  "vec4 xbr(sampler2D tex, vec2 uv) {\n" \
  "  vec2 texel = uv * u_size;\n" \
  "  vec2 p = floor(texel);\n" \
  "  vec2 fp = fract(texel) - 0.5;\n" \
  "  vec2 s = vec2(fp.x >= 0.0 ? 1.0 : -1.0, fp.y >= 0.0 ? 1.0 : -1.0);\n" \
  "  vec3 E = at(tex, p), F = at(tex, p + vec2(s.x, 0.0)), H = at(tex, p + vec2(0.0, s.y)), I = at(tex, p + s);\n" \
  "  vec3 B = at(tex, p - vec2(0.0, s.y)), D = at(tex, p - vec2(s.x, 0.0));\n" \
  "  vec3 C = at(tex, p + vec2(s.x, -s.y)), G = at(tex, p + vec2(-s.x, s.y));\n" \
  "  vec3 F4 = at(tex, p + vec2(2.0 * s.x, 0.0)), H5 = at(tex, p + vec2(0.0, 2.0 * s.y));\n" \
  "  vec3 I4 = at(tex, p + vec2(2.0 * s.x, s.y)), I5 = at(tex, p + vec2(s.x, 2.0 * s.y));\n" \
  "  float d1 = diff(E, C) + diff(E, G) + diff(I, F4) + diff(I, H5) + 4.0 * diff(H, F);\n" \
  "  float d2 = diff(H, D) + diff(H, I5) + diff(F, B) + diff(F, I4) + 4.0 * diff(E, I);\n" \
  "  if (d1 >= d2 || diff(E, F) < 0.004 || diff(E, H) < 0.004) return vec4(E, 1.0);\n" \
  "  vec3 edge = diff(E, F) <= diff(E, H) ? F : H;\n" \
  "  float aa = 1.0 / max(min(u_scale.x, u_scale.y), 1.0);\n" \
  "  float t = dot(fp, s);\n" \
  "  return vec4(mix(E, edge, smoothstep(0.5 - aa, 0.5 + aa, t)), 1.0);\n" \
  "}\n" \
  "void easuSet(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {\n" \
  "  float lenX = max(abs(lD - lC), abs(lC - lB));\n" \
  "  lenX = lenX > 0.0 ? 1.0 / lenX : 0.0;\n" \
  "  float dirX = lD - lB;\n" \
  "  dir.x += dirX * w;\n" \
  "  lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);\n" \
  "  len += lenX * lenX * w;\n" \
  "  float lenY = max(abs(lE - lC), abs(lC - lA));\n" \
  "  lenY = lenY > 0.0 ? 1.0 / lenY : 0.0;\n" \
  "  float dirY = lE - lA;\n" \
  "  dir.y += dirY * w;\n" \
  "  lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);\n" \
  "  len += lenY * lenY * w;\n" \
  "}\n" \
  "void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len2, float lob, float clp, vec3 c) {\n" \
  "  vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * (-dir.y) + off.y * dir.x) * len2;\n" \
  "  float d2 = min(dot(v, v), clp);\n" \
  "  float wB = 0.4 * d2 - 1.0;\n" \
  "  float wA = lob * d2 - 1.0;\n" \
  "  wB *= wB;\n" \
  "  wA *= wA;\n" \
  "  wB = 1.5625 * wB - 0.5625;\n" \
  "  float w = wB * wA;\n" \
  "  aC += c * w;\n" \
  "  aW += w;\n" \
  "}\n" \
  "vec4 easu(sampler2D tex, vec2 uv) {\n" \
  "  vec2 pp = uv * u_size - 0.5;\n" \
  "  vec2 fp = floor(pp);\n" \
  "  pp -= fp;\n" \
  "  vec3 b = at(tex, fp + vec2(0.0, -1.0)), c = at(tex, fp + vec2(1.0, -1.0));\n" \
  "  vec3 e = at(tex, fp + vec2(-1.0, 0.0)), f = at(tex, fp), g = at(tex, fp + vec2(1.0, 0.0)), h = at(tex, fp + vec2(2.0, 0.0));\n" \
  "  vec3 i = at(tex, fp + vec2(-1.0, 1.0)), j = at(tex, fp + vec2(0.0, 1.0)), k = at(tex, fp + vec2(1.0, 1.0)), l = at(tex, fp + vec2(2.0, 1.0));\n" \
  "  vec3 n = at(tex, fp + vec2(0.0, 2.0)), o = at(tex, fp + vec2(1.0, 2.0));\n" \
  "  vec2 dir = vec2(0.0);\n" \
  "  float len = 0.0;\n" \
  "  easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), lum(b), lum(e), lum(f), lum(g), lum(j));\n" \
  "  easuSet(dir, len, pp.x * (1.0 - pp.y), lum(c), lum(f), lum(g), lum(h), lum(k));\n" \
  "  easuSet(dir, len, (1.0 - pp.x) * pp.y, lum(f), lum(i), lum(j), lum(k), lum(n));\n" \
  "  easuSet(dir, len, pp.x * pp.y, lum(g), lum(j), lum(k), lum(l), lum(o));\n" \
  "  float dirR = dot(dir, dir);\n" \
  "  if (dirR < 1.0 / 32768.0) dir = vec2(1.0, 0.0); else dir *= inversesqrt(dirR);\n" \
  "  len = len * 0.5;\n" \
  "  len *= len;\n" \
  "  float stretch = dot(dir, dir) / max(abs(dir.x), abs(dir.y));\n" \
  "  vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);\n" \
  "  float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;\n" \
  "  float clp = 1.0 / lob;\n" \
  "  vec3 aC = vec3(0.0);\n" \
  "  float aW = 0.0;\n" \
  "  easuTap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);\n" \
  "  easuTap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);\n" \
  "  easuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);\n" \
  "  easuTap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);\n" \
  "  easuTap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);\n" \
  "  easuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);\n" \
  "  easuTap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);\n" \
  "  easuTap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);\n" \
  "  easuTap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);\n" \
  "  easuTap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);\n" \
  "  easuTap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);\n" \
  "  easuTap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);\n" \
  "  vec3 mn = min(min(f, g), min(j, k));\n" \
  "  vec3 mx = max(max(f, g), max(j, k));\n" \
  "  return vec4(clamp(aC / aW, mn, mx), 1.0);\n" \
  "}\n" \
  "vec4 rcas(sampler2D tex, vec2 uv) {\n" \
  "  vec2 px = 1.0 / u_size;\n" \
  "  vec3 b = TEX(tex, uv - vec2(0.0, px.y)).rgb, d = TEX(tex, uv - vec2(px.x, 0.0)).rgb, e = TEX(tex, uv).rgb;\n" \
  "  vec3 f = TEX(tex, uv + vec2(px.x, 0.0)).rgb, h = TEX(tex, uv + vec2(0.0, px.y)).rgb;\n" \
  "  vec3 mn4 = min(min(b, d), min(f, h));\n" \
  "  vec3 mx4 = max(max(b, d), max(f, h));\n" \
  "  vec3 hitMin = min(mn4, e) / (4.0 * mx4 + 1e-5);\n" \
  "  vec3 hitMax = (1.0 - max(mx4, e)) / (4.0 * mn4 - 4.0 - 1e-5);\n" \
  "  vec3 lobeRGB = max(-hitMin, hitMax);\n" \
  "  float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * 0.87;\n" \
  "  return vec4((lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0), 1.0);\n" \
  "}\n"

#define FILTER_FUNCTION                                                                  \
  "uniform int u_mode; uniform vec2 u_size; uniform vec2 u_scale;\n"                     \
  FILTER_EXTRA                                                                           \
  "float catmullRom(float x) {\n"                                                        \
  "  x = abs(x);\n"                                                                      \
  "  if (x < 1.0) return (1.5 * x - 2.5) * x * x + 1.0;\n"                               \
  "  if (x < 2.0) return ((-0.5 * x + 2.5) * x - 4.0) * x + 2.0;\n"                      \
  "  return 0.0;\n"                                                                      \
  "}\n"                                                                                  \
  "bool same(vec4 a, vec4 b) { return distance(a.rgb, b.rgb) < 0.02; }\n"                \
  "vec4 filtered(sampler2D tex, vec2 uv) {\n"                                            \
  "  if (u_mode == 0 || u_mode == 3) return TEX(tex, uv);\n"                             \
  "  if (u_mode == 8) return xbr(tex, uv);\n"                                            \
  "  if (u_mode == 9) return easu(tex, uv);\n"                                           \
  "  if (u_mode == 10) return rcas(tex, uv);\n"                                          \
  "  vec2 texel = uv * u_size;\n"                                                        \
  "  if (u_mode == 2) {\n"                                                               \
  "    vec2 base = floor(texel - 0.5) + 0.5;\n"                                          \
  "    vec2 f = texel - base;\n"                                                         \
  "    vec4 sum = vec4(0.0);\n"                                                          \
  "    for (int j = -1; j <= 2; j++) for (int i = -1; i <= 2; i++) {\n"                  \
  "      float w = catmullRom(f.x - float(i)) * catmullRom(f.y - float(j));\n"           \
  "      sum += TEX(tex, (base + vec2(float(i), float(j))) / u_size) * w;\n"             \
  "    }\n"                                                                              \
  "    return clamp(sum, 0.0, 1.0);\n"                                                   \
  "  }\n"                                                                                \
  "  if (u_mode == 4) {\n"                                                               \
  "    vec2 p = floor(texel) + 0.5;\n"                                                   \
  "    vec4 P = TEX(tex, p / u_size);\n"                                                 \
  "    vec4 A = TEX(tex, (p + vec2(0.0, -1.0)) / u_size);\n"                             \
  "    vec4 B = TEX(tex, (p + vec2(1.0, 0.0)) / u_size);\n"                              \
  "    vec4 C = TEX(tex, (p + vec2(-1.0, 0.0)) / u_size);\n"                             \
  "    vec4 D = TEX(tex, (p + vec2(0.0, 1.0)) / u_size);\n"                              \
  "    vec2 q = step(vec2(0.5), fract(texel));\n"                                        \
  "    if (q.x < 0.5 && q.y < 0.5) return (same(C, A) && !same(C, D) && !same(A, B)) ? A : P;\n" \
  "    if (q.y < 0.5) return (same(A, B) && !same(A, C) && !same(B, D)) ? B : P;\n"      \
  "    if (q.x < 0.5) return (same(D, C) && !same(D, B) && !same(C, A)) ? C : P;\n"      \
  "    return (same(B, D) && !same(B, A) && !same(D, C)) ? D : P;\n"                     \
  "  }\n"                                                                                \
  "  vec2 centerDist = fract(texel) - 0.5;\n"                                            \
  "  vec4 c = TEX(tex, uv);\n"                                                           \
  "  if (u_mode != 7) {\n"                                                               \
  "    vec2 range = max(vec2(0.0), 0.5 - 0.5 / u_scale);\n"                              \
  "    vec2 f = (centerDist - clamp(centerDist, -range, range)) * u_scale + 0.5;\n"      \
  "    c = TEX(tex, (floor(texel) + f) / u_size);\n"                                     \
  "  }\n"                                                                                \
  "  if ((u_mode == 5 || u_mode == 6) && u_scale.y >= 2.0)\n"                            \
  "    c.rgb *= mix(0.55, 1.1, sin(3.14159265 * fract(texel.y)));\n"                     \
  "  if (u_mode == 6) {\n"                                                               \
  "    float m = mod(floor(gl_FragCoord.x), 3.0);\n"                                     \
  "    vec3 mask = m < 1.0 ? vec3(1.0, 0.7, 0.7) : m < 2.0 ? vec3(0.7, 1.0, 0.7) : vec3(0.7, 0.7, 1.0);\n" \
  "    c.rgb = min(c.rgb * mask * 1.3, vec3(1.0));\n"                                    \
  "  }\n"                                                                                \
  "  if (u_mode == 7 && min(u_scale.x, u_scale.y) >= 3.0) {\n"                           \
  "    vec2 line = step(vec2(1.0) - 1.0 / u_scale, fract(texel));\n"                     \
  "    c.rgb *= 1.0 - 0.35 * max(line.x, line.y);\n"                                     \
  "  }\n"                                                                                \
  "  return c;\n"                                                                        \
  "}\n"

static const char* kFragment150 =
    "#version 150\n"
    "#define TEX texture\n"
    FILTER_FUNCTION
    "in vec2 v_uv; uniform sampler2D u_tex; uniform float u_alpha; out vec4 o_color;\n"
    "void main() { vec4 c = filtered(u_tex, v_uv); o_color = vec4(c.rgb, mix(1.0, c.a, u_alpha)); }\n";
static const char* kVertex120 =
    "#version 120\n"
    "attribute vec2 a_pos; attribute vec2 a_uv; varying vec2 v_uv;\n"
    "void main() { v_uv = a_uv; gl_Position = vec4(a_pos, 0.0, 1.0); }\n";
static const char* kFragment120 =
    "#version 120\n"
    "#define TEX texture2D\n"
    FILTER_FUNCTION
    "varying vec2 v_uv; uniform sampler2D u_tex; uniform float u_alpha;\n"
    "void main() { vec4 c = filtered(u_tex, v_uv); gl_FragColor = vec4(c.rgb, mix(1.0, c.a, u_alpha)); }\n";

static const char* kFilterIds[kFilterCount] = {"pixels", "sharp", "soft", "smooth", "epx", "crt", "crtmask", "lcd", "xbr", "fsr"};
// Ordre du menu : lissages, agrandisseurs (EPX, xBR, FSR), puis imitations d'écrans.
static const Filter kFilterOrder[kFilterCount] = {Filter::Pixels, Filter::Sharp, Filter::Soft, Filter::Smooth, Filter::Epx,
                                                  Filter::Xbr, Filter::Fsr, Filter::Crt, Filter::CrtMask, Filter::Lcd};

Filter nextFilter(Filter filter) {
  for (int i = 0; i < kFilterCount; i++) {
    if (kFilterOrder[i] == filter) return kFilterOrder[(i + 1) % kFilterCount];
  }
  return Filter::Sharp;
}

const char* filterId(Filter filter) { return kFilterIds[(int)filter]; }

Filter filterFromId(const std::string& id) {
  for (int i = 0; i < kFilterCount; i++) {
    if (id == kFilterIds[i]) return (Filter)i;
  }
  return Filter::Sharp;
}

static GLuint compile(GLenum type, const char* source) {
  GLuint shader = p_glCreateShader(type);
  p_glShaderSource(shader, 1, &source, nullptr);
  p_glCompileShader(shader);
  GLint ok = 0;
  p_glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char log[1024];
    p_glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
    logf("Shader : %s", log);
  }
  return shader;
}

bool Video::createProgram() {
  GLuint vs = compile(GL_VERTEX_SHADER, coreProfile_ ? kVertex150 : kVertex120);
  GLuint fs = compile(GL_FRAGMENT_SHADER, coreProfile_ ? kFragment150 : kFragment120);
  program_ = p_glCreateProgram();
  p_glAttachShader(program_, vs);
  p_glAttachShader(program_, fs);
  p_glBindAttribLocation(program_, 0, "a_pos");
  p_glBindAttribLocation(program_, 1, "a_uv");
  p_glLinkProgram(program_);
  p_glDeleteShader(vs);
  p_glDeleteShader(fs);
  GLint ok = 0;
  p_glGetProgramiv(program_, GL_LINK_STATUS, &ok);
  if (!ok) {
    char log[1024];
    p_glGetProgramInfoLog(program_, sizeof(log), nullptr, log);
    logf("Programme : %s", log);
    return false;
  }
  uniformTexture_ = p_glGetUniformLocation(program_, "u_tex");
  uniformAlpha_ = p_glGetUniformLocation(program_, "u_alpha");
  uniformMode_ = p_glGetUniformLocation(program_, "u_mode");
  uniformSize_ = p_glGetUniformLocation(program_, "u_size");
  uniformScale_ = p_glGetUniformLocation(program_, "u_scale");
  if (p_glGenVertexArrays) p_glGenVertexArrays(1, &vao_);
  p_glGenBuffers(1, &vbo_);
  return true;
}

// ---------------------------------------------------------------------------
// Images du cœur
// ---------------------------------------------------------------------------

void Video::onFrame(const void* data, unsigned width, unsigned height, size_t pitch) {
  if (!data) return;  // image identique à la précédente
  frameWidth_ = width;
  frameHeight_ = height;
  hasFrame_ = true;
  if (data == RETRO_HW_FRAME_BUFFER_VALID) {
    frameIsHw_ = true;
    return;
  }
  frameIsHw_ = false;
  // Le cœur a pu laisser un tampon de pixels ou un alignement : on les neutralise.
  p_glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
  glBindTexture(GL_TEXTURE_2D, frameTexture_);
  GLenum format, type;
  int bytes;
  switch (g.pixelFormat) {
    case RETRO_PIXEL_FORMAT_XRGB8888: format = GL_BGRA; type = GL_UNSIGNED_INT_8_8_8_8_REV; bytes = 4; break;
    case RETRO_PIXEL_FORMAT_RGB565: format = GL_RGB; type = GL_UNSIGNED_SHORT_5_6_5; bytes = 2; break;
    default: format = GL_BGRA; type = GL_UNSIGNED_SHORT_1_5_5_5_REV; bytes = 2; break;
  }
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
  glPixelStorei(GL_UNPACK_ROW_LENGTH, (GLint)(pitch / bytes));
  glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
  glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
  if ((int)width != textureWidth_ || (int)height != textureHeight_) {
    textureWidth_ = (int)width;
    textureHeight_ = (int)height;
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, (GLsizei)width, (GLsizei)height, 0, format, type, data);
  } else {
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, (GLsizei)width, (GLsizei)height, format, type, data);
  }
  glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
}

// ---------------------------------------------------------------------------
// Affichage
// ---------------------------------------------------------------------------

namespace {
struct Vertex {
  float x, y, u, v;
};
}  // namespace

void Video::present(const uint8_t* overlay, int overlayWidth, int overlayHeight, bool swap) {
  int winW, winH;
  SDL_GL_GetDrawableSize(window_, &winW, &winH);

  // État neutre : le cœur peut avoir laissé n'importe quel état OpenGL.
  p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
  glDisable(GL_DEPTH_TEST);
  glDisable(GL_STENCIL_TEST);
  glDisable(GL_SCISSOR_TEST);
  glDisable(GL_CULL_FACE);
  glDisable(GL_BLEND);
  glDisable(GL_FRAMEBUFFER_SRGB);
  glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
  glViewport(0, 0, winW, winH);
  glClearColor(0, 0, 0, 1);
  glClear(GL_COLOR_BUFFER_BIT);

  p_glUseProgram(program_);
  if (vao_) p_glBindVertexArray(vao_);
  p_glActiveTexture(GL_TEXTURE0);
  if (p_glBindSampler) p_glBindSampler(0, 0);
  p_glUniform1i(uniformTexture_, 0);
  p_glBindBuffer(GL_ARRAY_BUFFER, vbo_);
  p_glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(0));
  p_glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), reinterpret_cast<void*>(2 * sizeof(float)));
  p_glEnableVertexAttribArray(0);
  p_glEnableVertexAttribArray(1);

  auto drawQuad = [&](const Vertex quad[4]) {
    p_glBufferData(GL_ARRAY_BUFFER, sizeof(Vertex) * 4, quad, GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  };

  if (hasFrame_ && frameWidth_ && frameHeight_) {
    // Rapport d'aspect du cœur (sinon celui de l'image), inversé pour les écrans tournés.
    const retro_game_geometry& geo = g.av.geometry;
    float aspect = geo.aspect_ratio > 0 ? geo.aspect_ratio
                   : (float)(geo.base_width ? geo.base_width : frameWidth_) / (float)(geo.base_height ? geo.base_height : frameHeight_);
    if (g.rotation & 1) aspect = 1.0f / aspect;
    float w = (float)winW, h = w / aspect;
    if (h > winH) {
      h = (float)winH;
      w = h * aspect;
    }
    float x1 = w / winW, y1 = h / winH;  // demi-taille en coordonnées normalisées

    float u1, v1, vTop, vBottom;
    float texW, texH;
    if (frameIsHw_) {
      glBindTexture(GL_TEXTURE_2D, fboTexture_);
      texW = (float)fboWidth_;
      texH = (float)fboHeight_;
      u1 = (float)frameWidth_ / fboWidth_;
      v1 = (float)frameHeight_ / fboHeight_;
      bool bottomLeft = g.hw.bottom_left_origin;
      vTop = bottomLeft ? v1 : 0.0f;
      vBottom = bottomLeft ? 0.0f : v1;
    } else {
      glBindTexture(GL_TEXTURE_2D, frameTexture_);
      texW = (float)textureWidth_;
      texH = (float)textureHeight_;
      u1 = (float)frameWidth_ / textureWidth_;
      v1 = (float)frameHeight_ / textureHeight_;
      vTop = 0.0f;
      vBottom = v1;
    }
    // Pixels de l'écran par pixel de l'image (axes de l'image échangés par un quart de tour).
    float scaleX = ((g.rotation & 1) ? h : w) / frameWidth_, scaleY = ((g.rotation & 1) ? w : h) / frameHeight_;
    p_glUniform1i(uniformMode_, (int)filter_);
    p_glUniform2f(uniformSize_, texW, texH);
    p_glUniform2f(uniformScale_, scaleX, scaleY);
    // Échantillons au centre des pixels (bicubique, EPX, LCD, pixels nets) : filtrage au plus proche.
    bool nearest = filter_ == Filter::Pixels || filter_ == Filter::Soft || filter_ == Filter::Epx || filter_ == Filter::Lcd ||
                   filter_ == Filter::Xbr || filter_ == Filter::Fsr;
    GLint filter = nearest ? GL_NEAREST : GL_LINEAR;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // Coins de l'image (haut-gauche, haut-droit, bas-droit, bas-gauche) ; la rotation (quarts de
    // tour antihoraires) décale les coins de l'image affectés à ceux de l'écran.
    float uv[4][2] = {{0, vTop}, {u1, vTop}, {u1, vBottom}, {0, vBottom}};
    float pos[4][2] = {{-x1, y1}, {x1, y1}, {x1, -y1}, {-x1, -y1}};
    int k = (int)g.rotation;
    auto corner = [&](int screen) {
      const float* t = uv[(screen + k) % 4];
      return Vertex{pos[screen][0], pos[screen][1], t[0], t[1]};
    };
    // Bande de triangles : haut-gauche, bas-gauche, haut-droit, bas-droit.
    Vertex quad[4] = {corner(0), corner(3), corner(1), corner(2)};
    p_glUniform1f(uniformAlpha_, 0.0f);
    int outW = (int)(w + 0.5f), outH = (int)(h + 0.5f);
    if (filter_ == Filter::Fsr && preparePostTarget(outW, outH)) {
      // FSR 1 : agrandissement (EASU) dans une image à la taille de l'affichage, puis netteté (RCAS)
      // en la dessinant à l'écran.
      p_glBindFramebuffer(GL_FRAMEBUFFER, postFbo_);
      glViewport(0, 0, outW, outH);
      p_glUniform1i(uniformMode_, 9);
      Vertex full[4];
      for (int i = 0; i < 4; i++) full[i] = Vertex{quad[i].x / x1, quad[i].y / y1, quad[i].u, quad[i].v};
      drawQuad(full);
      p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
      glViewport(0, 0, winW, winH);
      glBindTexture(GL_TEXTURE_2D, postTexture_);
      p_glUniform1i(uniformMode_, 10);
      p_glUniform2f(uniformSize_, (float)outW, (float)outH);
      // Image rendue de bas en haut (origine OpenGL) : haut de l'écran = v 1.
      Vertex out[4] = {{-x1, y1, 0, 1}, {-x1, -y1, 0, 0}, {x1, y1, 1, 1}, {x1, -y1, 1, 0}};
      drawQuad(out);
    } else {
      drawQuad(quad);
    }
  }

  if (overlay) {
    glBindTexture(GL_TEXTURE_2D, overlayTexture_);
    p_glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, overlayWidth, overlayHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, overlay);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    // Surimpression à l'échelle de la fenêtre, proportions conservées.
    float aspect = (float)overlayWidth / overlayHeight;
    float w = (float)winW, h = w / aspect;
    if (h > winH) {
      h = (float)winH;
      w = h * aspect;
    }
    float x1 = w / winW, y1 = h / winH;
    Vertex quad[4] = {{-x1, y1, 0, 0}, {-x1, -y1, 0, 1}, {x1, y1, 1, 0}, {x1, -y1, 1, 1}};
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    p_glUniform1i(uniformMode_, (int)Filter::Smooth);  // menu : sans effet
    p_glUniform1f(uniformAlpha_, 1.0f);
    drawQuad(quad);
    glDisable(GL_BLEND);
  }

  if (swap) SDL_GL_SwapWindow(window_);
}

bool Video::saveWindow(const std::string& path) {
  int w, h;
  SDL_GL_GetDrawableSize(window_, &w, &h);
  std::vector<uint8_t> pixels((size_t)w * h * 4);
  p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
  p_glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
  glPixelStorei(GL_PACK_ALIGNMENT, 4);
  glReadBuffer(GL_BACK);
  glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
  for (int y = 0; y < h / 2; y++) std::swap_ranges(&pixels[(size_t)y * w * 4], &pixels[(size_t)(y + 1) * w * 4], &pixels[(size_t)(h - 1 - y) * w * 4]);
  SDL_Surface* surface = SDL_CreateRGBSurfaceWithFormatFrom(pixels.data(), w, h, 32, w * 4, SDL_PIXELFORMAT_RGBA32);
  if (!surface) return false;
  bool ok = SDL_SaveBMP(surface, path.c_str()) == 0;
  SDL_FreeSurface(surface);
  return ok;
}
