#include "shaderchain.h"

#include <windows.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <map>

#include "glfunctions.h"
#include "util.h"

// Préréglages de xBR (shaders/xbr, licence MIT), du plus simple au plus lourd ; variantes « 3D » : pour
// les jeux 3D rendus en 2x, 4x ou 8x (XBR_RES : blocs de pixels lissés comme des pixels). Dernière
// passe absente (custom-jinc2-sharper, GPL ; stock) : image finale agrandie par le moteur (bicubique).
static const struct {
  const char* file;
  const char* name;
} kXbrPresets[] = {
    {"xbr/xbr-lv2.glslp", "xBR lv2"},
    {"xbr/xbr-lv2-noblend.glslp", "xBR lv2 noblend"},
    {"xbr/xbr-lv2-3d.glslp", "xBR lv2 3D (jeux 3D)"},
    {"xbr/xbr-lv3.glslp", "xBR lv3"},
    {"xbr/xbr-lv3-multipass.glslp", "xBR lv3 multipass"},
    {"xbr/xbr-mlv4-multipass.glslp", "xBR mlv4 multipass"},
    {"xbr/super-xbr-2p.glslp", "Super-xBR 2p"},
    {"xbr/super-xbr-3p-smoother.glslp", "Super-xBR 3p smoother"},
    {"xbr/super-xbr-6p.glslp", "Super-xBR 6p"},
    {"xbr/super-xbr-6p-adaptive.glslp", "Super-xBR 6p adaptive"},
    {"xbr/super-xbr-6p-small-details.glslp", "Super-xBR 6p small details"},
    {"xbr/super-2xbr-3d-2p.glslp", "Super-2xBR 3D 2p (3D 2x)"},
    {"xbr/super-2xbr-3d-3p-smoother.glslp", "Super-2xBR 3D 3p smoother (3D 2x)"},
    {"xbr/super-4xbr-3d-4p.glslp", "Super-4xBR 3D 4p (3D 4x)"},
    {"xbr/super-4xbr-3d-6p-smoother.glslp", "Super-4xBR 3D 6p smoother (3D 4x)"},
    {"xbr/super-8xbr-3d-6p.glslp", "Super-8xBR 3D 6p (3D 8x)"},
};

const std::vector<std::string>& ShaderChain::xbrPresets() {
  static const std::vector<std::string> names = [] {
    std::vector<std::string> list;
    for (const auto& p : kXbrPresets) list.push_back(p.name);
    return list;
  }();
  return names;
}

static const EmbeddedFile* findEmbedded(const std::string& path) {
  for (size_t i = 0; i < kEmbeddedShaderCount; i++) {
    if (path == kEmbeddedShaders[i].path) return &kEmbeddedShaders[i];
  }
  return nullptr;
}

static std::string trim(const std::string& s) {
  const size_t a = s.find_first_not_of(" \t\r\n\"");
  const size_t b = s.find_last_not_of(" \t\r\n\"");
  return a == std::string::npos ? std::string() : s.substr(a, b - a + 1);
}

/** Chemin « a/b/../c » simplifié (« a/c »). */
static std::string normalize(const std::string& path) {
  std::vector<std::string> parts;
  size_t start = 0;
  while (start <= path.size()) {
    size_t slash = path.find('/', start);
    if (slash == std::string::npos) slash = path.size();
    const std::string part = path.substr(start, slash - start);
    if (part == "..") {
      if (!parts.empty()) parts.pop_back();
    } else if (!part.empty() && part != ".") {
      parts.push_back(part);
    }
    start = slash + 1;
  }
  std::string out;
  for (const auto& p : parts) out += (out.empty() ? "" : "/") + p;
  return out;
}

bool ShaderChain::compilePass(Pass& pass, const std::string& source, const std::vector<std::pair<std::string, float>>& overrides, int glslVersion) {
  // Version du contexte à la place de celle du fichier, puis VERTEX ou FRAGMENT et PARAMETER_UNIFORM
  // (paramètres en uniformes), comme RetroArch.
  std::string body = source;
  const size_t version = body.find("#version");
  if (version != std::string::npos) body.replace(version, body.find('\n', version) - version, "");
  auto stage = [&](GLenum type, const char* define) -> GLuint {
    const std::string text = "#version " + std::to_string(glslVersion) + "\n#define " + define + "\n#define PARAMETER_UNIFORM\n" + body;
    const char* ptr = text.c_str();
    GLuint shader = p_glCreateShader(type);
    p_glShaderSource(shader, 1, &ptr, nullptr);
    p_glCompileShader(shader);
    GLint ok = 0;
    p_glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
      char log[1024];
      p_glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
      logf("Shader %s : %s", define, log);
      p_glDeleteShader(shader);
      return 0;
    }
    return shader;
  };
  GLuint vs = stage(GL_VERTEX_SHADER, "VERTEX"), fs = stage(GL_FRAGMENT_SHADER, "FRAGMENT");
  if (!vs || !fs) {
    if (vs) p_glDeleteShader(vs);
    if (fs) p_glDeleteShader(fs);
    return false;
  }
  pass.program = p_glCreateProgram();
  p_glAttachShader(pass.program, vs);
  p_glAttachShader(pass.program, fs);
  p_glBindAttribLocation(pass.program, 0, "VertexCoord");
  p_glBindAttribLocation(pass.program, 1, "TexCoord");
  p_glBindAttribLocation(pass.program, 2, "COLOR");
  p_glLinkProgram(pass.program);
  p_glDeleteShader(vs);
  p_glDeleteShader(fs);
  GLint linked = 0;
  p_glGetProgramiv(pass.program, GL_LINK_STATUS, &linked);
  if (!linked) {
    char log[1024];
    p_glGetProgramInfoLog(pass.program, sizeof(log), nullptr, log);
    logf("Shader (édition des liens) : %s", log);
    return false;
  }
  const GLuint p = pass.program;
  pass.mvp = p_glGetUniformLocation(p, "MVPMatrix");
  pass.frameCount = p_glGetUniformLocation(p, "FrameCount");
  pass.frameDirection = p_glGetUniformLocation(p, "FrameDirection");
  pass.outputSize = p_glGetUniformLocation(p, "OutputSize");
  pass.textureSize = p_glGetUniformLocation(p, "TextureSize");
  pass.inputSize = p_glGetUniformLocation(p, "InputSize");
  pass.texture = p_glGetUniformLocation(p, "Texture");
  pass.origTexture = p_glGetUniformLocation(p, "OrigTexture");
  pass.origTextureSize = p_glGetUniformLocation(p, "OrigTextureSize");
  pass.origInputSize = p_glGetUniformLocation(p, "OrigInputSize");
  for (int n = 2; n <= 9; n++) {
    const std::string base = "PassPrev" + std::to_string(n);
    pass.prevTexture[n - 2] = p_glGetUniformLocation(p, (base + "Texture").c_str());
    pass.prevTextureSize[n - 2] = p_glGetUniformLocation(p, (base + "TextureSize").c_str());
    pass.prevInputSize[n - 2] = p_glGetUniformLocation(p, (base + "InputSize").c_str());
  }
  // #pragma parameter NOM "libellé" défaut min max pas : valeur du préréglage, sinon celle par défaut.
  size_t at = 0;
  while ((at = source.find("#pragma parameter", at)) != std::string::npos) {
    const size_t end = source.find('\n', at);
    const std::string line = source.substr(at + 17, end == std::string::npos ? std::string::npos : end - at - 17);
    at = end == std::string::npos ? source.size() : end;
    const size_t nameStart = line.find_first_not_of(" \t");
    if (nameStart == std::string::npos) continue;
    const size_t nameEnd = line.find_first_of(" \t", nameStart);
    const std::string name = line.substr(nameStart, nameEnd - nameStart);
    const size_t closing = line.rfind('"');
    if (nameEnd == std::string::npos || closing == std::string::npos) continue;
    float value = (float)atof(line.c_str() + closing + 1);
    for (const auto& [key, v] : overrides) {
      if (key == name) value = v;
    }
    const int location = p_glGetUniformLocation(p, name.c_str());
    if (location >= 0) pass.params.emplace_back(location, value);
  }
  return true;
}

bool ShaderChain::load(int index, int glslVersion) {
  if (index == loaded_) return !passes_.empty();
  destroy();
  loaded_ = index;
  if (index < 0 || index >= (int)(sizeof(kXbrPresets) / sizeof(kXbrPresets[0]))) return false;
  const std::string presetPath = kXbrPresets[index].file;
  const EmbeddedFile* preset = findEmbedded(presetPath);
  if (!preset) {
    logf("Préréglage %s absent", presetPath.c_str());
    return false;
  }
  // Préréglage : « clé = valeur » par ligne.
  std::map<std::string, std::string> keys;
  const std::string text((const char*)preset->data, preset->size);
  size_t start = 0;
  while (start < text.size()) {
    size_t end = text.find('\n', start);
    if (end == std::string::npos) end = text.size();
    std::string line = text.substr(start, end - start);
    start = end + 1;
    const size_t hash = line.find('#');
    if (hash != std::string::npos) line.resize(hash);
    const size_t eq = line.find('=');
    if (eq != std::string::npos) keys[trim(line.substr(0, eq))] = trim(line.substr(eq + 1));
  }
  auto key = [&](const std::string& name, const std::string& fallback = std::string()) {
    auto it = keys.find(name);
    return it == keys.end() ? fallback : it->second;
  };
  std::vector<std::pair<std::string, float>> overrides;
  {
    const std::string list = key("parameters");
    size_t from = 0;
    while (from < list.size()) {
      size_t semi = list.find(';', from);
      if (semi == std::string::npos) semi = list.size();
      const std::string name = trim(list.substr(from, semi - from));
      if (!name.empty() && keys.count(name)) overrides.emplace_back(name, (float)atof(keys[name].c_str()));
      from = semi + 1;
    }
  }
  const std::string dir = presetPath.substr(0, presetPath.rfind('/') + 1);
  int count = atoi(key("shaders", "0").c_str());
  // Dernière passe absente (jinc2, stock) : laissée au moteur.
  while (count > 0 && !findEmbedded(normalize(dir + key("shader" + std::to_string(count - 1))))) count--;
  for (int i = 0; i < count; i++) {
    const std::string n = std::to_string(i);
    const EmbeddedFile* file = findEmbedded(normalize(dir + key("shader" + n)));
    Pass pass;
    if (!file) {
      logf("Shader %s absent", key("shader" + n).c_str());
      destroy();
      loaded_ = index;
      return false;
    }
    const std::string typeX = key("scale_type_x" + n, key("scale_type" + n)), typeY = key("scale_type_y" + n, key("scale_type" + n));
    // Sans échelle : à la taille de l'écran pour la dernière passe du préréglage, de l'image sinon.
    const bool lastOfPreset = i == atoi(key("shaders", "0").c_str()) - 1;
    pass.viewport = typeX == "viewport" || (typeX.empty() && lastOfPreset);
    pass.scaleX = (float)atof(key("scale_x" + n, key("scale" + n, "1")).c_str());
    pass.scaleY = (float)atof(key("scale_y" + n, key("scale" + n, "1")).c_str());
    if (typeY == "viewport") pass.viewport = true;
    pass.linear = key("filter_linear" + n) == "true";
    if (!compilePass(pass, std::string((const char*)file->data, file->size), overrides, glslVersion)) {
      logf("Préréglage %s : passe %d refusée", presetPath.c_str(), i);
      if (pass.program) p_glDeleteProgram(pass.program);
      destroy();
      loaded_ = index;
      return false;
    }
    passes_.push_back(pass);
  }
  logf("Préréglage %s : %d passes", presetPath.c_str(), (int)passes_.size());
  return !passes_.empty();
}

unsigned ShaderChain::run(unsigned source, int inW, int inH, int viewW, int viewH, unsigned frameCount, int& outW, int& outH, bool& atViewport) {
  struct Image {
    unsigned texture;
    int w, h;
  };
  std::vector<Image> inputs = {{source, inW, inH}};  // entrée de chaque passe (0 : image d'origine)
  // Rectangle 0..1 (MVPMatrix : vers -1..1), coordonnées de texture 0..1 (images de taille exacte).
  static const float quad[] = {0, 0, 0, 0, 1, 0, 1, 0, 0, 1, 0, 1, 1, 1, 1, 1};
  static const float mvp[16] = {2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0, -1, -1, 0, 1};
  p_glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STREAM_DRAW);
  p_glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), reinterpret_cast<void*>(0));
  p_glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), reinterpret_cast<void*>(2 * sizeof(float)));
  p_glEnableVertexAttribArray(0);
  p_glEnableVertexAttribArray(1);
  p_glDisableVertexAttribArray(2);
  for (size_t i = 0; i < passes_.size(); i++) {
    Pass& pass = passes_[i];
    const Image in = inputs.back();
    const int w = pass.viewport ? viewW : std::max(1, (int)std::lround(in.w * pass.scaleX));
    const int h = pass.viewport ? viewH : std::max(1, (int)std::lround(in.h * pass.scaleY));
    if (!pass.fbo) {
      p_glGenFramebuffers(1, &pass.fbo);
      glGenTextures(1, &pass.target);
    }
    if (w != pass.width || h != pass.height) {
      pass.width = w;
      pass.height = h;
      glBindTexture(GL_TEXTURE_2D, pass.target);
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      p_glBindFramebuffer(GL_FRAMEBUFFER, pass.fbo);
      p_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, pass.target, 0);
      if (p_glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        logf("Image intermédiaire du shader incomplète (%dx%d)", w, h);
        pass.width = pass.height = 0;
        return 0;
      }
    }
    p_glBindFramebuffer(GL_FRAMEBUFFER, pass.fbo);
    glViewport(0, 0, w, h);
    p_glUseProgram(pass.program);
    // Entrée (unité 0), image d'origine (1), entrées des passes précédentes (PassPrev2… : 2 et suivantes).
    p_glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, in.texture);
    const GLint filter = pass.linear ? GL_LINEAR : GL_NEAREST;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (pass.texture >= 0) p_glUniform1i(pass.texture, 0);
    if (pass.origTexture >= 0) {
      p_glActiveTexture(GL_TEXTURE1);
      glBindTexture(GL_TEXTURE_2D, inputs[0].texture);
      p_glUniform1i(pass.origTexture, 1);
    }
    for (int n = 2; n <= 9; n++) {
      const int k = (int)i - (n - 1);  // PassPrevN : entrée de la passe i - (N - 1)
      if (k < 0 || pass.prevTexture[n - 2] < 0) continue;
      p_glActiveTexture(GL_TEXTURE0 + n);
      glBindTexture(GL_TEXTURE_2D, inputs[(size_t)k].texture);
      p_glUniform1i(pass.prevTexture[n - 2], n);
      p_glUniform2f(pass.prevTextureSize[n - 2], (float)inputs[(size_t)k].w, (float)inputs[(size_t)k].h);
      p_glUniform2f(pass.prevInputSize[n - 2], (float)inputs[(size_t)k].w, (float)inputs[(size_t)k].h);
    }
    p_glActiveTexture(GL_TEXTURE0);
    if (pass.mvp >= 0) p_glUniformMatrix4fv(pass.mvp, 1, GL_FALSE, mvp);
    if (pass.frameCount >= 0) p_glUniform1i(pass.frameCount, (int)frameCount);
    if (pass.frameDirection >= 0) p_glUniform1i(pass.frameDirection, 1);
    if (pass.outputSize >= 0) p_glUniform2f(pass.outputSize, (float)w, (float)h);
    if (pass.textureSize >= 0) p_glUniform2f(pass.textureSize, (float)in.w, (float)in.h);
    if (pass.inputSize >= 0) p_glUniform2f(pass.inputSize, (float)in.w, (float)in.h);
    if (pass.origTextureSize >= 0) p_glUniform2f(pass.origTextureSize, (float)inW, (float)inH);
    if (pass.origInputSize >= 0) p_glUniform2f(pass.origInputSize, (float)inW, (float)inH);
    for (const auto& [location, value] : pass.params) p_glUniform1f(location, value);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    inputs.push_back({pass.target, w, h});
  }
  p_glBindFramebuffer(GL_FRAMEBUFFER, 0);
  const Image& last = inputs.back();
  outW = last.w;
  outH = last.h;
  atViewport = !passes_.empty() && passes_.back().viewport;
  return passes_.empty() ? 0 : last.texture;
}

void ShaderChain::destroy() {
  for (Pass& pass : passes_) {
    if (pass.program) p_glDeleteProgram(pass.program);
    if (pass.fbo) p_glDeleteFramebuffers(1, &pass.fbo);
    if (pass.target) glDeleteTextures(1, &pass.target);
  }
  passes_.clear();
  loaded_ = -1;
}
