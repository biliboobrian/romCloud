cmake_minimum_required(VERSION 3.22.1)
project(romcloud_audio_shim CXX)

# Adaptateur audio des cœurs libretro qui envoient leur son échantillon par échantillon (voir audio_shim.cpp).
add_library(romcloud_audio_shim SHARED audio_shim.cpp)
set_target_properties(romcloud_audio_shim PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_compile_options(romcloud_audio_shim PRIVATE -fno-exceptions -fno-rtti)
target_link_libraries(romcloud_audio_shim log dl)
