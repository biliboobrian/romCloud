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
/**
 * Image partagée avec OpenGL (sans recopie par le processeur) : handles Windows à importer par
 * OpenGL (puis à fermer) : [memory] (nouvelle image : première, ou taille changée), [ready] et
 * [released] (sémaphores, la première fois) ; nuls s'il n'y a rien de neuf. [bgra] : rouge et bleu
 * échangés dans l'image.
 */
struct SharedFrame {
  void* memory = nullptr;
  uint64_t memorySize = 0;
  void* ready = nullptr;
  void* released = nullptr;
  unsigned width = 0, height = 0, generation = 0;
  bool bgra = false;
};
/** Partage possible : extensions activées et même carte graphique qu'OpenGL ([uuid] : GL_DEVICE_UUID_EXT). */
bool interopSupported(const uint8_t uuid[16]);
/**
 * Image du cœur copiée dans l'image partagée (sur la carte graphique) ; « image prête » signalé à la
 * fin, que OpenGL attend avant de la lire. Faux : partage impossible (recopie par [readFrame]).
 */
bool shareFrame(unsigned width, unsigned height, SharedFrame& out);
/** OpenGL a signalé « image rendue » : attendu avant la copie suivante dans l'image partagée. */
void sharedReleased();
/** Fin du partage (retour à la recopie) : image et sémaphores libérés. */
void stopSharing();
/** Fin du périphérique et de l'instance (après retro_deinit). */
void destroy();

}  // namespace vulkan
