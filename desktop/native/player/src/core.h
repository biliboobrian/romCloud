// Chargement d'un cœur libretro (.dll) et callbacks de l'API libretro (environnement, vidéo,
// audio, entrées). Comme dans LibretroDroid, l'état de la session est global : l'API libretro
// n'utilise que des pointeurs de fonctions C sans contexte.
#pragma once

#include <string>
#include <vector>

#include "libretro.h"
#include "options.h"

class Video;
class Audio;
class Input;

struct CoreApi {
  void (*init)();
  void (*deinit)();
  unsigned (*api_version)();
  void (*get_system_info)(retro_system_info*);
  void (*get_system_av_info)(retro_system_av_info*);
  void (*set_environment)(retro_environment_t);
  void (*set_video_refresh)(retro_video_refresh_t);
  void (*set_audio_sample)(retro_audio_sample_t);
  void (*set_audio_sample_batch)(retro_audio_sample_batch_t);
  void (*set_input_poll)(retro_input_poll_t);
  void (*set_input_state)(retro_input_state_t);
  void (*set_controller_port_device)(unsigned, unsigned);
  void (*reset)();
  void (*run)();
  size_t (*serialize_size)();
  bool (*serialize)(void*, size_t);
  bool (*unserialize)(const void*, size_t);
  bool (*load_game)(const retro_game_info*);
  void (*unload_game)();
  void* (*get_memory_data)(unsigned);
  size_t (*get_memory_size)(unsigned);
};

class Netplay;

struct Session {
  CoreApi api{};
  void* library = nullptr;
  CoreOptions options;

  std::string systemDir;
  std::string saveDir;
  std::string language = "fr";

  retro_pixel_format pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
  retro_system_av_info av{};
  bool avChanged = false;        // SET_SYSTEM_AV_INFO : fréquence audio à rouvrir
  unsigned rotation = 0;         // SET_ROTATION : quarts de tour (sens antihoraire)

  bool hwRender = false;
  retro_hw_render_callback hw{};

  bool hasDiskControl = false;
  retro_disk_control_callback disk{};
  // Types de manette proposés par le cœur pour chaque port (SET_CONTROLLER_INFO).
  std::vector<std::vector<unsigned>> controllerTypes;

  bool hasFrameTime = false;
  retro_frame_time_callback frameTime{};

  bool supportsNoGame = false;

  /** Message du cœur (SET_MESSAGE) à afficher brièvement par-dessus le jeu. */
  std::string message;
  unsigned messageFrames = 0;

  Video* video = nullptr;
  Audio* audio = nullptr;
  Input* input = nullptr;
  /** Jeu à plusieurs en réseau local : touches des joueurs pendant une image (sinon null). */
  Netplay* netplay = nullptr;
  /** Liaison entre consoles : paquets du cœur (RETRO_ENVIRONMENT_SET_NETPACKET_INTERFACE, gpSP). */
  bool hasNetpacket = false;
  retro_netpacket_callback netpacket{};
};

extern Session g;

/** Charge la DLL et ses fonctions retro_* ; message d'erreur dans [error]. */
bool loadCore(const std::string& dllPath, std::string& error);
/** Appelle retro_init et branche les callbacks (ordre de RetroArch). */
void initCore();
/** Choisit la manette de chaque port (après le chargement du jeu). */
void selectControllers(unsigned ports);
void unloadCore();
