// Utilitaires : journal, chemins UTF-8 sous Windows, fichiers.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

void logf(const char* fmt, ...);

std::wstring widen(const std::string& utf8);
std::string narrow(const std::wstring& wide);

/** Chemin utilisable par les cœurs qui ouvrent les fichiers en ANSI : nom court 8.3 si non ASCII. */
std::string compatiblePath(const std::string& utf8);

bool readFile(const std::string& path, std::vector<uint8_t>& out);
bool writeFile(const std::string& path, const void* data, size_t size);
bool fileExists(const std::string& path);
void makeDirs(const std::string& path);

std::string baseName(const std::string& path);       // "C:\a\Jeu (FR).sfc" -> "Jeu (FR)"
std::string joinPath(const std::string& dir, const std::string& name);

/** Décode une chaîne UTF-8 en points de code. */
std::vector<uint32_t> utf8Decode(const std::string& s);
