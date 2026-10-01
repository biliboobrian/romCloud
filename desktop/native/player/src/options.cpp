#include "options.h"

#include <algorithm>

#include "libretro.h"
#include "util.h"

static std::string trim(const std::string& s) {
  size_t a = s.find_first_not_of(" \t\r\n\"");
  size_t b = s.find_last_not_of(" \t\r\n\"");
  return a == std::string::npos ? std::string() : s.substr(a, b - a + 1);
}

void CoreOptions::load(const std::string& file) {
  file_ = file;
  std::vector<uint8_t> data;
  if (file.empty() || !readFile(file, data)) return;
  std::string text(data.begin(), data.end());
  size_t start = 0;
  while (start < text.size()) {
    size_t end = text.find('\n', start);
    if (end == std::string::npos) end = text.size();
    std::string line = text.substr(start, end - start);
    size_t eq = line.find('=');
    if (eq != std::string::npos) saved_[trim(line.substr(0, eq))] = trim(line.substr(eq + 1));
    start = end + 1;
  }
}

void CoreOptions::declare(const retro_variable* vars) {
  for (; vars && vars->key; vars++) {
    CoreOption option;
    option.key = vars->key;
    std::string desc = vars->value ? vars->value : "";
    size_t sep = desc.find("; ");
    option.label = sep == std::string::npos ? option.key : desc.substr(0, sep);
    if (sep != std::string::npos) {
      std::string list = desc.substr(sep + 2);
      size_t start = 0;
      while (start <= list.size()) {
        size_t bar = list.find('|', start);
        if (bar == std::string::npos) bar = list.size();
        if (bar > start) option.values.push_back(list.substr(start, bar - start));
        start = bar + 1;
      }
    }
    if (option.values.empty()) option.values.push_back("");
    // Valeur déjà en cours (nouvelle déclaration du même cœur) ou mémorisée, si elle existe encore.
    auto current = std::find_if(options_.begin(), options_.end(), [&](const CoreOption& o) { return o.key == option.key; });
    std::string wanted = current != options_.end() ? current->value : (saved_.count(option.key) ? saved_[option.key] : "");
    bool valid = std::find(option.values.begin(), option.values.end(), wanted) != option.values.end();
    option.value = valid ? wanted : option.values.front();
    if (current != options_.end()) *current = option;
    else options_.push_back(option);
  }
}

const char* CoreOptions::get(const std::string& key) const {
  for (const auto& o : options_) {
    if (o.key == key) return o.value.c_str();
  }
  return nullptr;
}

bool CoreOptions::takeUpdated() {
  bool was = updated_;
  updated_ = false;
  return was;
}

void CoreOptions::cycle(size_t index, int direction) {
  if (index >= options_.size()) return;
  CoreOption& o = options_[index];
  if (o.values.size() < 2) return;
  auto it = std::find(o.values.begin(), o.values.end(), o.value);
  int i = it == o.values.end() ? 0 : (int)(it - o.values.begin());
  int n = (int)o.values.size();
  o.value = o.values[(i + direction % n + n) % n];
  if (o.value == o.defaultValue()) saved_.erase(o.key);
  else saved_[o.key] = o.value;
  updated_ = true;
  save();
}

std::string CoreOptions::setting(const std::string& key, const std::string& fallback) const {
  auto it = saved_.find(key);
  return it == saved_.end() ? fallback : it->second;
}

void CoreOptions::setSetting(const std::string& key, const std::string& value) {
  saved_[key] = value;
  save();
}

void CoreOptions::resetAll() {
  for (auto& o : options_) {
    o.value = o.defaultValue();
    saved_.erase(o.key);
  }
  updated_ = true;
  save();
}

void CoreOptions::save() const {
  if (file_.empty()) return;
  std::string text;
  for (const auto& kv : saved_) text += kv.first + " = \"" + kv.second + "\"\n";
  writeFile(file_, text.data(), text.size());
}
