// RomCloud : cœurs Vulkan (RETRO_HW_CONTEXT_VULKAN) dans LibretroDroid. Instance et périphérique
// Vulkan créés pour le cœur (ou par lui, interface de négociation), interface libretro_vulkan.h donnée
// au cœur, puis chaque image rendue recopiée en mémoire (BGRA) et affichée par OpenGL ES comme celle
// d'un cœur logiciel 32 bits (shaders et captures inchangés). Le chargeur libvulkan.so est ouvert au
// lancement (absent : le cœur doit utiliser OpenGL ES). Même fonctionnement que le moteur Windows
// (desktop/native/player/src/vulkan.cpp).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

#include "libretro.h"

namespace libretrodroid {

namespace vulkan {

/** Chargeur Vulkan présent (pilote graphique compatible) : sinon le cœur doit utiliser OpenGL ES. */
bool available();
/** Interface de négociation du cœur (SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE), avant [create]. */
void setNegotiation(const retro_hw_render_context_negotiation_interface* negotiation);
/** Instance, carte graphique et périphérique ; avant le context_reset du cœur. */
bool create(std::string& error);
/** Interface donnée au cœur (GET_HW_RENDER_INTERFACE), nulle avant [create]. */
const retro_hw_render_interface* hwInterface();
/** Vrai si le périphérique existe déjà (context_reset du cœur à n'appeler qu'une fois). */
bool created();
/**
 * Image donnée par le cœur pour cette image (set_image) recopiée en mémoire : [pixels] en BGRA
 * (format XRGB8888 de libretro), [pitch] octets par ligne. Faux sans image ou format illisible.
 */
bool readFrame(unsigned width, unsigned height, const uint8_t*& pixels, size_t& pitch);
/** Fin du périphérique et de l'instance (après retro_deinit). */
void destroy();

}  // namespace vulkan
}  // namespace libretrodroid
