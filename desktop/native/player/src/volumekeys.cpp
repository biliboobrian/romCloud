#include "volumekeys.h"

#include <windows.h>

#include <atomic>

static HHOOK hook = nullptr;
static HWND gameWindow = nullptr;
static std::atomic<int> steps{0};

/** Appelé par Windows (fil du moteur, pendant le traitement de ses messages) pour chaque touche. */
static LRESULT CALLBACK onKey(int code, WPARAM message, LPARAM data) {
  if (code == HC_ACTION && gameWindow && GetForegroundWindow() == gameWindow) {
    const auto* key = reinterpret_cast<const KBDLLHOOKSTRUCT*>(data);
    if (key->vkCode == VK_VOLUME_UP || key->vkCode == VK_VOLUME_DOWN) {
      // Appui (répété si la touche est tenue) : un pas ; relâchement ignoré. Jamais transmise à Windows.
      if (message == WM_KEYDOWN || message == WM_SYSKEYDOWN) steps += key->vkCode == VK_VOLUME_UP ? 1 : -1;
      return 1;
    }
  }
  return CallNextHookEx(hook, code, message, data);
}

void startVolumeKeys(SDL_Window* window) {
  if (hook || !window) return;
  gameWindow = static_cast<HWND>(SDL_GetPointerProperty(SDL_GetWindowProperties(window), SDL_PROP_WINDOW_WIN32_HWND_POINTER, nullptr));
  if (!gameWindow) return;
  hook = SetWindowsHookExW(WH_KEYBOARD_LL, onKey, GetModuleHandleW(nullptr), 0);
}

void stopVolumeKeys() {
  if (hook) UnhookWindowsHookEx(hook);
  hook = nullptr;
  gameWindow = nullptr;
  steps = 0;
}

int takeVolumeSteps() { return steps.exchange(0); }
