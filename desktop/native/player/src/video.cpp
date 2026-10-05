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
#define FILTER_FUNCTION                                                                  \
  "uniform int u_mode; uniform vec2 u_size; uniform vec2 u_scale;\n"                     \
  "vec4 filtered(sampler2D tex, vec2 uv) {\n"                                            \
  "  if (u_mode == 0 || u_mode == 2) return TEX(tex, uv);\n"                             \
  "  vec2 texel = uv * u_size;\n"                                                        \
  "  vec2 centerDist = fract(texel) - 0.5;\n"                                            \
  "  vec4 c = TEX(tex, uv);\n"                                                           \
  "  if (u_mode != 4) {\n"                                                               \
  "    vec2 range = max(vec2(0.0), 0.5 - 0.5 / u_scale);\n"                              \
  "    vec2 f = (centerDist - clamp(centerDist, -range, range)) * u_scale + 0.5;\n"      \
  "    c = TEX(tex, (floor(texel) + f) / u_size);\n"                                     \
  "  }\n"                                                                                \
  "  if (u_mode == 3 && u_scale.y >= 2.0)\n"                                             \
  "    c.rgb *= mix(0.55, 1.1, sin(3.14159265 * fract(texel.y)));\n"                     \
  "  if (u_mode == 4 && min(u_scale.x, u_scale.y) >= 3.0) {\n"                           \
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

static const char* kFilterIds[kFilterCount] = {"pixels", "sharp", "smooth", "crt", "lcd"};

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
    GLint filter = filter_ == Filter::Pixels || filter_ == Filter::Lcd ? GL_NEAREST : GL_LINEAR;
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
    drawQuad(quad);
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
