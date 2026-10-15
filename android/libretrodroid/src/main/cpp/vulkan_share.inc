// RomCloud : image partagée avec OpenGL ES (inclus dans vulkan.cpp, dans son espace de noms
// anonyme). Deux AHardwareBuffer utilisés à tour de rôle (l'un affiché pendant que l'autre est
// rempli), importés comme images Vulkan : l'image du cœur y est copiée sur la carte graphique, puis
// OpenGL ES les lit comme textures (EGLImage), sans recopie par le processeur. La propriété passe de
// Vulkan à la file « étrangère » (OpenGL ES) à chaque image.

struct SharedSlot {
  AHardwareBuffer* buffer = nullptr;
  VkImage image = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
  bool used = false;  // déjà donnée à OpenGL ES (propriété à reprendre)
};
SharedSlot slots[kSharedSlots];
uint32_t sharedWidth = 0, sharedHeight = 0;
unsigned sharedGeneration = 0;

void freeSlots() {
  for (SharedSlot& slot : slots) {
    if (slot.image) vkDestroyImage(device, slot.image, nullptr);
    if (slot.memory) vkFreeMemory(device, slot.memory, nullptr);
    if (slot.buffer) AHardwareBuffer_release(slot.buffer);
    slot = SharedSlot{};
  }
  sharedWidth = sharedHeight = 0;
}

/** Extensions de partage avec OpenGL ES que la carte propose (Vulkan 1.1 minimum), vide sinon. */
std::vector<const char*> interopExtensions(VkPhysicalDevice physical) {
  if (!physical || !vkEnumerateDeviceExtensionProperties) return {};
  VkPhysicalDeviceProperties props;
  vkGetPhysicalDeviceProperties(physical, &props);
  if (props.apiVersion < VK_API_VERSION_1_1) return {};
  uint32_t count = 0;
  vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, nullptr);
  std::vector<VkExtensionProperties> list(count);
  vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, list.data());
  std::vector<const char*> wanted;
  for (const char* name : kInteropExtensions) {
    if (std::none_of(list.begin(), list.end(), [&](const VkExtensionProperties& e) { return strcmp(e.extensionName, name) == 0; })) return {};
    wanted.push_back(name);
  }
  return wanted;
}

/** AHardwareBuffer RVBA 8 bits à la taille de l'image, importé comme image Vulkan (copie vers lui). */
bool createSlot(SharedSlot& slot, uint32_t width, uint32_t height) {
  AHardwareBuffer_Desc desc{};
  desc.width = width;
  desc.height = height;
  desc.layers = 1;
  desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
  desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
  if (AHardwareBuffer_allocate(&desc, &slot.buffer) != 0) return false;
  VkAndroidHardwareBufferFormatPropertiesANDROID format{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
  VkAndroidHardwareBufferPropertiesANDROID props{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
  props.pNext = &format;
  if (vkGetAndroidHardwareBufferPropertiesANDROID(device, slot.buffer, &props) != VK_SUCCESS) return false;
  if (format.format != VK_FORMAT_R8G8B8A8_UNORM) return false;
  VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
  external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
  VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  info.pNext = &external;
  info.imageType = VK_IMAGE_TYPE_2D;
  info.format = VK_FORMAT_R8G8B8A8_UNORM;
  info.extent = {width, height, 1};
  info.mipLevels = 1;
  info.arrayLayers = 1;
  info.samples = VK_SAMPLE_COUNT_1_BIT;
  info.tiling = VK_IMAGE_TILING_OPTIMAL;
  info.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
  info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  if (vkCreateImage(device, &info, nullptr, &slot.image) != VK_SUCCESS) return false;
  uint32_t type = UINT32_MAX;
  for (uint32_t i = 0; i < 32 && type == UINT32_MAX; i++) {
    if (props.memoryTypeBits & (1u << i)) type = i;
  }
  if (type == UINT32_MAX) return false;
  VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
  dedicated.image = slot.image;
  VkImportAndroidHardwareBufferInfoANDROID import{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
  import.pNext = &dedicated;
  import.buffer = slot.buffer;
  VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  alloc.pNext = &import;
  alloc.allocationSize = props.allocationSize;
  alloc.memoryTypeIndex = type;
  if (vkAllocateMemory(device, &alloc, nullptr, &slot.memory) != VK_SUCCESS) return false;
  return vkBindImageMemory(device, slot.image, slot.memory, 0) == VK_SUCCESS;
}

/** Deux tampons à la taille exacte de l'image (le moteur affiche toute la texture). */
bool ensureSlots(uint32_t width, uint32_t height) {
  if (slots[0].image && sharedWidth == width && sharedHeight == height) return true;
  vkDeviceWaitIdle(device);
  freeSlots();
  for (SharedSlot& slot : slots) {
    if (!createSlot(slot, width, height)) {
      freeSlots();
      return false;
    }
  }
  sharedWidth = width;
  sharedHeight = height;
  sharedGeneration++;
  return true;
}
