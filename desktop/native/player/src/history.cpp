#include "history.h"

#include <windows.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <ctime>
#include <random>

#include "util.h"

namespace {

constexpr int kThumbnailWidth = 320;

std::string newId() {
  std::random_device device;
  std::mt19937_64 random(((uint64_t)device() << 32) ^ device() ^ (uint64_t)std::chrono::steady_clock::now().time_since_epoch().count());
  uint64_t a = random(), b = random();
  char id[40];
  snprintf(id, sizeof id, "%08x-%04x-4%03x-%04x-%012llx", (unsigned)(a >> 32), (unsigned)(a >> 16) & 0xffff,
           (unsigned)a & 0xfff, (unsigned)((b >> 48) & 0x3fff) | 0x8000, (unsigned long long)(b & 0xffffffffffffULL));
  return id;
}

int64_t nowMillis() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
}

void removeFile(const std::string& path) { DeleteFileW(widen(path).c_str()); }

}  // namespace

std::string StateHistory::jsonString(const std::string& text) {
  std::string out = "\"";
  for (unsigned char c : text) {
    if (c == '"' || c == '\\') {
      out += '\\';
      out += (char)c;
    } else if (c < 0x20) {
      char escaped[8];
      snprintf(escaped, sizeof escaped, "\\u%04x", c);
      out += escaped;
    } else {
      out += (char)c;
    }
  }
  return out + "\"";
}

std::string StateHistory::jsonValue(const std::string& json, const std::string& key) {
  size_t at = json.find("\"" + key + "\"");
  if (at == std::string::npos) return {};
  at = json.find(':', at + key.size() + 2);
  if (at == std::string::npos) return {};
  at = json.find_first_not_of(" \t\r\n", at + 1);
  if (at == std::string::npos) return {};
  if (json[at] != '"') {
    size_t end = json.find_first_of(",}\r\n", at);
    std::string raw = json.substr(at, end == std::string::npos ? std::string::npos : end - at);
    while (!raw.empty() && (raw.back() == ' ' || raw.back() == '\t')) raw.pop_back();
    return raw == "null" ? std::string() : raw;
  }
  std::string out;
  for (size_t i = at + 1; i < json.size() && json[i] != '"'; i++) {
    if (json[i] == '\\' && i + 1 < json.size()) {
      char next = json[++i];
      if (next == 'u' && i + 4 < json.size()) {
        unsigned code = (unsigned)strtoul(json.substr(i + 1, 4).c_str(), nullptr, 16);
        i += 4;
        if (code < 0x80) out += (char)code;
        else if (code < 0x800) {
          out += (char)(0xC0 | (code >> 6));
          out += (char)(0x80 | (code & 0x3F));
        } else {
          out += (char)(0xE0 | (code >> 12));
          out += (char)(0x80 | ((code >> 6) & 0x3F));
          out += (char)(0x80 | (code & 0x3F));
        }
      } else {
        out += next == 'n' ? '\n' : next == 't' ? '\t' : next;
      }
    } else {
      out += json[i];
    }
  }
  return out;
}

std::vector<HistoryEntry> StateHistory::read(const std::string& dir, bool online) const {
  std::vector<HistoryEntry> entries;
  WIN32_FIND_DATAW found;
  HANDLE handle = FindFirstFileW(widen(joinPath(dir, "*.json")).c_str(), &found);
  if (handle == INVALID_HANDLE_VALUE) return entries;
  do {
    std::string name = narrow(found.cFileName);
    std::string id = name.substr(0, name.size() - 5);
    std::vector<uint8_t> bytes;
    HistoryEntry entry;
    entry.statePath = joinPath(dir, id + ".state");
    if (!readFile(joinPath(dir, name), bytes) || !fileExists(entry.statePath)) continue;
    std::string json(bytes.begin(), bytes.end());
    entry.id = id;
    entry.device = jsonValue(json, "device");
    entry.createdAt = _strtoi64(jsonValue(json, "createdAt").c_str(), nullptr, 10);
    entry.pinned = jsonValue(json, "pinned") == "true";
    entry.online = online;
    entries.push_back(entry);
  } while (FindNextFileW(handle, &found));
  FindClose(handle);
  return entries;
}

std::vector<HistoryEntry> StateHistory::list() const {
  if (!enabled()) return {};
  std::vector<HistoryEntry> entries = read(dir_, false);
  // En ligne : seulement ceux qui ne sont pas déjà sur ce PC.
  for (auto& entry : read(joinPath(dir_, "online"), true)) {
    bool local = std::any_of(entries.begin(), entries.end(), [&](const HistoryEntry& e) { return e.id == entry.id; });
    if (!local) entries.push_back(entry);
  }
  std::sort(entries.begin(), entries.end(), [](const HistoryEntry& a, const HistoryEntry& b) { return a.createdAt > b.createdAt; });
  return entries;
}

bool StateHistory::add(const std::vector<uint8_t>& state, SDL_Surface* frame) {
  if (!enabled() || state.empty()) return false;
  makeDirs(dir_);
  const std::string id = newId();
  if (!writeFile(joinPath(dir_, id + ".state"), state.data(), state.size())) return false;
  if (frame && frame->w > 0 && frame->h > 0) {
    int width = std::min(kThumbnailWidth, frame->w), height = std::max(1, frame->h * width / frame->w);
    SDL_Surface* thumbnail = SDL_ScaleSurface(frame, width, height, SDL_SCALEMODE_LINEAR);
    if (thumbnail) {
      if (!SDL_SavePNG(thumbnail, joinPath(dir_, id + ".png").c_str())) logf("Miniature impossible : %s", SDL_GetError());
      SDL_DestroySurface(thumbnail);
    }
  }
  std::string json = "{\"id\":" + jsonString(id) + ",\"core\":" + jsonString(core_) + ",\"createdAt\":" + std::to_string(nowMillis()) +
                     ",\"device\":" + jsonString(device_) + ",\"platform\":\"windows\",\"pinned\":false,\"uploaded\":false}";
  bool ok = writeFile(joinPath(dir_, id + ".json"), json.data(), json.size());
  prune();
  return ok;
}

void StateHistory::prune() const {
  int kept = 0;
  std::vector<HistoryEntry> own = read(dir_, false);
  std::sort(own.begin(), own.end(), [](const HistoryEntry& a, const HistoryEntry& b) { return a.createdAt > b.createdAt; });
  for (const auto& entry : own) {
    if (entry.pinned || ++kept <= kLimit) continue;
    for (const char* ext : {".state", ".png", ".json"}) removeFile(joinPath(dir_, entry.id + ext));
  }
}

std::string formatStateDate(int64_t millis, const std::string& language) {
  time_t seconds = (time_t)(millis / 1000);
  tm local{};
  localtime_s(&local, &seconds);
  char text[32];
  strftime(text, sizeof text, language == "fr" ? "%d/%m/%Y %H:%M" : "%Y-%m-%d %H:%M", &local);
  return text;
}
