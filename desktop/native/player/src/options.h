// Options d'un cœur (variables libretro) : déclarées par le cœur au format « Libellé; v1|v2|… »
// (la première valeur est celle par défaut) ou, pour les cœurs récents, au format des options v1/v2
// (libellés des valeurs, valeur par défaut, traductions, catégories). Valeurs choisies mémorisées
// par système dans un fichier « clé=valeur » et appliquées au lancement suivant.
#pragma once

#include <map>
#include <string>
#include <vector>

struct retro_variable;
struct retro_core_option_definition;
struct retro_core_options_v2;

struct CoreOption {
  std::string key;
  std::string label;
  std::vector<std::string> values;
  std::vector<std::string> labels;  // libellés affichés des valeurs (vide : la valeur elle-même)
  std::string defaultVal;
  std::string category;  // clé de catégorie (options v2), vide sinon
  std::string value;
  const std::string& defaultValue() const { return defaultVal; }
  /** Libellé de la valeur en cours. */
  std::string display() const;
};

/** Onglet d'options : catégorie du cœur ou type d'options ; titre vide = options générales. */
struct CoreOptionGroup {
  std::string title;
  std::vector<size_t> indices;  // positions dans CoreOptions::list()
};

class CoreOptions {
 public:
  void load(const std::string& file);
  /** Valeur par défaut propre au jeu (« clé=valeur »), utilisée sans valeur mémorisée ; non enregistrée. */
  void setGameDefault(const std::string& assignment);
  /** RETRO_ENVIRONMENT_SET_VARIABLES : garde les valeurs mémorisées si elles sont encore valides. */
  void declare(const retro_variable* vars);
  /** RETRO_ENVIRONMENT_SET_CORE_OPTIONS(_INTL) : [local] (facultatif) traduit les libellés. */
  void declare(const retro_core_option_definition* us, const retro_core_option_definition* local);
  /** RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2(_INTL) : options et catégories ; [local] facultatif. */
  void declare(const retro_core_options_v2* us, const retro_core_options_v2* local);
  /** RETRO_ENVIRONMENT_GET_VARIABLE ; nullptr si la clé est inconnue. */
  const char* get(const std::string& key) const;
  /** RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE : vrai une fois après un changement. */
  bool takeUpdated();

  const std::vector<CoreOption>& list() const { return options_; }
  /**
   * Onglets d'options. Catégories déclarées par le cœur (options v2) dans leur ordre, les options
   * sans catégorie en premier. Sinon, répartition par type d'après la clé (même règle que
   * l'application Android) : le préfixe commun à toutes (« beetle_psx_hw_ ») est retiré, puis le
   * mot suivant sert de type (« gpu », « cpu »…) ; un mot porté par une seule option, ou une clé
   * sans mot suivant, va dans les options générales, en premier ; les autres types suivent par
   * ordre alphabétique, et les options par libellé.
   */
  std::vector<CoreOptionGroup> groups() const;
  /** Valeur suivante (ou précédente) d'une option ; mémorisée. */
  void cycle(size_t index, int direction);
  void resetAll();

  /** Réglage du moteur (clé « romcloud_… »), mémorisé avec les options du cœur. */
  std::string setting(const std::string& key, const std::string& fallback) const;
  void setSetting(const std::string& key, const std::string& value);

 private:
  void save() const;
  /** Ajoute ou remplace une option, avec la valeur en cours ou mémorisée si elle existe encore. */
  void add(CoreOption option);
  std::vector<CoreOptionGroup> groupsByCategory() const;
  std::vector<CoreOptionGroup> groupsByKey() const;

  std::string file_;
  std::map<std::string, std::string> saved_;
  std::map<std::string, std::string> gameDefaults_;
  std::vector<CoreOption> options_;
  std::vector<std::pair<std::string, std::string>> categories_;  // clé -> titre, ordre du cœur
  bool updated_ = false;
};
