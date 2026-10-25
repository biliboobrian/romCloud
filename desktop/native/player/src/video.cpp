#include "video.h"

#include <windows.h>

#include <SDL3/SDL_opengl.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <regex>
#include <vector>

#include "core.h"
#include "util.h"
#include "vulkan.h"
// Shaders CUT, CUT2 et CUT3 : mêmes sources que LibretroDroid (Android).
#include "../../../../android/libretrodroid/src/main/cpp/shadermanager.h"

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
  X(PFNGLUNIFORM4FPROC, glUniform4f)                                            \
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
  X(PFNGLDELETERENDERBUFFERSPROC, glDeleteRenderbuffers)                          X(PFNGLBLENDFUNCSEPARATEPROC, glBlendFuncSeparate)

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
#ifndef GL_VERTEX_ARRAY_BINDING
#define GL_VERTEX_ARRAY_BINDING 0x85B5
#endif
#ifndef GL_SAMPLER_BINDING
#define GL_SAMPLER_BINDING 0x8919
#endif
#ifndef GL_TEXTURE_SWIZZLE_R
#define GL_TEXTURE_SWIZZLE_R 0x8E42
#define GL_TEXTURE_SWIZZLE_B 0x8E44
#endif

// Image des cœurs Vulkan partagée avec OpenGL (GL_EXT_memory_object_win32, GL_EXT_semaphore_win32) :
// constantes et fonctions, lues seulement si le pilote les propose.
namespace {
constexpr GLenum kDedicatedMemoryObject = 0x9581, kNumDeviceUuids = 0x9596, kDeviceUuid = 0x9597;
constexpr GLenum kHandleOpaqueWin32 = 0x9587, kLayoutShaderReadOnly = 0x9591;
// Format interne 16 bits 5-6-5 (OpenGL 4.1, GL_ARB_ES2_compatibility).
constexpr GLint kRgb565 = 0x8D62;
typedef void(APIENTRY* PfnCreateMemoryObjects)(GLsizei, GLuint*);
typedef void(APIENTRY* PfnDeleteMemoryObjects)(GLsizei, const GLuint*);
typedef void(APIENTRY* PfnMemoryObjectParameteriv)(GLuint, GLenum, const GLint*);
typedef void(APIENTRY* PfnImportMemoryWin32Handle)(GLuint, GLuint64, GLenum, void*);
typedef void(APIENTRY* PfnTexStorageMem2D)(GLenum, GLsizei, GLenum, GLsizei, GLsizei, GLuint, GLuint64);
typedef void(APIENTRY* PfnGenSemaphores)(GLsizei, GLuint*);
typedef void(APIENTRY* PfnDeleteSemaphores)(GLsizei, const GLuint*);
typedef void(APIENTRY* PfnImportSemaphoreWin32Handle)(GLuint, GLenum, void*);
typedef void(APIENTRY* PfnWaitSemaphore)(GLuint, GLuint, const GLuint*, GLuint, const GLuint*, const GLenum*);
typedef void(APIENTRY* PfnSignalSemaphore)(GLuint, GLuint, const GLuint*, GLuint, const GLuint*, const GLenum*);
typedef void(APIENTRY* PfnGetUnsignedBytei)(GLenum, GLuint, GLubyte*);
PfnCreateMemoryObjects glCreateMemoryObjects = nullptr;
PfnDeleteMemoryObjects glDeleteMemoryObjects = nullptr;
PfnMemoryObjectParameteriv glMemoryObjectParameteriv = nullptr;
PfnImportMemoryWin32Handle glImportMemoryWin32Handle = nullptr;
PfnTexStorageMem2D glTexStorageMem2D = nullptr;
PfnGenSemaphores glGenSemaphores = nullptr;
PfnDeleteSemaphores glDeleteSemaphores = nullptr;
PfnImportSemaphoreWin32Handle glImportSemaphoreWin32Handle = nullptr;
PfnWaitSemaphore glWaitSemaphore = nullptr;
PfnSignalSemaphore glSignalSemaphore = nullptr;
PfnGetUnsignedBytei glGetUnsignedBytei = nullptr;

template <typename T>
bool loadGl(T& out, const char* name) {
  out = reinterpret_cast<T>(SDL_GL_GetProcAddress(name));
  return out != nullptr;
}
}  // namespace

// ---------------------------------------------------------------------------
// État OpenGL du cœur
// ---------------------------------------------------------------------------

namespace {
/**
 * État OpenGL du cœur gardé pendant que l'affichage dessine (même contexte que le cœur) : tout ce
 * que l'affichage change est rétabli à l'identique en sortant. Les cœurs à rendu matériel mémorisent
 * ce qu'ils ont lié (LRPS2 : son tableau de sommets, qui porte aussi son tampon d'indices) et ne le
 * relient pas d'une image à l'autre ; avec le tableau de sommets de l'affichage resté lié, LRPS2
 * dessinait sans ses indices et le pilote lisait une adresse presque nulle (plantage).
 */
class GLStateGuard {
 public:
  GLStateGuard() {
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFb_);
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readFb_);
    glGetIntegerv(GL_RENDERBUFFER_BINDING, &renderbuffer_);
    glGetIntegerv(GL_VIEWPORT, viewport_);
    for (int i = 0; i < kCaps; i++) enabled_[i] = glIsEnabled(kCapList[i]);
    glGetBooleanv(GL_COLOR_WRITEMASK, colorMask_);
    glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor_);
    glGetIntegerv(GL_CURRENT_PROGRAM, &program_);
    if (p_glBindVertexArray) glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao_);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &arrayBuffer_);
    glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &unpackBuffer_);
    glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &packBuffer_);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &unpackAlignment_);
    glGetIntegerv(GL_UNPACK_ROW_LENGTH, &unpackRowLength_);
    glGetIntegerv(GL_PACK_ALIGNMENT, &packAlignment_);
    glGetIntegerv(GL_READ_BUFFER, &readBuffer_);
    glGetIntegerv(GL_BLEND_SRC_RGB, &blend_[0]);
    glGetIntegerv(GL_BLEND_DST_RGB, &blend_[1]);
    glGetIntegerv(GL_BLEND_SRC_ALPHA, &blend_[2]);
    glGetIntegerv(GL_BLEND_DST_ALPHA, &blend_[3]);
    // Unité de texture 0 (celle de l'affichage) : texture et échantillonneur.
    glGetIntegerv(GL_ACTIVE_TEXTURE, &activeTexture_);
    p_glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &texture0_);
    if (p_glBindSampler) glGetIntegerv(GL_SAMPLER_BINDING, &sampler0_);
  }

  ~GLStateGuard() {
    p_glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, (GLuint)texture0_);
    if (p_glBindSampler) p_glBindSampler(0, (GLuint)sampler0_);
    p_glActiveTexture((GLenum)activeTexture_);
    p_glBlendFuncSeparate((GLenum)blend_[0], (GLenum)blend_[1], (GLenum)blend_[2], (GLenum)blend_[3]);
    glPixelStorei(GL_PACK_ALIGNMENT, packAlignment_);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, unpackRowLength_);
    glPixelStorei(GL_UNPACK_ALIGNMENT, unpackAlignment_);
    p_glBindBuffer(GL_PIXEL_PACK_BUFFER, (GLuint)packBuffer_);
    p_glBindBuffer(GL_PIXEL_UNPACK_BUFFER, (GLuint)unpackBuffer_);
    p_glBindBuffer(GL_ARRAY_BUFFER, (GLuint)arrayBuffer_);
    if (p_glBindVertexArray) p_glBindVertexArray((GLuint)vao_);
    p_glUseProgram((GLuint)program_);
    glClearColor(clearColor_[0], clearColor_[1], clearColor_[2], clearColor_[3]);
    glColorMask(colorMask_[0], colorMask_[1], colorMask_[2], colorMask_[3]);
    for (int i = 0; i < kCaps; i++) {
      if (enabled_[i]) glEnable(kCapList[i]);
      else glDisable(kCapList[i]);
    }
    glViewport(viewport_[0], viewport_[1], viewport_[2], viewport_[3]);
    p_glBindRenderbuffer(GL_RENDERBUFFER, (GLuint)renderbuffer_);
    p_glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)readFb_);
    p_glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)drawFb_);
    // Tampon de lecture : celui du framebuffer lu, une fois celui-ci relié.
    glReadBuffer((GLenum)readBuffer_);
  }

  GLStateGuard(const GLStateGuard&) = delete;
  GLStateGuard& operator=(const GLStateGuard&) = delete;

 private:
  static constexpr int kCaps = 6;
  static constexpr GLenum kCapList[kCaps] = {GL_DEPTH_TEST, GL_STENCIL_TEST, GL_SCISSOR_TEST, GL_CULL_FACE, GL_BLEND, GL_FRAMEBUFFER_SRGB};
  GLint drawFb_ = 0, readFb_ = 0, renderbuffer_ = 0, viewport_[4] = {0, 0, 0, 0};
  GLboolean enabled_[kCaps] = {}, colorMask_[4] = {GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE};
  GLfloat clearColor_[4] = {0, 0, 0, 0};
  GLint program_ = 0, vao_ = 0, arrayBuffer_ = 0, unpackBuffer_ = 0, packBuffer_ = 0;
  GLint unpackAlignment_ = 4, unpackRowLength_ = 0, packAlignment_ = 4, readBuffer_ = GL_BACK;
  GLint blend_[4] = {GL_ONE, GL_ZERO, GL_ONE, GL_ZERO};
  GLint activeTexture_ = GL_TEXTURE0, texture0_ = 0, sampler0_ = 0;
};
}  // namespace

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
  if (g.hwRender && !g.vulkan && g.hw.context_type == RETRO_HW_CONTEXT_OPENGL_CORE) {
    int major = g.hw.version_major ? (int)g.hw.version_major : 3;
    int minor = g.hw.version_major ? (int)g.hw.version_minor : 2;
    ok = tryContext(window_, major, minor, core, context_);
    coreProfile_ = true;
  } else if (g.hwRender && !g.vulkan) {
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
  SDL_GL_SetSwapInterval(0);  // synchro verticale choisie par la boucle (setSwapInterval)
  swapInterval_ = 0;
  logf("OpenGL : %s — %s", (const char*)glGetString(GL_VERSION), (const char*)glGetString(GL_RENDERER));
  return true;
}

bool Video::create(const std::string& title, bool fullscreen, bool hidden, std::string& error) {
  fullscreen_ = fullscreen;
  const retro_game_geometry& geo = g.av.geometry;
  int scale = 3;
  int w = std::max(640, (int)geo.base_width * scale), h = std::max(480, (int)geo.base_height * scale);
  Uint32 flags = SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY;
  if (fullscreen) flags |= SDL_WINDOW_FULLSCREEN;
  if (hidden) flags |= SDL_WINDOW_HIDDEN;
  window_ = SDL_CreateWindow(title.c_str(), w, h, flags);
  if (!window_) {
    error = SDL_GetError();
    return false;
  }
  if (!hidden) SDL_RaiseWindow(window_);  // devant RomCloud (SDL_HINT_FORCE_RAISEWINDOW)
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
  rgb565_ = SDL_GL_ExtensionSupported("GL_ARB_ES2_compatibility");
  if (g.vulkan) {
    // Cœur Vulkan : périphérique créé avant son context_reset, image affichée par OpenGL.
    if (!vulkan::create(error)) return false;
    if (g.hw.context_reset) g.hw.context_reset();
  } else if (g.hwRender) {
    allocateHwFramebuffer();
    if (g.hw.context_reset) g.hw.context_reset();
  }
  if (fullscreen) SDL_HideCursor();
  else SDL_ShowCursor();
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
  if (g.vulkan) {
    dropShared();
    vulkan::destroy();
  }
  if (context_) {
    // Images du DLSS partagées avec OpenGL : rendues avant la fin du contexte.
    SDL_GL_MakeCurrent(window_, context_);
    dlss_.destroy();
    SDL_GL_DestroyContext(context_);
  }
  if (window_) SDL_DestroyWindow(window_);
  context_ = nullptr;
  window_ = nullptr;
}

bool Video::saveFrame(const std::string& path) {
  SDL_Surface* surface = captureFrame();
  if (!surface) return false;
  bool ok = SDL_SaveBMP(surface, path.c_str());
  SDL_DestroySurface(surface);
  return ok;
}

SDL_Surface* Video::captureFrame() {
  if (!hasFrame_ || !frameWidth_ || !frameHeight_) return nullptr;
  GLStateGuard keep;
  int w = (int)frameWidth_, h = (int)frameHeight_;
  SDL_Surface* surface = SDL_CreateSurface(w, h, SDL_PIXELFORMAT_RGBA32);
  if (!surface) return nullptr;
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
    const int texWidth = sharedActive_ ? sharedWidth_ : textureWidth_, texHeight = sharedActive_ ? sharedHeight_ : textureHeight_;
    std::vector<uint8_t> full((size_t)texWidth * texHeight * 4);
    glBindTexture(GL_TEXTURE_2D, sharedActive_ ? sharedTexture_ : frameTexture_);
    // Rouge et bleu échangés dans l'image partagée : la lecture ne tient pas compte de l'inversion.
    glGetTexImage(GL_TEXTURE_2D, 0, sharedActive_ && sharedBgra_ ? GL_BGRA : GL_RGBA, GL_UNSIGNED_BYTE, full.data());
    for (int y = 0; y < h; y++) memcpy(&pixels[(size_t)y * w * 4], &full[(size_t)y * texWidth * 4], (size_t)w * 4);
    flip = false;
  }
  if (flip) {
    for (int y = 0; y < h / 2; y++) std::swap_ranges(&pixels[(size_t)y * w * 4], &pixels[(size_t)(y + 1) * w * 4], &pixels[(size_t)(h - 1 - y) * w * 4]);
  }
  // Image opaque (certains cœurs laissent l'alpha à 0).
  for (size_t i = 3; i < pixels.size(); i += 4) pixels[i] = 255;
  for (int y = 0; y < h; y++) memcpy((uint8_t*)surface->pixels + (size_t)y * surface->pitch, &pixels[(size_t)y * w * 4], (size_t)w * 4);
  return surface;
}

double Video::refreshRate() const {
  if (!window_) return 0;
  const SDL_DisplayMode* mode = SDL_GetCurrentDisplayMode(SDL_GetDisplayForWindow(window_));
  return mode ? mode->refresh_rate : 0;
}

int Video::syncInterval(double fps) const {
  const double refresh = refreshRate();
  if (vsyncFailed_ || refresh <= 0 || fps <= 0) return 0;
  const int interval = (int)std::lround(refresh / fps);
  // Écart rattrapé par la vitesse du son (1 % au plus : inaudible).
  if (interval < 1 || interval > 4 || std::fabs(refresh / interval - fps) / fps > 0.01) return 0;
  return interval;
}

void Video::setSwapInterval(int interval) {
  if (interval == swapInterval_ || !context_ || (interval && vsyncFailed_)) return;
  if (SDL_GL_SetSwapInterval(interval)) {
    swapInterval_ = interval;
    logf("Synchro verticale : écran %.2f Hz, échange des tampons tous les %d rafraîchissements", refreshRate(), interval);
  } else {
    vsyncFailed_ = true;
    logf("Synchro verticale impossible : %s", SDL_GetError());
  }
}

void Video::toggleFullscreen() {
  fullscreen_ = !fullscreen_;
  SDL_SetWindowFullscreen(window_, fullscreen_);
  if (fullscreen_) SDL_HideCursor();
  else SDL_ShowCursor();
}

// ---------------------------------------------------------------------------
// Framebuffer du rendu matériel
// ---------------------------------------------------------------------------

void Video::allocateHwFramebuffer() {
  int w = (int)std::max(1u, g.av.geometry.max_width), h = (int)std::max(1u, g.av.geometry.max_height);
  if (fbo_ && w <= fboWidth_ && h <= fboHeight_) return;
  // Agrandi en cours de partie (SET_SYSTEM_AV_INFO pendant retro_run) : état du cœur gardé.
  GLStateGuard keep;
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
  if (context_ && g.hwRender && !g.vulkan) allocateHwFramebuffer();
}

// ---------------------------------------------------------------------------
// Shaders
// ---------------------------------------------------------------------------

// Sommet du rectangle dessiné : position (coordonnées normalisées) et coordonnées de texture.
namespace {
struct Vertex {
  float x, y, u, v;
};
}  // namespace

static const char* kVertex150 =
    "#version 150\n"
    "in vec2 a_pos; in vec2 a_uv; out vec2 v_uv;\n"
    "void main() { v_uv = a_uv; gl_Position = vec4(a_pos, 0.0, 1.0); }\n";
// Filtre d'image (u_mode : rang dans Filter) ; u_size : taille de la texture en pixels, u_scale :
// pixels de l'écran par pixel de l'image. Lissage net (et base de l'écran cathodique) d'après
// « sharp-bilinear-simple » de libretro : chaque pixel reste net, seule sa bordure est lissée.
// Modes (rang dans Filter) : 0 pixels, 1 lissage net, 2 doux (bicubique de Mitchell-Netravali,
// Catmull-Rom par défaut), 3 bilinéaire, 4 EPX / Scale2x, 5 cathodique, 6 cathodique + masque RGB
// (grille d'ouverture), 7 LCD, 8 xBR (niveau 1 : angles des contours redessinés en diagonale),
// 9 FSR 1 EASU (agrandissement suivant la direction des contours, d'après AMD FidelityFX Super
// Resolution 1), 13 écran de GBA / GBC (couleurs de l'écran d'origine d'après « gba-color » et
// « gbc-color » de Pokefan531, grille), 14 Game Boy (palette verte ou de la Game Boy Pocket, matrice
// de points), 20 FSR 1 RCAS (netteté adaptée au contraste, seconde passe sur l'image agrandie).
// CUT, CUT2 et CUT3 : programmes à part (prepareCut).
// Réglages (filterParams) : u_params, u_params2 ; couleurs de tous les filtres (u_color : luminosité,
// contraste, saturation, gamma), appliquées à l'image finale (pas à la première passe de FSR).
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
  "  float aa = max(u_params.y, 0.1) / max(min(u_scale.x, u_scale.y), 1.0);\n" \
  "  float t = dot(fp, s);\n" \
  "  return vec4(mix(E, edge, smoothstep(0.5 - aa, 0.5 + aa, t) * u_params.x), 1.0);\n" \
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
  "vec3 handheld(vec3 c, vec2 texel) {\n" \
  "  mat3 gba = mat3(0.82, 0.125, 0.195, 0.24, 0.665, 0.075, -0.06, 0.21, 0.73);\n" \
  "  mat3 gbc = mat3(0.78824, 0.025, 0.12039, 0.12157, 0.72941, 0.12157, 0.0, 0.275, 0.82);\n" \
  "  vec3 lin = pow(clamp(c, 0.0, 1.0), vec3(2.2)) * 0.94;\n" \
  "  vec3 screen = pow(clamp((u_params.z < 0.5 ? gba : gbc) * lin, 0.0, 1.0), vec3(1.0 / 2.2));\n" \
  "  c = mix(c, screen, u_params.x);\n" \
  "  if (min(u_scale.x, u_scale.y) >= 3.0) {\n" \
  "    vec2 line = step(vec2(1.0) - 1.0 / u_scale, fract(texel));\n" \
  "    c *= 1.0 - u_params.y * max(line.x, line.y);\n" \
  "  }\n" \
  "  return c;\n" \
  "}\n" \
  "vec3 dotMatrix(vec3 c, vec2 texel) {\n" \
  "  float l = dot(c, vec3(0.299, 0.587, 0.114));\n" \
  "  vec3 light = vec3(0.92);\n" \
  "  if (u_params.x < 0.5) {\n" \
  "    vec3 c0 = vec3(0.059, 0.22, 0.059), c1 = vec3(0.188, 0.384, 0.188);\n" \
  "    vec3 c2 = vec3(0.545, 0.675, 0.059), c3 = vec3(0.608, 0.737, 0.059);\n" \
  "    float t = l * 3.0;\n" \
  "    c = t < 1.0 ? mix(c0, c1, t) : t < 2.0 ? mix(c1, c2, t - 1.0) : mix(c2, c3, t - 2.0);\n" \
  "    light = vec3(0.64, 0.76, 0.09);\n" \
  "  } else if (u_params.x < 1.5) {\n" \
  "    c = mix(vec3(0.1, 0.1, 0.09), vec3(0.77, 0.79, 0.71), l);\n" \
  "    light = vec3(0.8, 0.82, 0.74);\n" \
  "  }\n" \
  "  if (min(u_scale.x, u_scale.y) >= 2.0) {\n" \
  "    vec2 gap = step(vec2(1.0) - u_params.z / u_scale, fract(texel));\n" \
  "    c = mix(c, light, u_params.y * max(gap.x, gap.y));\n" \
  "  }\n" \
  "  return c;\n" \
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
  "  lobe = max(lobe * u_params.x, -0.2);\n" \
  "  return vec4((lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0), 1.0);\n" \
  "}\n"

#define FILTER_FUNCTION                                                                  \
  "uniform int u_mode; uniform vec2 u_size; uniform vec2 u_scale;\n"                     \
  "uniform vec4 u_params; uniform vec4 u_params2; uniform vec4 u_color; uniform vec4 u_rect;\n" \
  FILTER_EXTRA                                                                           \
  "float cubic(float x) {\n"                                                             \
  "  float B = u_params.x, C = u_params.y;\n"                                            \
  "  x = abs(x);\n"                                                                      \
  "  if (x < 1.0) return ((12.0 - 9.0 * B - 6.0 * C) * x * x * x + (-18.0 + 12.0 * B + 6.0 * C) * x * x + (6.0 - 2.0 * B)) / 6.0;\n" \
  "  if (x < 2.0) return ((-B - 6.0 * C) * x * x * x + (6.0 * B + 30.0 * C) * x * x + (-12.0 * B - 48.0 * C) * x + (8.0 * B + 24.0 * C)) / 6.0;\n" \
  "  return 0.0;\n"                                                                      \
  "}\n"                                                                                  \
  "bool same(vec4 a, vec4 b) { return distance(a.rgb, b.rgb) < u_params.x; }\n"          \
  "vec3 grade(vec3 c) {\n"                                                               \
  "  c = pow(max(c, vec3(0.0)), vec3(1.0 / u_color.w));\n"                               \
  "  c = ((c - 0.5) * u_color.y + 0.5) * u_color.x;\n"                                   \
  "  return clamp(mix(vec3(dot(c, vec3(0.299, 0.587, 0.114))), c, u_color.z), 0.0, 1.0);\n" \
  "}\n"                                                                                  \
  "vec4 filtered(sampler2D tex, vec2 uv) {\n"                                            \
  "  vec2 du = dFdx(uv), dv = dFdy(uv);\n"                                               \
  "  bool crt = u_mode == 5 || u_mode == 6;\n"                                           \
  "  vec2 p = (gl_FragCoord.xy - u_rect.xy) / u_rect.zw * 2.0 - 1.0;\n"                  \
  "  if (crt && u_params.z > 0.0) {\n"                                                   \
  "    vec2 q = p * (1.0 + u_params.z * 0.2 * (p.yx * p.yx));\n"                         \
  "    if (abs(q.x) > 1.0 || abs(q.y) > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);\n"         \
  "    vec2 d = (q - p) * 0.5 * u_rect.zw;\n"                                            \
  "    uv += du * d.x + dv * d.y;\n"                                                     \
  "    p = q;\n"                                                                         \
  "  }\n"                                                                                \
  "  if (u_mode == 0 || u_mode == 3) return TEX(tex, uv);\n"                             \
  "  if (u_mode == 8) return xbr(tex, uv);\n"                                            \
  "  if (u_mode == 9) return easu(tex, uv);\n"                                           \
  "  if (u_mode == 20) return rcas(tex, uv);\n"                                          \
  "  vec2 texel = uv * u_size;\n"                                                        \
  "  if (u_mode == 2) {\n"                                                               \
  "    vec2 base = floor(texel - 0.5) + 0.5;\n"                                          \
  "    vec2 f = texel - base;\n"                                                         \
  "    vec4 sum = vec4(0.0);\n"                                                          \
  "    for (int j = -1; j <= 2; j++) for (int i = -1; i <= 2; i++) {\n"                  \
  "      float w = cubic(f.x - float(i)) * cubic(f.y - float(j));\n"                     \
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
  "  if (u_mode != 7 && u_mode < 13) {\n"                                                \
  "    float width = u_mode == 1 ? u_params.x : crt ? u_params.w : 1.0;\n"               \
  "    vec2 range = max(vec2(0.0), 0.5 - 0.5 * width / u_scale);\n"                      \
  "    vec2 f = (centerDist - clamp(centerDist, -range, range)) * u_scale / width + 0.5;\n" \
  "    c = TEX(tex, (floor(texel) + f) / u_size);\n"                                     \
  "  }\n"                                                                                \
  "  if (crt && u_scale.y >= 2.0)\n"                                                     \
  "    c.rgb *= mix(1.0 - u_params.x, 1.0 + u_params2.y * u_params.x, sin(3.14159265 * fract(texel.y)));\n" \
  "  if (crt) c.rgb *= 1.0 - u_params2.x * 0.5 * dot(p, p);\n"                           \
  "  if (crt && u_params.y > 0.0) {\n"                                                   \
  "    float m = mod(floor(gl_FragCoord.x), 3.0);\n"                                     \
  "    float o = 1.0 - u_params.y;\n"                                                    \
  "    vec3 mask = m < 1.0 ? vec3(1.0, o, o) : m < 2.0 ? vec3(o, 1.0, o) : vec3(o, o, 1.0);\n" \
  "    c.rgb = min(c.rgb * mask * (1.0 + u_params.y), vec3(1.0));\n"                     \
  "  }\n"                                                                                \
  "  if (u_mode == 7 && min(u_scale.x, u_scale.y) >= 3.0) {\n"                           \
  "    vec2 line = step(vec2(1.0) - u_params.y / u_scale, fract(texel));\n"              \
  "    c.rgb *= 1.0 - u_params.x * max(line.x, line.y);\n"                               \
  "  }\n"                                                                                \
  "  if (u_mode == 13) c.rgb = handheld(c.rgb, texel);\n"                                \
  "  if (u_mode == 14) c.rgb = dotMatrix(c.rgb, texel);\n"                               \
  "  return c;\n"                                                                        \
  "}\n"

static const char* kFragment150 =
    "#version 150\n"
    "#define TEX texture\n"
    FILTER_FUNCTION
    "in vec2 v_uv; uniform sampler2D u_tex; uniform float u_alpha; out vec4 o_color;\n"
    "void main() { vec4 c = filtered(u_tex, v_uv); o_color = vec4(u_mode == 9 ? c.rgb : grade(c.rgb), mix(1.0, c.a, u_alpha)); }\n";
static const char* kVertex120 =
    "#version 120\n"
    "attribute vec2 a_pos; attribute vec2 a_uv; varying vec2 v_uv;\n"
    "void main() { v_uv = a_uv; gl_Position = vec4(a_pos, 0.0, 1.0); }\n";
static const char* kFragment120 =
    "#version 120\n"
    "#define TEX texture2D\n"
    FILTER_FUNCTION
    "varying vec2 v_uv; uniform sampler2D u_tex; uniform float u_alpha;\n"
    "void main() { vec4 c = filtered(u_tex, v_uv); gl_FragColor = vec4(u_mode == 9 ? c.rgb : grade(c.rgb), mix(1.0, c.a, u_alpha)); }\n";

// Passe préalable, à la taille du jeu (image de sortie : haut de l'image en v = 0, comme celle du
// cœur). u_rect : coordonnées de l'image du cœur dans sa texture (largeur, v du haut, v du bas),
// u_size : taille de cette texture ; u_params : rémanence (part de l'image précédente, u_hist),
// lissage du tramage. Tramage : deux couleurs qui alternent sur cinq pixels (colonnes de la Mega
// Drive, lignes, damier), motif continué au-dessus et en dessous (ou de part et d'autre) : pixel
// mêlé à ses voisins, comme sur un téléviseur de l'époque ; traits fins et textes laissés nets.
#define PREP_FUNCTION \
  "uniform vec4 u_rect; uniform vec2 u_size; uniform vec4 u_params;\n" \
  "vec3 px(sampler2D tex, vec2 base, vec2 d) { return TEX(tex, (base + d + 0.5) / u_size).rgb; }\n" \
  "bool same(vec3 a, vec3 b) { return dot(abs(a - b), vec3(1.0)) < 0.06; }\n" \
  "vec3 prep(sampler2D src, sampler2D hist, vec2 uv) {\n" \
  "  vec2 suv = vec2(uv.x * u_rect.x, mix(u_rect.y, u_rect.z, uv.y));\n" \
  "  vec2 base = floor(suv * u_size);\n" \
  "  vec3 c = px(src, base, vec2(0.0));\n" \
  "  if (u_params.y > 0.0) {\n" \
  "    vec3 l = px(src, base, vec2(-1.0, 0.0)), r = px(src, base, vec2(1.0, 0.0));\n" \
  "    vec3 u = px(src, base, vec2(0.0, -1.0)), d = px(src, base, vec2(0.0, 1.0));\n" \
  "    bool run = same(l, r) && !same(c, l) && same(px(src, base, vec2(-2.0, 0.0)), c) && same(px(src, base, vec2(2.0, 0.0)), c);\n" \
  "    bool h = run && (same(u, c) || same(u, l)) && (same(d, c) || same(d, l));\n" \
  "    run = same(u, d) && !same(c, u) && same(px(src, base, vec2(0.0, -2.0)), c) && same(px(src, base, vec2(0.0, 2.0)), c);\n" \
  "    bool v = run && (same(l, c) || same(l, u)) && (same(r, c) || same(r, u));\n" \
  "    vec3 o = h && v ? (l + r + u + d) * 0.25 : h ? (l + r) * 0.5 : v ? (u + d) * 0.5 : c;\n" \
  "    c = mix(c, (c + o) * 0.5, u_params.y);\n" \
  "  }\n" \
  "  return mix(c, TEX(hist, uv).rgb, u_params.x);\n" \
  "}\n"
static const char* kPrep150 =
    "#version 150\n"
    "#define TEX texture\n"
    PREP_FUNCTION
    "in vec2 v_uv; uniform sampler2D u_src; uniform sampler2D u_hist; out vec4 o_color;\n"
    "void main() { o_color = vec4(prep(u_src, u_hist, v_uv), 1.0); }\n";
static const char* kPrep120 =
    "#version 120\n"
    "#define TEX texture2D\n"
    PREP_FUNCTION
    "varying vec2 v_uv; uniform sampler2D u_src; uniform sampler2D u_hist;\n"
    "void main() { gl_FragColor = vec4(prep(u_src, u_hist, v_uv), 1.0); }\n";

std::vector<FilterParam> filterParams(Filter filter) {
  // CRT : lignes de balayage (et leur éclat), masque RGB, courbure, transition des pixels, coins assombris.
  const FilterParam scanlines{"scanlines", "fp_scanlines", 0, 0.45f, 0.0f, 0.9f, 0.05f};
  const FilterParam curvature{"curvature", "fp_curvature", 2, 0.0f, 0.0f, 1.0f, 0.1f};
  const FilterParam crtSoftness{"softness", "fp_softness", 3, 1.0f, 0.5f, 4.0f, 0.25f};
  const FilterParam vignette{"vignette", "fp_vignette", 4, 0.0f, 0.0f, 1.0f, 0.05f};
  const FilterParam glow{"glow", "fp_glow", 5, 0.22f, 0.0f, 0.6f, 0.02f};
  // Écrans LCD : rémanence (image précédente mêlée à la nouvelle, passe préalable).
  auto ghost = [](float def) { return FilterParam{"ghosting", "fp_ghosting", kGhostSlot, def, 0.0f, 0.9f, 0.05f}; };
  // CUT, CUT2 et CUT3 : réglages de LibretroDroid (valeurs de VideoFilters.kt sous Android), donnés
  // au shader par leurs #define (cutDefine).
  auto cut = [](int index, const char* id, const char* label, float def, float min = 0.0f, float max = 1.0f, float step = 0.05f) {
    return FilterParam{id, label, kCutSlot + index, def, min, max, step};
  };
  const FilterParam dynamicBlend{"dynamic_blend", "fp_dynamic_blend", kCutSlot, 1.0f, 0.0f, 1.0f, 1.0f, ParamFormat::Choice, "no|yes"};
  auto fastLuma = [](bool def) {
    return FilterParam{"fast_luma", "fp_fast_luma", kCutSlot + 6, def ? 1.0f : 0.0f, 0.0f, 1.0f, 1.0f, ParamFormat::Choice, "no|yes"};
  };
  std::vector<FilterParam> list;
  switch (filter) {
    case Filter::Sharp: list = {{"softness", "fp_softness", 0, 1.0f, 0.5f, 4.0f, 0.25f}}; break;
    case Filter::Soft: list = {{"blur", "fp_blur", 0, 0.0f, 0.0f, 1.0f, 0.05f}, {"ringing", "fp_ringing", 1, 0.5f, 0.0f, 1.0f, 0.05f}}; break;
    case Filter::Epx: list = {{"threshold", "fp_threshold", 0, 0.02f, 0.0f, 0.3f, 0.01f}}; break;
    case Filter::Xbr: list = {{"strength", "fp_strength", 0, 1.0f, 0.0f, 1.0f, 0.05f}, {"edge_softness", "fp_edge_softness", 1, 1.0f, 0.25f, 4.0f, 0.25f}}; break;
    case Filter::Fsr: list = {{"sharpness", "fp_sharpness", 0, 1.0f, 0.0f, 2.0f, 0.1f}}; break;
    // DLSS : netteté RCAS sur l'image agrandie (aucune par défaut, comme le DLSS seul).
    case Filter::Dlss: list = {{"sharpness", "fp_sharpness", 0, 0.0f, 0.0f, 2.0f, 0.1f}}; break;
    case Filter::Crt:
    case Filter::CrtMask: list = {scanlines, glow, {"mask", "fp_mask", 1, 0.0f, 0.0f, 0.6f, 0.05f}, curvature, crtSoftness, vignette}; break;
    case Filter::Lcd: list = {{"grid", "fp_grid", 0, 0.35f, 0.0f, 0.9f, 0.05f}, {"grid_width", "fp_grid_width", 1, 1.0f, 0.5f, 3.0f, 0.25f}, ghost(0.0f)}; break;
    case Filter::Handheld:
      list = {{"colors", "fp_screen_colors", 0, 1.0f, 0.0f, 1.0f, 0.05f},
              {"profile", "fp_screen", 2, 0.0f, 0.0f, 1.0f, 1.0f, ParamFormat::Choice, "fc_gba|fc_gbc"},
              {"grid", "fp_grid", 1, 0.15f, 0.0f, 0.9f, 0.05f}, ghost(0.25f)};
      break;
    case Filter::DotMatrix:
      list = {{"palette", "fp_palette", 0, 0.0f, 0.0f, 2.0f, 1.0f, ParamFormat::Choice, "fc_dmg|fc_pocket|fc_game_colors"},
              {"dots", "fp_dots", 1, 0.5f, 0.0f, 1.0f, 0.05f}, {"dot_gap", "fp_dot_gap", 2, 1.0f, 0.5f, 3.0f, 0.25f}, ghost(0.45f)};
      break;
    case Filter::Cut:
      list = {dynamicBlend, cut(1, "blend_min_contrast", "fp_blend_min_contrast", 0.0f), cut(2, "blend_max_contrast", "fp_blend_max_contrast", 1.0f),
              cut(3, "blend_min_sharpness", "fp_blend_min_sharpness", 0.0f), cut(4, "blend_max_sharpness", "fp_blend_max_sharpness", 1.0f),
              cut(5, "static_sharpness", "fp_static_sharpness", 0.5f), fastLuma(true),
              cut(7, "edge_min_value", "fp_edge_min_value", 0.05f, 0.0f, 1.0f, 0.01f), cut(8, "edge_min_contrast", "fp_edge_min_contrast", 2.0f, 1.0f, 5.0f, 0.1f)};
      break;
    case Filter::Cut2:
    case Filter::Cut3:
      list = {dynamicBlend, cut(1, "blend_min_contrast", "fp_blend_min_contrast", 0.0f), cut(2, "blend_max_contrast", "fp_blend_max_contrast", 0.5f),
              cut(3, "blend_min_sharpness", "fp_blend_min_sharpness", 0.0f), cut(4, "blend_max_sharpness", "fp_blend_max_sharpness", 0.75f),
              cut(5, "static_sharpness", "fp_static_sharpness", 0.75f), fastLuma(false),
              {"soft_edges", "fp_soft_edges", kCutSlot + 7, 1.0f, 0.0f, 1.0f, 1.0f, ParamFormat::Choice, "no|yes"},
              cut(8, "soft_edges_amount", "fp_soft_edges_amount", 1.0f, 0.0f, 2.0f, 0.1f), cut(9, "hard_edges_error", "fp_hard_edges_error", 0.25f)};
      if (filter == Filter::Cut3) list.push_back({"hard_edges_distance", "fp_hard_edges_distance", kCutSlot + 10, 4.0f, 1.0f, 8.0f, 1.0f, ParamFormat::Integer});
      break;
    default: break;
  }
  // Couleurs, communes à tous les filtres (mémorisées pour chacun).
  list.insert(list.end(), {{"brightness", "fp_brightness", 8, 1.0f, 0.5f, 1.5f, 0.05f},
                           {"contrast", "fp_contrast", 9, 1.0f, 0.5f, 1.5f, 0.05f},
                           {"saturation", "fp_saturation", 10, 1.0f, 0.0f, 2.0f, 0.05f},
                           {"gamma", "fp_gamma", 11, 1.0f, 0.5f, 2.0f, 0.05f},
                           {"dedither", "fp_dedither", kDeditherSlot, 0.0f, 0.0f, 1.0f, 0.05f}});
  return list;
}

static const char* kFilterIds[kFilterCount] = {"pixels", "sharp", "soft", "smooth", "epx", "crt", "crtmask", "lcd",
                                               "xbr", "fsr", "cut", "cut2", "cut3", "handheld", "dotmatrix", "dlss"};
static const char* kFilterLabels[kFilterCount] = {"filter_pixels", "filter_sharp", "filter_soft", "filter_smooth", "filter_epx",
                                                  "filter_crt", "filter_crtmask", "filter_lcd", "filter_xbr", "filter_fsr",
                                                  "filter_cut", "filter_cut2", "filter_cut3", "filter_handheld", "filter_dotmatrix",
                                                  "filter_dlss"};
// Ordre du menu : lissages, agrandisseurs (Scale2x, xBR, CUT, FSR, DLSS), puis imitations d'écrans.
static const Filter kFilterOrder[kFilterListCount] = {Filter::Pixels, Filter::Sharp, Filter::Soft, Filter::Smooth, Filter::Epx,
                                                      Filter::Xbr, Filter::Cut, Filter::Cut2, Filter::Cut3, Filter::Fsr,
                                                      Filter::Dlss, Filter::Crt, Filter::Lcd, Filter::Handheld, Filter::DotMatrix};

Filter filterAt(int index) { return kFilterOrder[std::clamp(index, 0, kFilterListCount - 1)]; }

int filterIndex(Filter filter) {
  if (filter == Filter::CrtMask) filter = Filter::Crt;
  for (int i = 0; i < kFilterListCount; i++) {
    if (kFilterOrder[i] == filter) return i;
  }
  return 1;
}

const char* filterLabel(Filter filter) { return kFilterLabels[(int)filter]; }

const char* filterDescription(Filter filter) {
  static const char* keys[kFilterCount] = {"fd_pixels", "fd_sharp", "fd_soft", "fd_smooth", "fd_epx", "fd_crt", "fd_crtmask", "fd_lcd",
                                           "fd_xbr", "fd_fsr", "fd_cut", "fd_cut2", "fd_cut3", "fd_handheld", "fd_dotmatrix", "fd_dlss"};
  return keys[(int)filter];
}

const char* filterId(Filter filter) { return kFilterIds[(int)filter]; }

static const char* kAspectIds[kAspectCount] = {"core", "4:3", "16:9", "stretch"};

Aspect nextAspect(Aspect aspect) { return (Aspect)(((int)aspect + 1) % kAspectCount); }

const char* aspectId(Aspect aspect) { return kAspectIds[(int)aspect]; }

Aspect aspectFromId(const std::string& id) {
  for (int i = 0; i < kAspectCount; i++) {
    if (id == kAspectIds[i]) return (Aspect)i;
  }
  return Aspect::Core;
}

Filter filterFromId(const std::string& id) {
  if (id == "crtmask") return Filter::Crt;  // ancien « CRT+ » : CRT, masque réglable
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

/** Programme de deux shaders, attributs [attributes] aux emplacements 0 et 1 ; 0 en cas d'erreur. */
static GLuint link(const char* vertex, const char* fragment, std::initializer_list<const char*> attributes) {
  GLuint vs = compile(GL_VERTEX_SHADER, vertex);
  GLuint fs = compile(GL_FRAGMENT_SHADER, fragment);
  GLuint program = p_glCreateProgram();
  p_glAttachShader(program, vs);
  p_glAttachShader(program, fs);
  GLuint location = 0;
  for (const char* name : attributes) p_glBindAttribLocation(program, location++, name);
  p_glLinkProgram(program);
  p_glDeleteShader(vs);
  p_glDeleteShader(fs);
  GLint ok = 0;
  p_glGetProgramiv(program, GL_LINK_STATUS, &ok);
  if (!ok) {
    char log[1024];
    p_glGetProgramInfoLog(program, sizeof(log), nullptr, log);
    logf("Programme : %s", log);
    p_glDeleteProgram(program);
    return 0;
  }
  return program;
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
  uniformParams_ = p_glGetUniformLocation(program_, "u_params");
  uniformParams2_ = p_glGetUniformLocation(program_, "u_params2");
  uniformColor_ = p_glGetUniformLocation(program_, "u_color");
  uniformRect_ = p_glGetUniformLocation(program_, "u_rect");
  if (p_glGenVertexArrays) p_glGenVertexArrays(1, &vao_);
  p_glGenBuffers(1, &vbo_);
  // Passe préalable : facultative (sans elle, ni lissage du tramage, ni rémanence, ni CUT).
  prepProgram_ = link(coreProfile_ ? kVertex150 : kVertex120, coreProfile_ ? kPrep150 : kPrep120, {"a_pos", "a_uv"});
  if (prepProgram_) {
    prepUniformSrc_ = p_glGetUniformLocation(prepProgram_, "u_src");
    prepUniformHist_ = p_glGetUniformLocation(prepProgram_, "u_hist");
    prepUniformRect_ = p_glGetUniformLocation(prepProgram_, "u_rect");
    prepUniformSize_ = p_glGetUniformLocation(prepProgram_, "u_size");
    prepUniformParams_ = p_glGetUniformLocation(prepProgram_, "u_params");
  }
  return true;
}

// ---------------------------------------------------------------------------
// Passe préalable (tramage, rémanence) et CUT
// ---------------------------------------------------------------------------

/** Texture RGBA8 à la taille donnée et son framebuffer, filtrage au plus proche ; faux si incomplet. */
static bool makeTarget(unsigned& fbo, unsigned& texture, int width, int height) {
  if (!fbo) {
    p_glGenFramebuffers(1, &fbo);
    glGenTextures(1, &texture);
  }
  glBindTexture(GL_TEXTURE_2D, texture);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  p_glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  p_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
  const bool ok = p_glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  glClearColor(0, 0, 0, 1);
  glClear(GL_COLOR_BUFFER_BIT);
  p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
  return ok;
}

bool Video::runPrep(unsigned source, float u1, float vTop, float vBottom, float texW, float texH) {
  if (!prepProgram_) return false;
  const int width = (int)frameWidth_, height = (int)frameHeight_;
  if (width != prepWidth_ || height != prepHeight_) {
    prepWidth_ = width;
    prepHeight_ = height;
    prepOk_ = makeTarget(prepFbo_[0], prepTexture_[0], width, height) && makeTarget(prepFbo_[1], prepTexture_[1], width, height);
    if (!prepOk_) logf("Passe préalable incomplète (%dx%d)", width, height);
    prepFrames_ = 0;
    prepDirty_ = true;
  }
  if (!prepOk_) return false;
  if (!prepDirty_ && prepSerial_ == frameSerial_) return true;  // image déjà traitée (redessinée)
  // Rémanence : seulement pour une nouvelle image (pas en changeant un réglage, image figée).
  const bool newFrame = prepSerial_ != frameSerial_;
  const float ghost = filter_ == Filter::Lcd || filter_ == Filter::Handheld || filter_ == Filter::DotMatrix ? params_[kGhostSlot] : 0.0f;
  const int out = newFrame ? 1 - prepOut_ : prepOut_;
  const int history = 1 - out;
  p_glBindFramebuffer(GL_FRAMEBUFFER, prepFbo_[out]);
  glViewport(0, 0, width, height);
  p_glUseProgram(prepProgram_);
  p_glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, prepTexture_[history]);
  p_glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, source);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  p_glUniform1i(prepUniformSrc_, 0);
  p_glUniform1i(prepUniformHist_, 1);
  p_glUniform4f(prepUniformRect_, u1, vTop, vBottom, 0.0f);
  p_glUniform2f(prepUniformSize_, texW, texH);
  // Image précédente dans [history] : après une image traitée (nouvelle image), après deux (réglage
  // changé, image figée : refaite à sa place, depuis l'image d'avant).
  const bool hasHistory = prepFrames_ >= (newFrame ? 1 : 2);
  p_glUniform4f(prepUniformParams_, hasHistory ? ghost : 0.0f, params_[kDeditherSlot], 0.0f, 0.0f);
  const Vertex full[4] = {{-1, 1, 0, 1}, {-1, -1, 0, 0}, {1, 1, 1, 1}, {1, -1, 1, 0}};
  p_glBufferData(GL_ARRAY_BUFFER, sizeof(full), full, GL_STREAM_DRAW);
  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  p_glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, 0);
  p_glActiveTexture(GL_TEXTURE0);
  p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
  prepOut_ = out;
  prepSerial_ = frameSerial_;
  if (newFrame) prepFrames_++;
  prepDirty_ = false;
  return true;
}

/** Nom du #define de LibretroDroid d'un réglage de CUT ; entier : interrupteur ou distance. */
static const char* cutDefine(const std::string& id, bool& integer) {
  static const struct {
    const char* id;
    const char* define;
    bool integer;
  } kDefines[] = {
      {"dynamic_blend", "USE_DYNAMIC_BLEND", true},
      {"blend_min_contrast", "BLEND_MIN_CONTRAST_EDGE", false},
      {"blend_max_contrast", "BLEND_MAX_CONTRAST_EDGE", false},
      {"blend_min_sharpness", "BLEND_MIN_SHARPNESS", false},
      {"blend_max_sharpness", "BLEND_MAX_SHARPNESS", false},
      {"static_sharpness", "STATIC_BLEND_SHARPNESS", false},
      {"fast_luma", "EDGE_USE_FAST_LUMA", true},
      {"edge_min_value", "EDGE_MIN_VALUE", false},
      {"edge_min_contrast", "EDGE_MIN_CONTRAST", false},
      {"soft_edges", "SOFT_EDGES_SHARPENING", true},
      {"soft_edges_amount", "SOFT_EDGES_SHARPENING_AMOUNT", false},
      {"hard_edges_error", "HARD_EDGES_SEARCH_MAX_ERROR", false},
      {"hard_edges_distance", "HARD_EDGES_SEARCH_MAX_DISTANCE", true},
  };
  for (const auto& d : kDefines) {
    if (id == d.id) {
      integer = d.integer;
      return d.define;
    }
  }
  return nullptr;
}

/**
 * Shader de LibretroDroid (GLSL ES 1.0) adapté à OpenGL : sans précisions (lowp…), uniforme
 * « texture » renommée (fonction de GLSL 1.50), entrées, sorties et texture2D de GLSL 1.50 en
 * profil core.
 */
static std::string desktopShader(const std::string& source, bool fragment, bool core) {
  std::string body = std::regex_replace(source, std::regex(R"(^[ \t]*precision[^;]*;)", std::regex::multiline), "");
  body = std::regex_replace(body, std::regex(R"(\btexture\b)"), "rc_texture");
  std::string head = core ? "#version 150\n" : "#version 120\n";
  head += "#define lowp\n#define mediump\n#define highp\n";
  if (core && fragment) head += "#define varying in\n#define texture2D texture\nout vec4 rc_FragColor;\n#define gl_FragColor rc_FragColor\n";
  if (core && !fragment) head += "#define attribute in\n#define varying out\n";
  return head + body;
}

bool Video::prepareCut() {
  // Réglages donnés au shader par des #define : programmes recréés quand ils changent.
  std::unordered_map<std::string, std::string> defines;
  std::string key = filterId(filter_);
  for (const auto& p : filterParams(filter_)) {
    bool integer = false;
    const char* define = p.slot >= kCutSlot ? cutDefine(p.id, integer) : nullptr;
    if (!define) continue;
    char value[32];
    if (integer) snprintf(value, sizeof(value), "%d", (int)std::lround(params_[p.slot]));
    else snprintf(value, sizeof(value), "%.4f", params_[p.slot]);
    defines[define] = value;
    key += std::string("|") + define + "=" + value;
  }
  // Couleurs de l'image finale, ajoutées au dernier passage par LibretroDroid.
  static const char* kColors[] = {"RC_BRIGHTNESS", "RC_CONTRAST", "RC_SATURATION", "RC_GAMMA"};
  for (int i = 0; i < 4; i++) {
    char value[32];
    snprintf(value, sizeof(value), "%.4f", params_[8 + i]);
    defines[kColors[i]] = value;
    key += std::string("|") + kColors[i] + "=" + value;
  }
  if (key == cutKey_) return !cutPasses_.empty();
  deleteCut();
  cutKey_ = key;
  using libretrodroid::ShaderManager;
  const ShaderManager::Type type = filter_ == Filter::Cut    ? ShaderManager::Type::SHADER_UPSCALE_CUT
                                   : filter_ == Filter::Cut2 ? ShaderManager::Type::SHADER_UPSCALE_CUT2
                                                             : ShaderManager::Type::SHADER_UPSCALE_CUT3;
  const ShaderManager::Chain chain = ShaderManager::getShader({type, defines});
  for (const auto& pass : chain.passes) {
    CutPass cut;
    cut.program = link(desktopShader(pass.vertex, false, coreProfile_).c_str(), desktopShader(pass.fragment, true, coreProfile_).c_str(),
                       {"vPosition", "vCoordinate"});
    if (!cut.program) {
      logf("Shader %s : passe %d non compilée", filterId(filter_), (int)cutPasses_.size());
      deleteCut();
      return false;
    }
    cut.texture = p_glGetUniformLocation(cut.program, "rc_texture");
    cut.previous = p_glGetUniformLocation(cut.program, "previousPass");
    cut.size = p_glGetUniformLocation(cut.program, "textureSize");
    cut.density = p_glGetUniformLocation(cut.program, "screenDensity");
    cutPasses_.push_back(cut);
  }
  return !cutPasses_.empty();
}

void Video::deleteCut() {
  for (const auto& pass : cutPasses_) p_glDeleteProgram(pass.program);
  cutPasses_.clear();
  cutKey_.clear();
}

void Video::drawCut(const void* screenQuad, float screenDensity) {
  const int width = (int)frameWidth_, height = (int)frameHeight_;
  // Passes intermédiaires à la taille du jeu (comme LibretroDroid).
  const size_t intermediate = cutPasses_.size() - 1;
  if (cutFbo_.size() != intermediate || width != cutWidth_ || height != cutHeight_) {
    for (size_t i = 0; i < cutFbo_.size(); i++) {
      p_glDeleteFramebuffers(1, &cutFbo_[i]);
      glDeleteTextures(1, &cutTexture_[i]);
    }
    cutFbo_.assign(intermediate, 0);
    cutTexture_.assign(intermediate, 0);
    for (size_t i = 0; i < intermediate; i++) makeTarget(cutFbo_[i], cutTexture_[i], width, height);
    cutWidth_ = width;
    cutHeight_ = height;
  }
  int winW, winH;
  SDL_GetWindowSizeInPixels(window_, &winW, &winH);
  const Vertex full[4] = {{-1, 1, 0, 1}, {-1, -1, 0, 0}, {1, 1, 1, 1}, {1, -1, 1, 0}};
  for (size_t i = 0; i < cutPasses_.size(); i++) {
    const CutPass& pass = cutPasses_[i];
    const bool last = i + 1 == cutPasses_.size();
    p_glBindFramebuffer(GL_FRAMEBUFFER, last ? 0 : cutFbo_[i]);
    if (last) glViewport(0, 0, winW, winH);
    else glViewport(0, 0, width, height);
    p_glUseProgram(pass.program);
    p_glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, prepTexture_[prepOut_]);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    p_glUniform1i(pass.texture, 0);
    if (pass.previous >= 0 && i > 0) {
      p_glActiveTexture(GL_TEXTURE1);
      glBindTexture(GL_TEXTURE_2D, cutTexture_[i - 1]);
      p_glUniform1i(pass.previous, 1);
      p_glActiveTexture(GL_TEXTURE0);
    }
    p_glUniform2f(pass.size, (float)width, (float)height);
    p_glUniform1f(pass.density, screenDensity);
    p_glBufferData(GL_ARRAY_BUFFER, sizeof(Vertex) * 4, last ? screenQuad : full, GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  }
  p_glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D, 0);
  p_glActiveTexture(GL_TEXTURE0);
  p_glUseProgram(program_);
}

// ---------------------------------------------------------------------------
// Images du cœur
// ---------------------------------------------------------------------------

void Video::onFrame(const void* data, unsigned width, unsigned height, size_t pitch) {
  if (!data) return;  // image identique à la précédente
  frameWidth_ = width;
  frameHeight_ = height;
  hasFrame_ = true;
  frameSerial_++;
  if (data == RETRO_HW_FRAME_BUFFER_VALID && g.vulkan) {
    // Cœur Vulkan : image partagée avec OpenGL si possible (sans passer par le processeur), sinon
    // recopiée en mémoire ; affichée comme celle d'un cœur logiciel.
    if (!interopTried_) setupInterop();
    if (interopOk_ && showShared(width, height)) {
      frameIsHw_ = false;
      return;
    }
    sharedActive_ = false;
    const uint8_t* pixels;
    size_t vkPitch;
    bool bgra;
    if (!vulkan::readFrame(width, height, pixels, vkPitch, bgra)) return;
    frameIsHw_ = false;
    p_glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, frameTexture_);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, (GLint)(vkPitch / 4));
    glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
    glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
    const GLenum format = bgra ? GL_BGRA : GL_RGBA;
    if ((int)width != textureWidth_ || (int)height != textureHeight_ || textureFormat_ != GL_RGBA8) {
      textureWidth_ = (int)width;
      textureHeight_ = (int)height;
      textureFormat_ = GL_RGBA8;
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei)width, (GLsizei)height, 0, format, GL_UNSIGNED_BYTE, pixels);
    } else {
      glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, (GLsizei)width, (GLsizei)height, format, GL_UNSIGNED_BYTE, pixels);
    }
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    return;
  }
  if (data == RETRO_HW_FRAME_BUFFER_VALID) {
    frameIsHw_ = true;
    return;
  }
  frameIsHw_ = false;
  // Le cœur a pu laisser un tampon de pixels ou un alignement : on les neutralise.
  p_glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
  glBindTexture(GL_TEXTURE_2D, frameTexture_);
  // Format interne identique à celui du cœur : copie directe, sans conversion par le pilote
  // (alpha ignoré à l'affichage et dans les captures).
  GLenum format, type;
  GLint internal;
  int bytes;
  switch (g.pixelFormat) {
    case RETRO_PIXEL_FORMAT_XRGB8888: format = GL_BGRA; type = GL_UNSIGNED_INT_8_8_8_8_REV; internal = GL_RGBA8; bytes = 4; break;
    case RETRO_PIXEL_FORMAT_RGB565:
      format = GL_RGB;
      type = GL_UNSIGNED_SHORT_5_6_5;
      internal = rgb565_ ? kRgb565 : GL_RGB8;
      bytes = 2;
      break;
    default: format = GL_BGRA; type = GL_UNSIGNED_SHORT_1_5_5_5_REV; internal = GL_RGB5_A1; bytes = 2; break;
  }
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
  glPixelStorei(GL_UNPACK_ROW_LENGTH, (GLint)(pitch / bytes));
  glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
  glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
  if ((int)width != textureWidth_ || (int)height != textureHeight_ || textureFormat_ != internal) {
    textureWidth_ = (int)width;
    textureHeight_ = (int)height;
    textureFormat_ = internal;
    glTexImage2D(GL_TEXTURE_2D, 0, internal, (GLsizei)width, (GLsizei)height, 0, format, type, data);
  } else {
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, (GLsizei)width, (GLsizei)height, format, type, data);
  }
  glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
}

// ---------------------------------------------------------------------------
// Image des cœurs Vulkan partagée avec OpenGL
// ---------------------------------------------------------------------------

/** Partage possible : extensions du pilote OpenGL et même carte graphique que Vulkan. */
void Video::setupInterop() {
  interopTried_ = true;
  const bool extensions = SDL_GL_ExtensionSupported("GL_EXT_memory_object") && SDL_GL_ExtensionSupported("GL_EXT_memory_object_win32") &&
                          SDL_GL_ExtensionSupported("GL_EXT_semaphore") && SDL_GL_ExtensionSupported("GL_EXT_semaphore_win32");
  const bool functions = extensions && loadGl(glCreateMemoryObjects, "glCreateMemoryObjectsEXT") &&
                         loadGl(glDeleteMemoryObjects, "glDeleteMemoryObjectsEXT") &&
                         loadGl(glMemoryObjectParameteriv, "glMemoryObjectParameterivEXT") &&
                         loadGl(glImportMemoryWin32Handle, "glImportMemoryWin32HandleEXT") && loadGl(glTexStorageMem2D, "glTexStorageMem2DEXT") &&
                         loadGl(glGenSemaphores, "glGenSemaphoresEXT") && loadGl(glDeleteSemaphores, "glDeleteSemaphoresEXT") &&
                         loadGl(glImportSemaphoreWin32Handle, "glImportSemaphoreWin32HandleEXT") &&
                         loadGl(glWaitSemaphore, "glWaitSemaphoreEXT") && loadGl(glSignalSemaphore, "glSignalSemaphoreEXT") &&
                         loadGl(glGetUnsignedBytei, "glGetUnsignedBytei_vEXT");
  bool sameGpu = false;
  if (functions) {
    GLint count = 0;
    glGetIntegerv(kNumDeviceUuids, &count);
    for (GLint i = 0; i < count && !sameGpu; i++) {
      GLubyte uuid[16] = {};
      glGetUnsignedBytei(kDeviceUuid, (GLuint)i, uuid);
      sameGpu = vulkan::interopSupported(uuid);
    }
  }
  interopOk_ = sameGpu;
  logf("Vulkan : image %s", interopOk_ ? "partagée avec OpenGL (sans recopie)"
                           : !extensions ? "recopiée (pilote OpenGL sans partage avec Vulkan)"
                           : !functions  ? "recopiée (fonctions de partage absentes)"
                                         : "recopiée (Vulkan et OpenGL sur deux cartes, ou extensions Vulkan absentes)");
}

/** Image précédente rendue à Vulkan (OpenGL a fini de la lire). */
void Video::releaseShared() {
  if (!releaseOwed_) return;
  const GLenum layout = kLayoutShaderReadOnly;
  glSignalSemaphore(releasedSemaphore_, 0, nullptr, 1, &sharedTexture_, &layout);
  glFlush();
  vulkan::sharedReleased();
  releaseOwed_ = false;
}

void Video::dropShared() {
  if (sharedTexture_) glDeleteTextures(1, &sharedTexture_);
  if (sharedMemory_ && glDeleteMemoryObjects) glDeleteMemoryObjects(1, &sharedMemory_);
  if (readySemaphore_ && glDeleteSemaphores) glDeleteSemaphores(1, &readySemaphore_);
  if (releasedSemaphore_ && glDeleteSemaphores) glDeleteSemaphores(1, &releasedSemaphore_);
  sharedTexture_ = sharedMemory_ = readySemaphore_ = releasedSemaphore_ = 0;
  sharedActive_ = releaseOwed_ = false;
  sharedWidth_ = sharedHeight_ = 0;
}

/** Image du cœur copiée par Vulkan dans l'image partagée, puis attendue par OpenGL ; faux : recopie. */
bool Video::showShared(unsigned width, unsigned height) {
  releaseShared();
  vulkan::SharedFrame frame;
  auto fail = [&](const char* why) {
    for (void* handle : {frame.memory, frame.ready, frame.released}) {
      if (handle) CloseHandle(handle);
    }
    logf("Vulkan : partage avec OpenGL arrêté (%s), image recopiée", why);
    dropShared();
    vulkan::stopSharing();
    interopOk_ = false;
    return false;
  };
  if (!vulkan::shareFrame(width, height, frame)) return fail("copie impossible");
  while (glGetError() != GL_NO_ERROR) {
  }
  if (frame.ready) {
    glGenSemaphores(1, &readySemaphore_);
    glGenSemaphores(1, &releasedSemaphore_);
    glImportSemaphoreWin32Handle(readySemaphore_, kHandleOpaqueWin32, frame.ready);
    glImportSemaphoreWin32Handle(releasedSemaphore_, kHandleOpaqueWin32, frame.released);
    // Pilote OpenGL à fil séparé (NVIDIA) : import réellement fait avant de fermer les handles.
    glFinish();
    CloseHandle(frame.ready);
    CloseHandle(frame.released);
    frame.ready = frame.released = nullptr;
  }
  if (frame.memory) {
    // Nouvelle image (première, ou taille changée) : mémoire importée, texture créée dessus.
    if (sharedTexture_) glDeleteTextures(1, &sharedTexture_);
    if (sharedMemory_) glDeleteMemoryObjects(1, &sharedMemory_);
    glCreateMemoryObjects(1, &sharedMemory_);
    const GLint dedicated = GL_TRUE;
    glMemoryObjectParameteriv(sharedMemory_, kDedicatedMemoryObject, &dedicated);
    glImportMemoryWin32Handle(sharedMemory_, frame.memorySize, kHandleOpaqueWin32, frame.memory);
    glFinish();
    CloseHandle(frame.memory);
    frame.memory = nullptr;
    glGenTextures(1, &sharedTexture_);
    glBindTexture(GL_TEXTURE_2D, sharedTexture_);
    glTexStorageMem2D(GL_TEXTURE_2D, 1, GL_RGBA8, (GLsizei)frame.width, (GLsizei)frame.height, sharedMemory_, 0);
    if (const GLenum error = glGetError(); error != GL_NO_ERROR) {
      logf("Vulkan : image partagée %ux%u refusée par OpenGL (0x%x)", frame.width, frame.height, (unsigned)error);
      return fail("import refusé par OpenGL");
    }
    sharedWidth_ = (int)frame.width;
    sharedHeight_ = (int)frame.height;
    sharedBgra_ = !frame.bgra;  // forcé à l'appel suivant
  }
  if (frame.bgra != sharedBgra_) {
    glBindTexture(GL_TEXTURE_2D, sharedTexture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_R, frame.bgra ? GL_BLUE : GL_RED);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_B, frame.bgra ? GL_RED : GL_BLUE);
    sharedBgra_ = frame.bgra;
  }
  const GLenum layout = kLayoutShaderReadOnly;
  glWaitSemaphore(readySemaphore_, 0, nullptr, 1, &sharedTexture_, &layout);
  if (const GLenum error = glGetError(); error != GL_NO_ERROR) {
    logf("Vulkan : erreur OpenGL 0x%x pendant le partage", (unsigned)error);
    return fail("import refusé par OpenGL");
  }
  releaseOwed_ = true;
  sharedActive_ = true;
  return true;
}

// ---------------------------------------------------------------------------
// Affichage
// ---------------------------------------------------------------------------

bool Video::drawDlss(unsigned source, float u1, float vTop, float vBottom, float x1, float y1, float w, float h, int winW, int winH) {
  // Taille de sortie dans le sens de l'image (écran tourné : côtés échangés), au plus 3x (mode Ultra
  // Performance du DLSS) ; le reste de l'agrandissement est bilinéaire.
  const bool turned = g.rotation & 1;
  const float imageW = turned ? h : w, imageH = turned ? w : h;
  const float fit = std::min({1.0f, 3.0f * frameWidth_ / imageW, 3.0f * frameHeight_ / imageH});
  const int outW = (int)(imageW * fit + 0.5f), outH = (int)(imageH * fit + 0.5f);
  // Rien à agrandir, ou image de démarrage minuscule (1x1 de certains cœurs) : FSR.
  if (outW <= (int)frameWidth_ || outH <= (int)frameHeight_ || frameWidth_ < 64 || frameHeight_ < 64) return false;
  if (!dlss_.resize((int)frameWidth_, (int)frameHeight_, outW, outH) || !dlss_.lock(true)) return false;
  if (!dlssFbo_) p_glGenFramebuffers(1, &dlssFbo_);

  // Image du jeu recopiée telle quelle (pixels, couleurs d'origine) dans l'image d'entrée du DLSS.
  auto drawQuad = [](const Vertex quad[4]) {
    p_glBufferData(GL_ARRAY_BUFFER, sizeof(Vertex) * 4, quad, GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  };
  p_glBindFramebuffer(GL_FRAMEBUFFER, dlssFbo_);
  p_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, dlss_.inputTexture(), 0);
  glViewport(0, 0, (GLsizei)frameWidth_, (GLsizei)frameHeight_);
  p_glUniform1i(uniformMode_, (int)Filter::Pixels);
  p_glUniform4f(uniformColor_, 1.0f, 1.0f, 1.0f, 1.0f);
  const Vertex copy[4] = {{-1, 1, 0, vTop}, {-1, -1, 0, vBottom}, {1, 1, u1, vTop}, {1, -1, u1, vBottom}};
  drawQuad(copy);
  p_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
  p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
  dlss_.unlock(true);
  glViewport(0, 0, winW, winH);
  p_glUniform4f(uniformColor_, params_[8], params_[9], params_[10], params_[11]);
  if (!dlss_.evaluate() || !dlss_.lock(false)) {
    // État rétabli pour FSR à la place.
    p_glUniform1i(uniformMode_, (int)Filter::Dlss);
    glBindTexture(GL_TEXTURE_2D, source);
    return false;
  }

  // Image agrandie dessinée à l'écran (haut de l'image en v = 1 : rendue par OpenGL), netteté RCAS
  // (réglage à 0 : aucune), couleurs réglées ; rotation comme pour l'image du cœur.
  glBindTexture(GL_TEXTURE_2D, dlss_.outputTexture());
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  p_glUniform1i(uniformMode_, 20);
  p_glUniform2f(uniformSize_, (float)outW, (float)outH);
  const float uv[4][2] = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
  const float pos[4][2] = {{-x1, y1}, {x1, y1}, {x1, -y1}, {-x1, -y1}};
  const int k = (int)g.rotation;
  auto corner = [&](int screen) {
    const float* t = uv[(screen + k) % 4];
    return Vertex{pos[screen][0], pos[screen][1], t[0], t[1]};
  };
  const Vertex out[4] = {corner(0), corner(3), corner(1), corner(2)};
  drawQuad(out);
  dlss_.unlock(false);
  return true;
}

void Video::present(const uint8_t* overlay, int overlayWidth, int overlayHeight, bool swap, bool overlayChanged) {
  // Rétabli avant l'échange des tampons (fin de la portée).
  {
    GLStateGuard keep;
    draw(overlay, overlayWidth, overlayHeight, overlayChanged);
  }
  if (swap) SDL_GL_SwapWindow(window_);
}

void Video::draw(const uint8_t* overlay, int overlayWidth, int overlayHeight, bool overlayChanged) {
  int winW, winH;
  SDL_GetWindowSizeInPixels(window_, &winW, &winH);

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
    // Rapport d'aspect du cœur (sinon celui de l'image) ou format choisi, inversé pour les écrans
    // tournés ; « étiré » : toute la fenêtre.
    const retro_game_geometry& geo = g.av.geometry;
    float aspect = geo.aspect_ratio > 0 ? geo.aspect_ratio
                   : (float)(geo.base_width ? geo.base_width : frameWidth_) / (float)(geo.base_height ? geo.base_height : frameHeight_);
    if (aspect_ == Aspect::Ratio4_3) aspect = 4.0f / 3.0f;
    if (aspect_ == Aspect::Ratio16_9) aspect = 16.0f / 9.0f;
    if (g.rotation & 1) aspect = 1.0f / aspect;
    if (aspect_ == Aspect::Stretch && winH > 0) aspect = (float)winW / (float)winH;
    float w = (float)winW, h = w / aspect;
    if (h > winH) {
      h = (float)winH;
      w = h * aspect;
    }
    float x1 = w / winW, y1 = h / winH;  // demi-taille en coordonnées normalisées

    float u1, v1, vTop, vBottom;
    float texW, texH;
    unsigned source;
    if (frameIsHw_) {
      source = fboTexture_;
      texW = (float)fboWidth_;
      texH = (float)fboHeight_;
      u1 = (float)frameWidth_ / fboWidth_;
      v1 = (float)frameHeight_ / fboHeight_;
      bool bottomLeft = g.hw.bottom_left_origin;
      vTop = bottomLeft ? v1 : 0.0f;
      vBottom = bottomLeft ? 0.0f : v1;
    } else {
      source = sharedActive_ ? sharedTexture_ : frameTexture_;
      texW = (float)(sharedActive_ ? sharedWidth_ : textureWidth_);
      texH = (float)(sharedActive_ ? sharedHeight_ : textureHeight_);
      u1 = (float)frameWidth_ / texW;
      v1 = (float)frameHeight_ / texH;
      vTop = 0.0f;
      vBottom = v1;
    }
    // DLSS prêt, sinon FSR à sa place (téléchargement en cours, pas de carte GeForce RTX).
    const bool dlss = filter_ == Filter::Dlss && dlss_.prepare() == Dlss::State::Ready;
    const Filter shown = filter_ == Filter::Dlss && !dlss ? Filter::Fsr : filter_;
    // Passe préalable (lissage du tramage, rémanence, CUT) : image à la taille exacte du jeu, haut en
    // v = 0, à la place de celle du cœur.
    const bool cut = shown == Filter::Cut || shown == Filter::Cut2 || shown == Filter::Cut3;
    const bool lcd = shown == Filter::Lcd || shown == Filter::Handheld || shown == Filter::DotMatrix;
    bool prepared = false;
    if ((cut || params_[kDeditherSlot] > 0.0f || (lcd && params_[kGhostSlot] > 0.0f)) &&
        runPrep(source, u1, vTop, vBottom, texW, texH)) {
      prepared = true;
      source = prepTexture_[prepOut_];
      texW = (float)frameWidth_;
      texH = (float)frameHeight_;
      u1 = 1.0f;
      vTop = 0.0f;
      vBottom = 1.0f;
      p_glUseProgram(program_);
      glViewport(0, 0, winW, winH);
    }
    glBindTexture(GL_TEXTURE_2D, source);
    // Pixels de l'écran par pixel de l'image (axes de l'image échangés par un quart de tour).
    float scaleX = ((g.rotation & 1) ? h : w) / frameWidth_, scaleY = ((g.rotation & 1) ? w : h) / frameHeight_;
    p_glUniform1i(uniformMode_, (int)shown);
    p_glUniform2f(uniformSize_, texW, texH);
    p_glUniform2f(uniformScale_, scaleX, scaleY);
    // Réglages du filtre ; emplacement de l'image dans la fenêtre (pixels, origine en bas) : courbure.
    p_glUniform4f(uniformParams_, params_[0], params_[1], params_[2], params_[3]);
    p_glUniform4f(uniformParams2_, params_[4], params_[5], params_[6], params_[7]);
    p_glUniform4f(uniformColor_, params_[8], params_[9], params_[10], params_[11]);
    p_glUniform4f(uniformRect_, (winW - w) / 2.0f, (winH - h) / 2.0f, w, h);
    // Échantillons au centre des pixels (bicubique, EPX, LCD, pixels nets) : filtrage au plus proche.
    bool nearest = shown == Filter::Pixels || shown == Filter::Soft || shown == Filter::Epx || shown == Filter::Lcd || shown == Filter::Xbr ||
                   shown == Filter::Fsr || shown == Filter::Dlss || shown == Filter::Handheld || shown == Filter::DotMatrix;
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
    if (cut && prepared && prepareCut()) {
      // CUT, CUT2, CUT3 : passes de LibretroDroid, la dernière dans le rectangle de l'image.
      drawCut(quad, std::min(scaleX, scaleY));
    } else if (dlss && drawDlss(source, u1, vTop, vBottom, x1, y1, w, h, winW, winH)) {
      // DLSS dessiné ; sinon (image non agrandie, erreur) FSR ci-dessous.
    } else if ((shown == Filter::Fsr || shown == Filter::Dlss) && preparePostTarget(outW, outH)) {
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
      p_glUniform1i(uniformMode_, 20);
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
    // Texture créée à la première image ou au changement de taille, ensuite seulement mise à jour
    // quand la surimpression a changé.
    if (overlayWidth != overlayTextureWidth_ || overlayHeight != overlayTextureHeight_) {
      overlayTextureWidth_ = overlayWidth;
      overlayTextureHeight_ = overlayHeight;
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, overlayWidth, overlayHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, overlay);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    } else if (overlayChanged) {
      glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, overlayWidth, overlayHeight, GL_RGBA, GL_UNSIGNED_BYTE, overlay);
    }
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
    p_glUniform1i(uniformMode_, (int)Filter::Smooth);  // menu : sans effet, couleurs d'origine
    p_glUniform4f(uniformColor_, 1.0f, 1.0f, 1.0f, 1.0f);
    p_glUniform1f(uniformAlpha_, 1.0f);
    drawQuad(quad);
    glDisable(GL_BLEND);
  }
}

bool Video::saveWindow(const std::string& path) {
  GLStateGuard keep;
  int w, h;
  SDL_GetWindowSizeInPixels(window_, &w, &h);
  std::vector<uint8_t> pixels((size_t)w * h * 4);
  p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
  p_glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
  glPixelStorei(GL_PACK_ALIGNMENT, 4);
  glReadBuffer(GL_BACK);
  glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
  for (int y = 0; y < h / 2; y++) std::swap_ranges(&pixels[(size_t)y * w * 4], &pixels[(size_t)(y + 1) * w * 4], &pixels[(size_t)(h - 1 - y) * w * 4]);
  SDL_Surface* surface = SDL_CreateSurfaceFrom(w, h, SDL_PIXELFORMAT_RGBA32, pixels.data(), w * 4);
  if (!surface) return false;
  bool ok = SDL_SaveBMP(surface, path.c_str());
  SDL_DestroySurface(surface);
  return ok;
}
