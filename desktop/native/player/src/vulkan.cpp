#include "vulkan.h"

#include <windows.h>

#include <algorithm>
#include <cstring>
#include <mutex>
#include <vector>

#include "libretro_vulkan.h"
#include "util.h"

namespace vulkan {
namespace {

// Fonctions de Vulkan, lues dans le chargeur (vulkan-1.dll), puis l'instance et le périphérique.
PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr = nullptr;
#define VK_FUNCTIONS(X)                                                                                     \
  X(vkCreateInstance) X(vkEnumerateInstanceExtensionProperties)                                             \
  X(vkDestroyInstance) X(vkEnumeratePhysicalDevices) X(vkGetPhysicalDeviceProperties)                       \
  X(vkGetPhysicalDeviceQueueFamilyProperties) X(vkGetPhysicalDeviceFeatures)                                \
  X(vkGetPhysicalDeviceMemoryProperties) X(vkGetPhysicalDeviceFormatProperties) X(vkCreateDevice)           \
  X(vkGetDeviceProcAddr) X(vkDestroyDevice) X(vkGetDeviceQueue) X(vkDeviceWaitIdle) X(vkCreateCommandPool)  \
  X(vkDestroyCommandPool) X(vkAllocateCommandBuffers) X(vkBeginCommandBuffer) X(vkEndCommandBuffer)         \
  X(vkResetCommandBuffer) X(vkCmdPipelineBarrier) X(vkCmdBlitImage) X(vkCmdCopyImageToBuffer)               \
  X(vkQueueSubmit) X(vkCreateFence) X(vkDestroyFence) X(vkWaitForFences) X(vkResetFences) X(vkCreateBuffer) \
  X(vkDestroyBuffer) X(vkGetBufferMemoryRequirements) X(vkAllocateMemory) X(vkFreeMemory)                   \
  X(vkBindBufferMemory) X(vkMapMemory) X(vkUnmapMemory) X(vkInvalidateMappedMemoryRanges) X(vkCreateImage)  \
  X(vkDestroyImage) X(vkGetImageMemoryRequirements) X(vkBindImageMemory)
#define DECLARE(name) PFN_##name name = nullptr;
VK_FUNCTIONS(DECLARE)
#undef DECLARE
PFN_vkEnumerateInstanceVersion vkEnumerateInstanceVersion = nullptr;

HMODULE loader = nullptr;
const retro_hw_render_context_negotiation_interface_vulkan* negotiation = nullptr;
bool coreDevice = false;  // périphérique créé par le cœur (create_device) : destroy_device à appeler

VkInstance instance = VK_NULL_HANDLE;
VkPhysicalDevice gpu = VK_NULL_HANDLE;
VkDevice device = VK_NULL_HANDLE;
VkQueue queue = VK_NULL_HANDLE;
uint32_t family = 0;
VkPhysicalDeviceMemoryProperties memory{};
retro_hw_render_interface_vulkan iface{};
std::mutex queueLock;

// Image de l'image en cours (set_image), attentes et commandes du cœur à soumettre avec la copie.
retro_vulkan_image image{};
bool hasImage = false;
uint32_t imageFamily = VK_QUEUE_FAMILY_IGNORED;
std::vector<VkSemaphore> waits;
std::vector<VkCommandBuffer> coreCommands;
VkSemaphore signal = VK_NULL_HANDLE;

// Copie : commandes, attente, image intermédiaire (formats autres que 8 bits RVBA) et mémoire lue.
VkCommandPool pool = VK_NULL_HANDLE;
VkCommandBuffer cmd = VK_NULL_HANDLE;
VkFence fence = VK_NULL_HANDLE;
VkBuffer staging = VK_NULL_HANDLE;
VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
VkDeviceSize stagingSize = 0;
bool stagingCoherent = true;
void* mapped = nullptr;
VkImage blitImage = VK_NULL_HANDLE;
VkDeviceMemory blitMemory = VK_NULL_HANDLE;
uint32_t blitWidth = 0, blitHeight = 0;
VkFormat blitFormat = VK_FORMAT_UNDEFINED;
VkFormat unreadable = VK_FORMAT_UNDEFINED;  // format déjà signalé illisible (journal une fois)

bool loadLoader() {
  if (vkGetInstanceProcAddr) return true;
  if (!loader) loader = LoadLibraryW(L"vulkan-1.dll");
  if (!loader) return false;
  vkGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(GetProcAddress(loader, "vkGetInstanceProcAddr"));
  if (!vkGetInstanceProcAddr) return false;
  vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(vkGetInstanceProcAddr(nullptr, "vkCreateInstance"));
  vkEnumerateInstanceExtensionProperties = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
      vkGetInstanceProcAddr(nullptr, "vkEnumerateInstanceExtensionProperties"));
  vkEnumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(vkGetInstanceProcAddr(nullptr, "vkEnumerateInstanceVersion"));
  return vkCreateInstance && vkEnumerateInstanceExtensionProperties;
}

void loadInstanceFunctions() {
#define LOAD(name) \
  if (!name) name = reinterpret_cast<PFN_##name>(vkGetInstanceProcAddr(instance, #name));
  VK_FUNCTIONS(LOAD)
#undef LOAD
}

/** Fonctions du périphérique : par vkGetDeviceProcAddr (sans passer par le chargeur à chaque appel). */
void loadDeviceFunctions() {
#define LOAD(name)                                                                           \
  if (auto f = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(device, #name))) name = f;
  VK_FUNCTIONS(LOAD)
#undef LOAD
}

bool hasInstanceExtension(const char* name) {
  uint32_t count = 0;
  vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
  std::vector<VkExtensionProperties> list(count);
  vkEnumerateInstanceExtensionProperties(nullptr, &count, list.data());
  return std::any_of(list.begin(), list.end(), [&](const VkExtensionProperties& e) { return strcmp(e.extensionName, name) == 0; });
}

/** Instance créée par le cœur (create_instance) avec les mêmes réglages que celle du moteur. */
VkInstance VKAPI_PTR createInstanceWrapper(void*, const VkInstanceCreateInfo* info) {
  VkInstance created = VK_NULL_HANDLE;
  if (vkCreateInstance(info, nullptr, &created) != VK_SUCCESS) return VK_NULL_HANDLE;
  return created;
}

VkDevice VKAPI_PTR createDeviceWrapper(VkPhysicalDevice physical, void*, const VkDeviceCreateInfo* info) {
  VkDevice created = VK_NULL_HANDLE;
  if (vkCreateDevice(physical, info, nullptr, &created) != VK_SUCCESS) return VK_NULL_HANDLE;
  return created;
}

bool createInstance(std::string& error) {
  // Version demandée par le cœur, au moins 1.1 si le chargeur la connaît (conseil de libretro_vulkan.h).
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.pApplicationName = "RomCloud";
  app.pEngineName = "RomCloud";
  app.apiVersion = VK_API_VERSION_1_1;
  if (negotiation && negotiation->get_application_info) {
    if (const VkApplicationInfo* wanted = negotiation->get_application_info()) app = *wanted;
  }
  uint32_t loaderVersion = VK_API_VERSION_1_0;
  if (vkEnumerateInstanceVersion) vkEnumerateInstanceVersion(&loaderVersion);
  if (app.apiVersion < VK_API_VERSION_1_1 && loaderVersion >= VK_API_VERSION_1_1) app.apiVersion = VK_API_VERSION_1_1;
  if (app.apiVersion > loaderVersion) app.apiVersion = loaderVersion;

  if (negotiation && negotiation->interface_version >= 2 && negotiation->create_instance) {
    instance = negotiation->create_instance(vkGetInstanceProcAddr, &app, createInstanceWrapper, nullptr);
  } else {
    std::vector<const char*> extensions;
    if (hasInstanceExtension(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME))
      extensions.push_back(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    info.enabledExtensionCount = (uint32_t)extensions.size();
    info.ppEnabledExtensionNames = extensions.data();
    if (vkCreateInstance(&info, nullptr, &instance) != VK_SUCCESS) instance = VK_NULL_HANDLE;
  }
  if (!instance) {
    error = "Vulkan : instance impossible à créer";
    return false;
  }
  loadInstanceFunctions();
  return true;
}

/** Carte graphique dédiée de préférence, sinon la première. */
VkPhysicalDevice chooseGpu() {
  uint32_t count = 0;
  vkEnumeratePhysicalDevices(instance, &count, nullptr);
  std::vector<VkPhysicalDevice> list(count);
  vkEnumeratePhysicalDevices(instance, &count, list.data());
  for (VkPhysicalDevice d : list) {
    VkPhysicalDeviceProperties p;
    vkGetPhysicalDeviceProperties(d, &p);
    if (p.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) return d;
  }
  return list.empty() ? VK_NULL_HANDLE : list[0];
}

/** Périphérique créé par le moteur : file graphique et calcul, fonctions de la carte (sans robustesse). */
bool createDefaultDevice() {
  uint32_t count = 0;
  vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, nullptr);
  std::vector<VkQueueFamilyProperties> families(count);
  vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, families.data());
  const VkQueueFlags wanted = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
  uint32_t index = UINT32_MAX;
  for (uint32_t i = 0; i < count && index == UINT32_MAX; i++) {
    if ((families[i].queueFlags & wanted) == wanted) index = i;
  }
  if (index == UINT32_MAX) return false;
  VkPhysicalDeviceFeatures features;
  vkGetPhysicalDeviceFeatures(gpu, &features);
  features.robustBufferAccess = VK_FALSE;
  const float priority = 1.0f;
  VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
  queueInfo.queueFamilyIndex = index;
  queueInfo.queueCount = 1;
  queueInfo.pQueuePriorities = &priority;
  VkDeviceCreateInfo info{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
  info.queueCreateInfoCount = 1;
  info.pQueueCreateInfos = &queueInfo;
  info.pEnabledFeatures = &features;
  if (vkCreateDevice(gpu, &info, nullptr, &device) != VK_SUCCESS) return false;
  family = index;
  vkGetDeviceQueue(device, family, 0, &queue);
  return true;
}

/** Périphérique créé par le cœur (interface de négociation) ; faux : création par le moteur. */
bool createCoreDevice() {
  if (!negotiation) return false;
  retro_vulkan_context context{};
  bool ok = false;
  if (negotiation->interface_version >= 2 && negotiation->create_device2) {
    ok = negotiation->create_device2(&context, instance, gpu, VK_NULL_HANDLE, vkGetInstanceProcAddr, createDeviceWrapper, nullptr);
    // Carte proposée refusée : le cœur choisit lui-même.
    if (!ok) ok = negotiation->create_device2(&context, instance, VK_NULL_HANDLE, VK_NULL_HANDLE, vkGetInstanceProcAddr, createDeviceWrapper, nullptr);
  } else if (negotiation->create_device) {
    VkPhysicalDeviceFeatures none{};
    ok = negotiation->create_device(&context, instance, gpu, VK_NULL_HANDLE, vkGetInstanceProcAddr, nullptr, 0, nullptr, 0, &none);
  }
  if (!ok || !context.device) return false;
  coreDevice = true;
  gpu = context.gpu ? context.gpu : gpu;
  device = context.device;
  queue = context.queue;
  family = context.queue_family_index;
  return true;
}

uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags wanted, VkMemoryPropertyFlags preferred = 0) {
  for (int pass = 0; pass < 2; pass++) {
    const VkMemoryPropertyFlags need = pass == 0 ? wanted | preferred : wanted;
    for (uint32_t i = 0; i < memory.memoryTypeCount; i++) {
      if ((bits & (1u << i)) && (memory.memoryTypes[i].propertyFlags & need) == need) return i;
    }
  }
  return UINT32_MAX;
}

void freeStaging() {
  if (mapped) vkUnmapMemory(device, stagingMemory);
  if (staging) vkDestroyBuffer(device, staging, nullptr);
  if (stagingMemory) vkFreeMemory(device, stagingMemory, nullptr);
  mapped = nullptr;
  staging = VK_NULL_HANDLE;
  stagingMemory = VK_NULL_HANDLE;
  stagingSize = 0;
}

void freeBlit() {
  if (blitImage) vkDestroyImage(device, blitImage, nullptr);
  if (blitMemory) vkFreeMemory(device, blitMemory, nullptr);
  blitImage = VK_NULL_HANDLE;
  blitMemory = VK_NULL_HANDLE;
  blitWidth = blitHeight = 0;
}

/** Mémoire lue par le processeur (en cache de préférence : lecture rapide). */
bool ensureStaging(VkDeviceSize size) {
  if (staging && stagingSize >= size) return true;
  freeStaging();
  VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
  info.size = size;
  info.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
  info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  if (vkCreateBuffer(device, &info, nullptr, &staging) != VK_SUCCESS) return false;
  VkMemoryRequirements req;
  vkGetBufferMemoryRequirements(device, staging, &req);
  const uint32_t type = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                                   VK_MEMORY_PROPERTY_HOST_CACHED_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
  if (type == UINT32_MAX) return false;
  stagingCoherent = (memory.memoryTypes[type].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
  VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  alloc.allocationSize = req.size;
  alloc.memoryTypeIndex = type;
  if (vkAllocateMemory(device, &alloc, nullptr, &stagingMemory) != VK_SUCCESS) return false;
  vkBindBufferMemory(device, staging, stagingMemory, 0);
  if (vkMapMemory(device, stagingMemory, 0, VK_WHOLE_SIZE, 0, &mapped) != VK_SUCCESS) return false;
  stagingSize = size;
  return true;
}

/** Image intermédiaire RVBA 8 bits à la taille de l'image (conversion des autres formats). */
bool ensureBlit(uint32_t width, uint32_t height, VkFormat format) {
  if (blitImage && blitWidth == width && blitHeight == height && blitFormat == format) return true;
  freeBlit();
  VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  info.imageType = VK_IMAGE_TYPE_2D;
  info.format = format;
  info.extent = {width, height, 1};
  info.mipLevels = 1;
  info.arrayLayers = 1;
  info.samples = VK_SAMPLE_COUNT_1_BIT;
  info.tiling = VK_IMAGE_TILING_OPTIMAL;
  info.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
  info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  if (vkCreateImage(device, &info, nullptr, &blitImage) != VK_SUCCESS) return false;
  VkMemoryRequirements req;
  vkGetImageMemoryRequirements(device, blitImage, &req);
  const uint32_t type = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  if (type == UINT32_MAX) return false;
  VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  alloc.allocationSize = req.size;
  alloc.memoryTypeIndex = type;
  if (vkAllocateMemory(device, &alloc, nullptr, &blitMemory) != VK_SUCCESS) return false;
  vkBindImageMemory(device, blitImage, blitMemory, 0);
  blitWidth = width;
  blitHeight = height;
  blitFormat = format;
  return true;
}

void barrier(VkImage target, const VkImageSubresourceRange& range, VkImageLayout from, VkImageLayout to, VkAccessFlags srcAccess,
             VkAccessFlags dstAccess, VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage,
             uint32_t srcFamily = VK_QUEUE_FAMILY_IGNORED, uint32_t dstFamily = VK_QUEUE_FAMILY_IGNORED) {
  VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  b.srcAccessMask = srcAccess;
  b.dstAccessMask = dstAccess;
  b.oldLayout = from;
  b.newLayout = to;
  b.srcQueueFamilyIndex = srcFamily;
  b.dstQueueFamilyIndex = dstFamily;
  b.image = target;
  b.subresourceRange = range;
  vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
}

// --- Interface donnée au cœur (libretro_vulkan.h) ---

void VKAPI_PTR setImage(void*, const retro_vulkan_image* next, uint32_t count, const VkSemaphore* semaphores, uint32_t srcFamily) {
  if (!next) {
    hasImage = false;
    return;
  }
  image = *next;
  hasImage = true;
  imageFamily = srcFamily;
  waits.assign(semaphores, semaphores + count);
}

// Une seule série de ressources : chaque image est attendue jusqu'au bout (copie lue aussitôt).
uint32_t VKAPI_PTR getSyncIndex(void*) { return 0; }
uint32_t VKAPI_PTR getSyncIndexMask(void*) { return 1; }
void VKAPI_PTR setCommandBuffers(void*, uint32_t count, const VkCommandBuffer* commands) {
  coreCommands.assign(commands, commands + count);
}
void VKAPI_PTR waitSyncIndex(void*) {}
void VKAPI_PTR lockQueue(void*) { queueLock.lock(); }
void VKAPI_PTR unlockQueue(void*) { queueLock.unlock(); }
void VKAPI_PTR setSignalSemaphore(void*, VkSemaphore semaphore) { signal = semaphore; }

bool isRgba8(VkFormat f) { return f == VK_FORMAT_R8G8B8A8_UNORM || f == VK_FORMAT_R8G8B8A8_SRGB; }
bool isBgra8(VkFormat f) { return f == VK_FORMAT_B8G8R8A8_UNORM || f == VK_FORMAT_B8G8R8A8_SRGB; }

}  // namespace

bool available() { return loadLoader(); }

void setNegotiation(const retro_hw_render_context_negotiation_interface* value) {
  negotiation = value && value->interface_type == RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN
                    ? reinterpret_cast<const retro_hw_render_context_negotiation_interface_vulkan*>(value)
                    : nullptr;
}

bool create(std::string& error) {
  if (device) return true;
  if (!loadLoader()) {
    error = "Vulkan : pilote absent (vulkan-1.dll)";
    return false;
  }
  if (!createInstance(error)) return false;
  gpu = chooseGpu();
  if (!gpu) {
    error = "Vulkan : aucune carte graphique compatible";
    return false;
  }
  if (!createCoreDevice() && !createDefaultDevice()) {
    error = "Vulkan : périphérique impossible à créer";
    return false;
  }
  loadDeviceFunctions();
  vkGetPhysicalDeviceMemoryProperties(gpu, &memory);
  VkPhysicalDeviceProperties props;
  vkGetPhysicalDeviceProperties(gpu, &props);
  logf("Vulkan %u.%u : %s (périphérique créé par %s)", VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion),
       props.deviceName, coreDevice ? "le cœur" : "le moteur");

  VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
  poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
  poolInfo.queueFamilyIndex = family;
  VkCommandBufferAllocateInfo cmdInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
  cmdInfo.commandBufferCount = 1;
  cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
  VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
  if (vkCreateCommandPool(device, &poolInfo, nullptr, &pool) != VK_SUCCESS ||
      (cmdInfo.commandPool = pool, vkAllocateCommandBuffers(device, &cmdInfo, &cmd)) != VK_SUCCESS ||
      vkCreateFence(device, &fenceInfo, nullptr, &fence) != VK_SUCCESS) {
    error = "Vulkan : commandes impossibles à créer";
    return false;
  }

  iface = {};
  iface.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
  iface.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
  iface.handle = &iface;
  iface.instance = instance;
  iface.gpu = gpu;
  iface.device = device;
  iface.get_device_proc_addr = vkGetDeviceProcAddr;
  iface.get_instance_proc_addr = vkGetInstanceProcAddr;
  iface.queue = queue;
  iface.queue_index = family;
  iface.set_image = setImage;
  iface.get_sync_index = getSyncIndex;
  iface.get_sync_index_mask = getSyncIndexMask;
  iface.set_command_buffers = setCommandBuffers;
  iface.wait_sync_index = waitSyncIndex;
  iface.lock_queue = lockQueue;
  iface.unlock_queue = unlockQueue;
  iface.set_signal_semaphore = setSignalSemaphore;
  return true;
}

const retro_hw_render_interface* hwInterface() {
  return device ? reinterpret_cast<const retro_hw_render_interface*>(&iface) : nullptr;
}

bool readFrame(unsigned width, unsigned height, const uint8_t*& pixels, size_t& pitch, bool& bgra) {
  if (!device || !hasImage || !width || !height) return false;
  const VkImage source = image.create_info.image;
  const VkFormat format = image.create_info.format;
  const VkImageLayout layout = image.image_layout;
  // Autres formats (10 bits, flottants…) : convertis par une copie vers une image RVBA 8 bits.
  const bool direct = isRgba8(format) || isBgra8(format);
  if (!direct) {
    VkFormatProperties props;
    vkGetPhysicalDeviceFormatProperties(gpu, format, &props);
    if (!(props.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_SRC_BIT)) {
      if (unreadable != format) logf("Vulkan : format d'image %d illisible", (int)format);
      unreadable = format;
      return false;
    }
  }
  if (!ensureStaging((VkDeviceSize)width * height * 4)) return false;
  if (!direct && !ensureBlit(width, height, VK_FORMAT_R8G8B8A8_UNORM)) return false;

  VkImageSubresourceRange range = image.create_info.subresourceRange;
  range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
  range.levelCount = 1;
  range.layerCount = 1;
  const VkImageSubresourceLayers layers{VK_IMAGE_ASPECT_COLOR_BIT, range.baseMipLevel, range.baseArrayLayer, 1};
  // Image d'une autre file (premières attentes seulement) : changement de propriétaire.
  const bool transfer = imageFamily != VK_QUEUE_FAMILY_IGNORED && imageFamily != family && !waits.empty();
  const uint32_t srcFamily = transfer ? imageFamily : VK_QUEUE_FAMILY_IGNORED, dstFamily = transfer ? family : VK_QUEUE_FAMILY_IGNORED;
  const VkImageLayout restore = layout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_IMAGE_LAYOUT_GENERAL : layout;

  std::lock_guard<std::mutex> lock(queueLock);
  vkResetCommandBuffer(cmd, 0);
  VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
  begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  vkBeginCommandBuffer(cmd, &begin);
  barrier(source, range, layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
          VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
          VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, srcFamily, dstFamily);
  VkBufferImageCopy copy{};
  copy.imageSubresource = layers;
  copy.imageExtent = {width, height, 1};
  if (direct) {
    vkCmdCopyImageToBuffer(cmd, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging, 1, &copy);
  } else {
    const VkImageSubresourceRange whole{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    barrier(blitImage, whole, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkImageBlit blit{};
    blit.srcSubresource = layers;
    blit.srcOffsets[1] = {(int32_t)width, (int32_t)height, 1};
    blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.dstOffsets[1] = {(int32_t)width, (int32_t)height, 1};
    vkCmdBlitImage(cmd, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, blitImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit,
                   VK_FILTER_NEAREST);
    barrier(blitImage, whole, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    vkCmdCopyImageToBuffer(cmd, blitImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging, 1, &copy);
  }
  // Image rendue au cœur dans son état d'origine (il la réutilise ensuite).
  barrier(source, range, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, restore, VK_ACCESS_TRANSFER_READ_BIT,
          VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
          VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
  VkBufferMemoryBarrier host{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
  host.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  host.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
  host.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  host.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  host.buffer = staging;
  host.size = VK_WHOLE_SIZE;
  vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0, nullptr, 1, &host, 0, nullptr);
  vkEndCommandBuffer(cmd);

  // Commandes du cœur (set_command_buffers) d'abord, puis la copie ; attentes une seule fois.
  std::vector<VkCommandBuffer> commands = coreCommands;
  commands.push_back(cmd);
  std::vector<VkPipelineStageFlags> stages(waits.size(), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
  VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
  submit.waitSemaphoreCount = (uint32_t)waits.size();
  submit.pWaitSemaphores = waits.data();
  submit.pWaitDstStageMask = stages.data();
  submit.commandBufferCount = (uint32_t)commands.size();
  submit.pCommandBuffers = commands.data();
  submit.signalSemaphoreCount = signal ? 1 : 0;
  submit.pSignalSemaphores = &signal;
  const VkResult result = vkQueueSubmit(queue, 1, &submit, fence);
  waits.clear();
  coreCommands.clear();
  signal = VK_NULL_HANDLE;
  imageFamily = VK_QUEUE_FAMILY_IGNORED;
  if (result != VK_SUCCESS) {
    logf("Vulkan : envoi des commandes refusé (%d)", (int)result);
    return false;
  }
  vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX);
  vkResetFences(device, 1, &fence);
  if (!stagingCoherent) {
    VkMappedMemoryRange mappedRange{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
    mappedRange.memory = stagingMemory;
    mappedRange.size = VK_WHOLE_SIZE;
    vkInvalidateMappedMemoryRanges(device, 1, &mappedRange);
  }
  pixels = static_cast<const uint8_t*>(mapped);
  pitch = (size_t)width * 4;
  bgra = isBgra8(format);
  return true;
}

void destroy() {
  if (device) {
    vkDeviceWaitIdle(device);
    freeStaging();
    freeBlit();
    if (fence) vkDestroyFence(device, fence, nullptr);
    if (pool) vkDestroyCommandPool(device, pool, nullptr);
    fence = VK_NULL_HANDLE;
    pool = VK_NULL_HANDLE;
    cmd = VK_NULL_HANDLE;
  }
  // Ressources propres au cœur, avant la fin du périphérique et de l'instance.
  if (instance && negotiation && negotiation->destroy_device) negotiation->destroy_device();
  if (device) vkDestroyDevice(device, nullptr);
  if (instance) vkDestroyInstance(instance, nullptr);
  device = VK_NULL_HANDLE;
  instance = VK_NULL_HANDLE;
  gpu = VK_NULL_HANDLE;
  queue = VK_NULL_HANDLE;
  hasImage = false;
  coreDevice = false;
  negotiation = nullptr;
}

}  // namespace vulkan
