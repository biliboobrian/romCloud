#include "options.h"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstring>

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

std::string CoreOption::display() const {
  for (size_t i = 0; i < values.size() && i < labels.size(); i++) {
    if (values[i] == value && !labels[i].empty()) return labels[i];
  }
  return value;
}

void CoreOptions::add(CoreOption option) {
  if (option.values.empty()) option.values.push_back("");
  if (std::find(option.values.begin(), option.values.end(), option.defaultVal) == option.values.end()) {
    option.defaultVal = option.values.front();
  }
  // Valeur déjà en cours (nouvelle déclaration du même cœur) ou mémorisée, si elle existe encore.
  auto current = std::find_if(options_.begin(), options_.end(), [&](const CoreOption& o) { return o.key == option.key; });
  std::string wanted = current != options_.end() ? current->value
                      : saved_.count(option.key)     ? saved_[option.key]
                      : gameDefaults_.count(option.key) ? gameDefaults_[option.key]
                                                     : "";
  bool valid = std::find(option.values.begin(), option.values.end(), wanted) != option.values.end();
  option.value = valid ? wanted : option.defaultVal;
  if (current != options_.end()) *current = option;
  else options_.push_back(option);
}

void CoreOptions::setGameDefault(const std::string& assignment) {
  size_t eq = assignment.find('=');
  if (eq != std::string::npos) gameDefaults_[trim(assignment.substr(0, eq))] = trim(assignment.substr(eq + 1));
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
    if (!option.values.empty()) option.defaultVal = option.values.front();
    add(option);
  }
}

static std::string str(const char* s) { return s ? s : ""; }

/** Valeurs et libellés d'une option v1/v2 ; libellés traduits pris dans [local] s'il les donne. */
static void readValues(CoreOption& option, const retro_core_option_value* values, const retro_core_option_value* local) {
  for (size_t i = 0; i < RETRO_NUM_CORE_OPTION_VALUES_MAX && values[i].value; i++) {
    std::string label = str(values[i].label);
    for (size_t j = 0; local && j < RETRO_NUM_CORE_OPTION_VALUES_MAX && local[j].value; j++) {
      if (strcmp(local[j].value, values[i].value) == 0 && local[j].label) label = local[j].label;
    }
    option.values.push_back(values[i].value);
    option.labels.push_back(label);
  }
}

void CoreOptions::declare(const retro_core_option_definition* us, const retro_core_option_definition* local) {
  for (; us && us->key; us++) {
    const retro_core_option_definition* tr = nullptr;
    for (auto* l = local; l && l->key; l++) {
      if (strcmp(l->key, us->key) == 0) tr = l;
    }
    CoreOption option;
    option.key = us->key;
    option.label = str(tr && tr->desc ? tr->desc : us->desc);
    if (option.label.empty()) option.label = option.key;
    readValues(option, us->values, tr ? tr->values : nullptr);
    option.defaultVal = str(us->default_value);
    add(option);
  }
}

void CoreOptions::declare(const retro_core_options_v2* us, const retro_core_options_v2* local) {
  if (!us) return;
  categories_.clear();
  for (auto* c = us->categories; c && c->key; c++) {
    std::string title = str(c->desc);
    for (auto* l = local ? local->categories : nullptr; l && l->key; l++) {
      if (strcmp(l->key, c->key) == 0 && l->desc) title = l->desc;
    }
    categories_.emplace_back(c->key, title.empty() ? std::string(c->key) : title);
  }
  for (auto* d = us->definitions; d && d->key; d++) {
    const retro_core_option_v2_definition* tr = nullptr;
    for (auto* l = local ? local->definitions : nullptr; l && l->key; l++) {
      if (strcmp(l->key, d->key) == 0) tr = l;
    }
    CoreOption option;
    option.key = d->key;
    option.category = str(d->category_key);
    // Dans un onglet de catégorie, le libellé court (« Résolution ») plutôt que complet.
    const char* desc = d->desc;
    if (tr && tr->desc) desc = tr->desc;
    if (!option.category.empty()) {
      if (d->desc_categorized) desc = d->desc_categorized;
      if (tr && tr->desc_categorized) desc = tr->desc_categorized;
    }
    option.label = str(desc);
    if (option.label.empty()) option.label = option.key;
    readValues(option, d->values, tr ? tr->values : nullptr);
    option.defaultVal = str(d->default_value);
    add(option);
  }
}

const char* CoreOptions::get(const std::string& key) const {
  for (const auto& o : options_) {
    if (o.key == key) return o.value.c_str();
  }
  return nullptr;
}

static std::vector<std::string> keyWords(const std::string& key) {
  std::vector<std::string> words(1);
  for (char c : key) {
    if (c == '_' || c == '-') {
      if (!words.back().empty()) words.emplace_back();
    } else {
      words.back() += (char)std::tolower((unsigned char)c);
    }
  }
  if (words.back().empty()) words.pop_back();
  return words;
}

static std::string lower(std::string s) {
  for (char& c : s) c = (char)std::tolower((unsigned char)c);
  return s;
}

std::vector<CoreOptionGroup> CoreOptions::groups() const {
  bool categorized = std::any_of(options_.begin(), options_.end(), [](const CoreOption& o) { return !o.category.empty(); });
  return categorized ? groupsByCategory() : groupsByKey();
}

std::vector<CoreOptionGroup> CoreOptions::groupsByCategory() const {
  std::vector<CoreOptionGroup> result(1);  // options sans catégorie (ou de catégorie inconnue)
  for (const auto& [key, title] : categories_) result.push_back({title, {}});
  for (size_t i = 0; i < options_.size(); i++) {
    size_t group = 0;
    for (size_t c = 0; c < categories_.size(); c++) {
      if (categories_[c].first == options_[i].category) group = c + 1;
    }
    result[group].indices.push_back(i);
  }
  result.erase(std::remove_if(result.begin(), result.end(), [](const CoreOptionGroup& g) { return g.indices.empty(); }),
               result.end());
  return result;
}

std::vector<CoreOptionGroup> CoreOptions::groupsByKey() const {
  const size_t n = options_.size();
  std::vector<std::vector<std::string>> words(n);
  for (size_t i = 0; i < n; i++) words[i] = keyWords(options_[i].key);
  // Mots communs à toutes les clés, en laissant au moins un mot à chacune.
  size_t common = 0;
  if (n >= 2) {
    size_t shortest = SIZE_MAX;
    for (const auto& w : words) shortest = std::min(shortest, w.size());
    while (shortest > 0 && common < shortest - 1 &&
           std::all_of(words.begin(), words.end(), [&](const auto& w) { return w[common] == words[0][common]; })) {
      common++;
    }
  }
  std::vector<std::string> type(n);
  std::map<std::string, int> counts;
  for (size_t i = 0; i < n; i++) {
    if (words[i].size() > common + 1) counts[type[i] = words[i][common]]++;
  }
  // std::map : le type vide (options générales) en premier, puis l'ordre alphabétique.
  std::map<std::string, std::vector<size_t>> byType;
  for (size_t i = 0; i < n; i++) byType[counts[type[i]] >= 2 ? type[i] : ""].push_back(i);

  std::vector<CoreOptionGroup> result;
  for (auto& [name, indices] : byType) {
    std::sort(indices.begin(), indices.end(),
              [&](size_t a, size_t b) { return lower(options_[a].label) < lower(options_[b].label); });
    std::string title = name;
    // « gpu » -> « GPU », « video » -> « Video ».
    if (title.size() <= 3) {
      for (char& c : title) c = (char)std::toupper((unsigned char)c);
    } else if (!title.empty()) {
      title[0] = (char)std::toupper((unsigned char)title[0]);
    }
    result.push_back({title, indices});
  }
  return result;
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

void CoreOptions::removeSetting(const std::string& key) {
  if (saved_.erase(key)) save();
}

std::vector<std::pair<std::string, std::string>> CoreOptions::settings(const std::string& prefix) const {
  std::vector<std::pair<std::string, std::string>> result;
  for (auto it = saved_.lower_bound(prefix); it != saved_.end() && it->first.compare(0, prefix.size(), prefix) == 0; ++it) {
    result.emplace_back(*it);
  }
  return result;
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
