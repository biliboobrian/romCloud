// Historique des états de sauvegarde d'un jeu (--history-dir), même format que l'application
// Android : un fichier par état (« <id>.state »), sa miniature (« <id>.png », image du jeu) et ses
// informations (« <id>.json » : date, appareil, épinglé, envoyé). Les états en ligne d'autres
// appareils, téléchargés par RomCloud avant la partie, sont dans le sous-dossier « online »
// (lecture seule ici). Les 10 états les plus récents de ce PC sont gardés, plus les épinglés.
#pragma once

#include <SDL3/SDL.h>

#include <cstdint>
#include <string>
#include <vector>

struct HistoryEntry {
  std::string id;
  std::string statePath;
  std::string device;
  int64_t createdAt = 0;  // ms depuis 1970
  bool pinned = false;
  bool online = false;  // état d'un autre appareil (sous-dossier « online »)
};

class StateHistory {
 public:
  static constexpr int kLimit = 10;

  StateHistory(std::string dir, std::string core, std::string device)
      : dir_(std::move(dir)), core_(std::move(core)), device_(std::move(device)) {}

  bool enabled() const { return !dir_.empty(); }
  /** États de ce PC et en ligne, du plus récent au plus ancien. */
  std::vector<HistoryEntry> list() const;
  /** Ajoute un état et sa miniature ([frame] : image du jeu, peut être nulle). */
  bool add(const std::vector<uint8_t>& state, SDL_Surface* frame);

  /** Valeur d'une clé d'un objet JSON simple (texte, nombre ou booléen), vide si absente. */
  static std::string jsonValue(const std::string& json, const std::string& key);
  /** Texte échappé pour JSON (guillemets compris). */
  static std::string jsonString(const std::string& text);

 private:
  std::vector<HistoryEntry> read(const std::string& dir, bool online) const;
  void prune() const;

  std::string dir_, core_, device_;
};

/** Date et heure locales d'un état (« 12/10/2026 14:32 » en français, « 2026-10-12 14:32 » sinon). */
std::string formatStateDate(int64_t millis, const std::string& language);
