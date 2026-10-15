// Cœurs Vulkan (RETRO_HW_CONTEXT_VULKAN) : instance et périphérique Vulkan créés pour le cœur (ou
// par lui, interface de négociation), interface libretro_vulkan.h donnée au cœur, puis chaque image
// rendue recopiée en mémoire et affichée par OpenGL comme celle d'un cœur logiciel (menu, filtres et
// captures inchangés). Le chargeur vulkan-1.dll du pilote est ouvert au lancement.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

#include "libretro.h"

namespace vulkan {

/** Chargeur Vulkan présent (pilote graphique compatible) : sinon le cœur doit utiliser OpenGL. */
bool available();
/** Interface de négociation du cœur (SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE), avant [create]. */
void setNegotiation(const retro_hw_render_context_negotiation_interface* negotiation);
/** Instance, carte graphique et périphérique ; avant le context_reset du cœur. */
bool create(std::string& error);
/** Interface donnée au cœur (GET_HW_RENDER_INTERFACE), nulle avant [create]. */
const retro_hw_render_interface* hwInterface();
/**
 * Image donnée par le cœur pour cette image (set_image) recopiée en mémoire : [pixels] (4 octets
 * par point, [bgra] : bleu en premier), [pitch] octets par ligne. Faux sans image ou format illisible.
 */
bool readFrame(unsigned width, unsigned height, const uint8_t*& pixels, size_t& pitch, bool& bgra);
/** Fin du périphérique et de l'instance (après retro_deinit). */
void destroy();

}  // namespace vulkan
