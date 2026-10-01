// Options d'un cœur (variables libretro) : déclarées par le cœur au format « Libellé; v1|v2|… »
// (la première valeur est celle par défaut), valeurs choisies mémorisées par système dans un fichier
// « clé=valeur » et appliquées au lancement suivant.
#pragma once

#include <map>
#include <string>
#include <vector>

struct retro_variable;

struct CoreOption {
  std::string key;
  std::string label;
  std::vector<std::string> values;
  std::string value;
  const std::string& defaultValue() const { return values.front(); }
};

class CoreOptions {
 public:
  void load(const std::string& file);
  /** RETRO_ENVIRONMENT_SET_VARIABLES : garde les valeurs mémorisées si elles sont encore valides. */
  void declare(const retro_variable* vars);
  /** RETRO_ENVIRONMENT_GET_VARIABLE ; nullptr si la clé est inconnue. */
  const char* get(const std::string& key) const;
  /** RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE : vrai une fois après un changement. */
  bool takeUpdated();

  const std::vector<CoreOption>& list() const { return options_; }
  /** Valeur suivante (ou précédente) d'une option ; mémorisée. */
  void cycle(size_t index, int direction);
  void resetAll();

  /** Réglage du moteur (clé « romcloud_… »), mémorisé avec les options du cœur. */
  std::string setting(const std::string& key, const std::string& fallback) const;
  void setSetting(const std::string& key, const std::string& value);

 private:
  void save() const;

  std::string file_;
  std::map<std::string, std::string> saved_;
  std::vector<CoreOption> options_;
  bool updated_ = false;
};
