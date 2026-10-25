// Fonctions OpenGL au-delà de l'OpenGL 1.1 (opengl32.dll n'exporte que celles-là) : chargées à
// l'exécution par video.cpp (Video::loadFunctions), utilisées aussi par shaderchain.cpp.
#pragma once

#include <SDL3/SDL_opengl.h>

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
  X(PFNGLUNIFORMMATRIX4FVPROC, glUniformMatrix4fv)                              \
  X(PFNGLVERTEXATTRIBPOINTERPROC, glVertexAttribPointer)                        \
  X(PFNGLENABLEVERTEXATTRIBARRAYPROC, glEnableVertexAttribArray)                \
  X(PFNGLDISABLEVERTEXATTRIBARRAYPROC, glDisableVertexAttribArray)              \
  X(PFNGLGENFRAMEBUFFERSPROC, glGenFramebuffers)                                \
  X(PFNGLBINDFRAMEBUFFERPROC, glBindFramebuffer)                                \
  X(PFNGLFRAMEBUFFERTEXTURE2DPROC, glFramebufferTexture2D)                      \
  X(PFNGLCHECKFRAMEBUFFERSTATUSPROC, glCheckFramebufferStatus)                  \
  X(PFNGLDELETEFRAMEBUFFERSPROC, glDeleteFramebuffers)                          \
  X(PFNGLGENRENDERBUFFERSPROC, glGenRenderbuffers)                              \
  X(PFNGLBINDRENDERBUFFERPROC, glBindRenderbuffer)                              \
  X(PFNGLRENDERBUFFERSTORAGEPROC, glRenderbufferStorage)                        \
  X(PFNGLFRAMEBUFFERRENDERBUFFERPROC, glFramebufferRenderbuffer)                \
  X(PFNGLDELETERENDERBUFFERSPROC, glDeleteRenderbuffers)                        \
  X(PFNGLBLENDFUNCSEPARATEPROC, glBlendFuncSeparate)

// Facultatives (absentes des contextes OpenGL 2.1).
#define GL_OPTIONAL_FUNCTIONS(X)                                                \
  X(PFNGLGENVERTEXARRAYSPROC, glGenVertexArrays)                                \
  X(PFNGLBINDVERTEXARRAYPROC, glBindVertexArray)                                \
  X(PFNGLBINDSAMPLERPROC, glBindSampler)

#define GL_DECLARE_EXTERN(type, name) extern type p_##name;
GL_FUNCTIONS(GL_DECLARE_EXTERN)
GL_OPTIONAL_FUNCTIONS(GL_DECLARE_EXTERN)
#undef GL_DECLARE_EXTERN
