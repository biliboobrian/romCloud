#include "util.h"

#include <windows.h>

#include <cstdarg>
#include <cstdio>

void logf(const char* fmt, ...) {
  va_list args;
  va_start(args, fmt);
  vfprintf(stderr, fmt, args);
  va_end(args);
  fputc('\n', stderr);
  fflush(stderr);
}

std::wstring widen(const std::string& utf8) {
  if (utf8.empty()) return {};
  int n = MultiByteToWideChar(CP_UTF8, 0, utf8.data(), (int)utf8.size(), nullptr, 0);
  std::wstring out(n, L'\0');
  MultiByteToWideChar(CP_UTF8, 0, utf8.data(), (int)utf8.size(), out.data(), n);
  return out;
}

std::string narrow(const std::wstring& wide) {
  if (wide.empty()) return {};
  int n = WideCharToMultiByte(CP_UTF8, 0, wide.data(), (int)wide.size(), nullptr, 0, nullptr, nullptr);
  std::string out(n, '\0');
  WideCharToMultiByte(CP_UTF8, 0, wide.data(), (int)wide.size(), out.data(), n, nullptr, nullptr);
  return out;
}

std::string compatiblePath(const std::string& utf8) {
  bool ascii = true;
  for (unsigned char c : utf8) ascii = ascii && c < 0x80;
  if (ascii) return utf8;
  std::wstring wide = widen(utf8);
  DWORD n = GetShortPathNameW(wide.c_str(), nullptr, 0);
  if (n == 0) return utf8;
  std::wstring shortPath(n, L'\0');
  n = GetShortPathNameW(wide.c_str(), shortPath.data(), n);
  shortPath.resize(n);
  return n ? narrow(shortPath) : utf8;
}

bool readFile(const std::string& path, std::vector<uint8_t>& out) {
  FILE* f = _wfopen(widen(path).c_str(), L"rb");
  if (!f) return false;
  fseek(f, 0, SEEK_END);
  long size = ftell(f);
  fseek(f, 0, SEEK_SET);
  out.resize(size > 0 ? (size_t)size : 0);
  bool ok = size <= 0 || fread(out.data(), 1, out.size(), f) == out.size();
  fclose(f);
  return ok;
}

bool writeFile(const std::string& path, const void* data, size_t size) {
  size_t slash = path.find_last_of("\\/");
  if (slash != std::string::npos) makeDirs(path.substr(0, slash));
  // Écriture dans un fichier temporaire puis remplacement : pas de sauvegarde tronquée.
  std::string tmp = path + ".tmp";
  FILE* f = _wfopen(widen(tmp).c_str(), L"wb");
  if (!f) return false;
  bool ok = fwrite(data, 1, size, f) == size;
  ok = fclose(f) == 0 && ok;
  if (!ok) return false;
  return MoveFileExW(widen(tmp).c_str(), widen(path).c_str(), MOVEFILE_REPLACE_EXISTING) != 0;
}

bool fileExists(const std::string& path) {
  DWORD attr = GetFileAttributesW(widen(path).c_str());
  return attr != INVALID_FILE_ATTRIBUTES && !(attr & FILE_ATTRIBUTE_DIRECTORY);
}

void makeDirs(const std::string& path) {
  if (path.empty()) return;
  std::wstring wide = widen(path);
  for (size_t i = 1; i <= wide.size(); i++) {
    if (i == wide.size() || wide[i] == L'\\' || wide[i] == L'/') {
      std::wstring part = wide.substr(0, i);
      if (part.size() > 2 || (part.size() == 2 && part[1] != L':')) CreateDirectoryW(part.c_str(), nullptr);
    }
  }
}

std::string baseName(const std::string& path) {
  size_t slash = path.find_last_of("\\/");
  std::string name = slash == std::string::npos ? path : path.substr(slash + 1);
  size_t dot = name.find_last_of('.');
  return dot == std::string::npos ? name : name.substr(0, dot);
}

std::string joinPath(const std::string& dir, const std::string& name) {
  if (dir.empty()) return name;
  char last = dir.back();
  return (last == '\\' || last == '/') ? dir + name : dir + "\\" + name;
}

std::vector<uint32_t> utf8Decode(const std::string& s) {
  std::vector<uint32_t> out;
  for (size_t i = 0; i < s.size();) {
    unsigned char c = (unsigned char)s[i];
    uint32_t cp;
    int extra;
    if (c < 0x80) { cp = c; extra = 0; }
    else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; extra = 1; }
    else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; extra = 2; }
    else { cp = c & 0x07; extra = 3; }
    i++;
    for (int k = 0; k < extra && i < s.size(); k++, i++) cp = (cp << 6) | ((unsigned char)s[i] & 0x3F);
    // Ligatures absentes de la police Latin-1 : « œ » s'écrit « oe ».
    if (cp == 0x153 || cp == 0x152) {
      out.push_back(cp == 0x153 ? 'o' : 'O');
      out.push_back(cp == 0x153 ? 'e' : 'E');
      continue;
    }
    out.push_back(cp);
  }
  return out;
}
