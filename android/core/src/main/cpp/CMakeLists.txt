cmake_minimum_required(VERSION 3.22.1)
project(romcloud_audio_shim CXX)

# Adaptateur des cœurs libretro : son échantillon par échantillon, affichage de l'image (voir audio_shim.cpp).
add_library(romcloud_audio_shim SHARED audio_shim.cpp)
set_target_properties(romcloud_audio_shim PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_compile_options(romcloud_audio_shim PRIVATE -fno-exceptions -fno-rtti)
target_link_libraries(romcloud_audio_shim log dl GLESv3)
