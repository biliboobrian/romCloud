# Moteur d'émulation intégré de RomCloud pour Windows : frontend libretro natif (inspiré de
# LibretroDroid) qui charge un cœur libretro (.dll) et affiche le jeu avec SDL3 et OpenGL (cœurs
# OpenGL ou Vulkan : image Vulkan recopiée dans l'affichage OpenGL).
# Un seul exécutable : SDL3 et le runtime C++ sont liés statiquement.
cmake_minimum_required(VERSION 3.20)
project(romcloud_player C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
if(MSVC)
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

include(FetchContent)
set(SDL_SHARED OFF CACHE BOOL "" FORCE)
set(SDL_STATIC ON CACHE BOOL "" FORCE)
set(SDL_TEST_LIBRARY OFF CACHE BOOL "" FORCE)
set(SDL_DISABLE_INSTALL ON CACHE BOOL "" FORCE)
FetchContent_Declare(
  SDL3
  URL https://github.com/libsdl-org/SDL/releases/download/release-3.4.18/SDL3-3.4.18.tar.gz
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(SDL3)
# En-têtes de Vulkan seulement (cœurs Vulkan) : le chargeur vulkan-1.dll du pilote est ouvert au
# lancement, sans lien à la compilation (VK_NO_PROTOTYPES).
FetchContent_Declare(
  VulkanHeaders
  URL https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.3.296.tar.gz
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
set(VULKAN_HEADERS_ENABLE_MODULE OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(VulkanHeaders)

add_executable(romcloud-player WIN32
  src/main.cpp
  src/core.cpp
  src/options.cpp
  src/video.cpp
  src/vulkan.cpp
  src/audio.cpp
  src/input.cpp
  src/canvas.cpp
  src/menu.cpp
  src/history.cpp
  src/padconfig.cpp
  src/players.cpp
  src/netplay.cpp
  src/util.cpp
  src/volumekeys.cpp
  src/font8x8.c
  # Shaders CUT, CUT2 et CUT3 : sources de LibretroDroid (Android), partagées.
  ../../../android/libretrodroid/src/main/cpp/shadermanager.cpp
)
target_include_directories(romcloud-player PRIVATE third_party src)
target_link_libraries(romcloud-player PRIVATE SDL3::SDL3-static Vulkan::Headers opengl32 ws2_32 iphlpapi)
target_compile_definitions(romcloud-player PRIVATE UNICODE _UNICODE NOMINMAX WIN32_LEAN_AND_MEAN VK_NO_PROTOTYPES)
# Exécutable directement dans build/ (pas build/Release/ avec Visual Studio) : repris par l'installeur.
set_target_properties(romcloud-player PROPERTIES RUNTIME_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}>")

if(MINGW)
  # -municode : point d'entrée wWinMain fourni par SDL3 (UNICODE défini).
  target_link_options(romcloud-player PRIVATE -static -municode)
endif()
if(MSVC)
  # Sources en UTF-8 (textes du menu accentués).
  target_compile_options(romcloud-player PRIVATE /utf-8)
  # Relais d'appel du cœur (registres préservés de Windows) : assembleur MASM, Visual Studio
  # n'acceptant pas l'assembleur dans le C++ en 64 bits (version GNU dans core.cpp).
  enable_language(ASM_MASM)
  target_sources(romcloud-player PRIVATE src/call_preserving_msvc.asm)
endif()
