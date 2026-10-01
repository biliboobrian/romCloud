# Moteur d'émulation intégré de RomCloud pour Windows : frontend libretro natif (inspiré de
# LibretroDroid) qui charge un cœur libretro (.dll) et affiche le jeu avec SDL2 et OpenGL.
# Un seul exécutable : SDL2 et le runtime C++ sont liés statiquement.
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
set(SDL_TEST OFF CACHE BOOL "" FORCE)
set(SDL2_DISABLE_INSTALL ON CACHE BOOL "" FORCE)
FetchContent_Declare(
  SDL2
  URL https://github.com/libsdl-org/SDL/releases/download/release-2.32.10/SDL2-2.32.10.tar.gz
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(SDL2)

add_executable(romcloud-player WIN32
  src/main.cpp
  src/core.cpp
  src/options.cpp
  src/video.cpp
  src/audio.cpp
  src/input.cpp
  src/canvas.cpp
  src/menu.cpp
  src/util.cpp
  src/font8x8.c
)
target_include_directories(romcloud-player PRIVATE third_party src)
target_link_libraries(romcloud-player PRIVATE SDL2::SDL2main SDL2::SDL2-static opengl32)
target_compile_definitions(romcloud-player PRIVATE UNICODE _UNICODE NOMINMAX WIN32_LEAN_AND_MEAN)
# Exécutable directement dans build/ (pas build/Release/ avec Visual Studio) : repris par electron-builder.
set_target_properties(romcloud-player PROPERTIES RUNTIME_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}>")

if(MINGW)
  target_link_options(romcloud-player PRIVATE -static)
endif()
if(MSVC)
  # Sources en UTF-8 (textes du menu accentués).
  target_compile_options(romcloud-player PRIVATE /utf-8)
endif()
