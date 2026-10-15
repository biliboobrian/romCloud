// Partage de l'image avec OpenGL (inclus dans vulkan.cpp, dans son espace de noms anonyme) : image
// à la mémoire exportée (handle Windows), copie de l'image du cœur dedans, sémaphores échangés avec
// OpenGL. La propriété de l'image passe d'une API à l'autre (file « externe ») à chaque image.

void freeShared() {
  if (sharedImage) vkDestroyImage(device, sharedImage, nullptr);
  if (sharedMemory) vkFreeMemory(device, sharedMemory, nullptr);
  sharedImage = VK_NULL_HANDLE;
  sharedMemory = VK_NULL_HANDLE;
  sharedWidth = sharedHeight = 0;
  sharedUsed = false;
}

void freeSemaphores() {
  if (readySemaphore) vkDestroySemaphore(device, readySemaphore, nullptr);
  if (releasedSemaphore) vkDestroySemaphore(device, releasedSemaphore, nullptr);
  readySemaphore = releasedSemaphore = VK_NULL_HANDLE;
  semaphoresExported = false;
  releasedPending = false;
}

VkSemaphore exportableSemaphore() {
  VkExportSemaphoreCreateInfo exportInfo{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
  exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
  VkSemaphoreCreateInfo info{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
  info.pNext = &exportInfo;
  VkSemaphore semaphore = VK_NULL_HANDLE;
  return vkCreateSemaphore(device, &info, nullptr, &semaphore) == VK_SUCCESS ? semaphore : VK_NULL_HANDLE;
}

HANDLE semaphoreHandle(VkSemaphore semaphore) {
  VkSemaphoreGetWin32HandleInfoKHR info{VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR};
  info.semaphore = semaphore;
  info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
  HANDLE handle = nullptr;
  return vkGetSemaphoreWin32HandleKHR(device, &info, &handle) == VK_SUCCESS ? handle : nullptr;
}

/**
 * Image partagée RVBA 8 bits, mémoire dédiée exportable : au moins 256 x 256 (les toutes petites
 * images des premières trames ne s'importent pas dans OpenGL), arrondie à 128 pixels et agrandie
 * seulement si l'image du jeu la dépasse (changements de résolution sans nouvelle image).
 */
bool ensureShared(uint32_t frameWidth, uint32_t frameHeight) {
  if (sharedImage && frameWidth <= sharedWidth && frameHeight <= sharedHeight) return true;
  const auto round = [](uint32_t v) { return std::max(256u, (v + 127u) / 128u * 128u); };
  const uint32_t width = std::max(round(frameWidth), sharedWidth), height = std::max(round(frameHeight), sharedHeight);
  vkDeviceWaitIdle(device);
  freeShared();
  VkExternalMemoryImageCreateInfo external{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
  external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
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
  if (vkCreateImage(device, &info, nullptr, &sharedImage) != VK_SUCCESS) return false;
  VkMemoryRequirements req;
  vkGetImageMemoryRequirements(device, sharedImage, &req);
  const uint32_t type = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  if (type == UINT32_MAX) return false;
  VkMemoryDedicatedAllocateInfo dedicated{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
  dedicated.image = sharedImage;
  VkExportMemoryAllocateInfo exportInfo{VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO};
  exportInfo.pNext = &dedicated;
  exportInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
  VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  alloc.pNext = &exportInfo;
  alloc.allocationSize = req.size;
  alloc.memoryTypeIndex = type;
  if (vkAllocateMemory(device, &alloc, nullptr, &sharedMemory) != VK_SUCCESS) return false;
  if (vkBindImageMemory(device, sharedImage, sharedMemory, 0) != VK_SUCCESS) return false;
  sharedSize = req.size;
  sharedWidth = width;
  sharedHeight = height;
  sharedGeneration++;
  return true;
}
