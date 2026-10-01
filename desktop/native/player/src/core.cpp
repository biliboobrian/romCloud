#include "core.h"

#include <windows.h>

#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>

#include "audio.h"
#include "input.h"
#include "util.h"
#include "video.h"

Session g;

// ---------------------------------------------------------------------------
// Chargement de la DLL
// ---------------------------------------------------------------------------

template <typename T>
static bool bind(HMODULE lib, T& target, const char* name, std::string& missing) {
  target = reinterpret_cast<T>(reinterpret_cast<void*>(GetProcAddress(lib, name)));
  if (!target) missing = name;
  return target != nullptr;
}

bool loadCore(const std::string& dllPath, std::string& error) {
  HMODULE lib = LoadLibraryExW(widen(dllPath).c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
  if (!lib) {
    error = "LoadLibrary " + std::to_string(GetLastError());
    return false;
  }
  g.library = lib;
  CoreApi& a = g.api;
  std::string missing;
  bool ok = bind(lib, a.init, "retro_init", missing) && bind(lib, a.deinit, "retro_deinit", missing) &&
            bind(lib, a.api_version, "retro_api_version", missing) &&
            bind(lib, a.get_system_info, "retro_get_system_info", missing) &&
            bind(lib, a.get_system_av_info, "retro_get_system_av_info", missing) &&
            bind(lib, a.set_environment, "retro_set_environment", missing) &&
            bind(lib, a.set_video_refresh, "retro_set_video_refresh", missing) &&
            bind(lib, a.set_audio_sample, "retro_set_audio_sample", missing) &&
            bind(lib, a.set_audio_sample_batch, "retro_set_audio_sample_batch", missing) &&
            bind(lib, a.set_input_poll, "retro_set_input_poll", missing) &&
            bind(lib, a.set_input_state, "retro_set_input_state", missing) &&
            bind(lib, a.set_controller_port_device, "retro_set_controller_port_device", missing) &&
            bind(lib, a.reset, "retro_reset", missing) && bind(lib, a.run, "retro_run", missing) &&
            bind(lib, a.serialize_size, "retro_serialize_size", missing) &&
            bind(lib, a.serialize, "retro_serialize", missing) &&
            bind(lib, a.unserialize, "retro_unserialize", missing) &&
            bind(lib, a.load_game, "retro_load_game", missing) &&
            bind(lib, a.unload_game, "retro_unload_game", missing) &&
            bind(lib, a.get_memory_data, "retro_get_memory_data", missing) &&
            bind(lib, a.get_memory_size, "retro_get_memory_size", missing);
  if (!ok) error = "symbole absent : " + missing;
  return ok;
}

void unloadCore() {
  if (g.library) FreeLibrary(static_cast<HMODULE>(g.library));
  g.library = nullptr;
}

// ---------------------------------------------------------------------------
// Interfaces fournies au cœur
// ---------------------------------------------------------------------------

static void RETRO_CALLCONV coreLog(enum retro_log_level level, const char* fmt, ...) {
  static const char* names[] = {"DEBUG", "INFO", "WARN", "ERROR"};
  char buffer[2048];
  va_list args;
  va_start(args, fmt);
  vsnprintf(buffer, sizeof(buffer), fmt, args);
  va_end(args);
  size_t n = strlen(buffer);
  while (n > 0 && (buffer[n - 1] == '\n' || buffer[n - 1] == '\r')) buffer[--n] = '\0';
  logf("[core %s] %s", names[level < 4 ? level : 3], buffer);
}

static retro_time_t RETRO_CALLCONV perfTimeUsec() {
  using namespace std::chrono;
  return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

static uint64_t RETRO_CALLCONV perfCpuFeatures() {
  uint64_t f = 0;
  if (IsProcessorFeaturePresent(PF_XMMI_INSTRUCTIONS_AVAILABLE)) f |= RETRO_SIMD_SSE;
  if (IsProcessorFeaturePresent(PF_XMMI64_INSTRUCTIONS_AVAILABLE)) f |= RETRO_SIMD_SSE2;
  if (IsProcessorFeaturePresent(PF_SSE3_INSTRUCTIONS_AVAILABLE)) f |= RETRO_SIMD_SSE3;
  return f;
}

static retro_perf_tick_t RETRO_CALLCONV perfCounter() {
  LARGE_INTEGER c;
  QueryPerformanceCounter(&c);
  return (retro_perf_tick_t)c.QuadPart;
}

static void RETRO_CALLCONV perfRegister(struct retro_perf_counter* counter) { counter->registered = true; }
static void RETRO_CALLCONV perfStart(struct retro_perf_counter* counter) { counter->start = perfCounter(); }
static void RETRO_CALLCONV perfStop(struct retro_perf_counter* counter) {
  counter->total += perfCounter() - counter->start;
  counter->call_cnt++;
}
static void RETRO_CALLCONV perfLog() {}

static bool RETRO_CALLCONV setRumble(unsigned port, enum retro_rumble_effect effect, uint16_t strength) {
  return g.input && g.input->rumble(port, effect, strength);
}

static uintptr_t RETRO_CALLCONV currentFramebuffer() { return g.video ? g.video->currentFramebuffer() : 0; }

// ---------------------------------------------------------------------------
// Environnement
// ---------------------------------------------------------------------------

static bool RETRO_CALLCONV environment(unsigned cmd, void* data) {
  switch (cmd) {
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
      *static_cast<bool*>(data) = true;
      return true;

    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
      auto format = *static_cast<const retro_pixel_format*>(data);
      if (format > RETRO_PIXEL_FORMAT_RGB565) return false;
      g.pixelFormat = format;
      return true;
    }

    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
      *static_cast<const char**>(data) = g.systemDir.c_str();
      return true;

    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
      *static_cast<const char**>(data) = g.saveDir.c_str();
      return true;

    case RETRO_ENVIRONMENT_SET_VARIABLES:
      g.options.declare(static_cast<const retro_variable*>(data));
      return true;

    // Options v1 / v2 : libellés des valeurs, valeur par défaut, traductions et catégories (onglets).
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
      *static_cast<unsigned*>(data) = 2;
      return true;

    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
      g.options.declare(static_cast<const retro_core_option_definition*>(data), nullptr);
      return true;

    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: {
      const auto* intl = static_cast<const retro_core_options_intl*>(data);
      g.options.declare(intl->us, intl->local);
      return true;
    }

    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
      g.options.declare(static_cast<const retro_core_options_v2*>(data), nullptr);
      return true;

    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
      const auto* intl = static_cast<const retro_core_options_v2_intl*>(data);
      g.options.declare(intl->us, intl->local);
      return true;
    }

    case RETRO_ENVIRONMENT_GET_VARIABLE: {
      auto* var = static_cast<retro_variable*>(data);
      var->value = var->key ? g.options.get(var->key) : nullptr;
      return var->value != nullptr;
    }

    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
      *static_cast<bool*>(data) = g.options.takeUpdated();
      return true;

    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
      return true;

    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
      static_cast<retro_log_callback*>(data)->log = coreLog;
      return true;

    case RETRO_ENVIRONMENT_GET_PERF_INTERFACE: {
      auto* perf = static_cast<retro_perf_callback*>(data);
      perf->get_time_usec = perfTimeUsec;
      perf->get_cpu_features = perfCpuFeatures;
      perf->get_perf_counter = perfCounter;
      perf->perf_register = perfRegister;
      perf->perf_start = perfStart;
      perf->perf_stop = perfStop;
      perf->perf_log = perfLog;
      return true;
    }

    case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
      *static_cast<unsigned*>(data) = RETRO_HW_CONTEXT_OPENGL;
      return true;

    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
      auto* hw = static_cast<retro_hw_render_callback*>(data);
      // OpenGL de bureau seulement (ni Vulkan, ni Direct3D, ni OpenGL ES).
      if (hw->context_type != RETRO_HW_CONTEXT_OPENGL && hw->context_type != RETRO_HW_CONTEXT_OPENGL_CORE) {
        logf("Rendu matériel %d non pris en charge", (int)hw->context_type);
        return false;
      }
      hw->get_current_framebuffer = currentFramebuffer;
      hw->get_proc_address = Video::procAddress;
      g.hw = *hw;
      g.hwRender = true;
      return true;
    }

    case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
      return true;

    case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
      static_cast<retro_rumble_interface*>(data)->set_rumble_state = setRumble;
      return true;

    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
      return true;

    case RETRO_ENVIRONMENT_SET_ROTATION:
      g.rotation = *static_cast<const unsigned*>(data) & 3;
      return true;

    case RETRO_ENVIRONMENT_SET_GEOMETRY: {
      const auto* geometry = static_cast<const retro_game_geometry*>(data);
      g.av.geometry.base_width = geometry->base_width;
      g.av.geometry.base_height = geometry->base_height;
      g.av.geometry.aspect_ratio = geometry->aspect_ratio;
      return true;
    }

    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
      g.av = *static_cast<const retro_system_av_info*>(data);
      g.avChanged = true;
      if (g.video) g.video->onGeometryChanged();
      return true;

    case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
      g.disk = *static_cast<const retro_disk_control_callback*>(data);
      g.hasDiskControl = true;
      return true;

    case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK:
      g.frameTime = *static_cast<const retro_frame_time_callback*>(data);
      g.hasFrameTime = true;
      return true;

    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
      g.supportsNoGame = *static_cast<const bool*>(data);
      return true;

    case RETRO_ENVIRONMENT_GET_LANGUAGE:
      *static_cast<unsigned*>(data) = g.language == "fr" ? RETRO_LANGUAGE_FRENCH : RETRO_LANGUAGE_ENGLISH;
      return true;

    case RETRO_ENVIRONMENT_GET_USERNAME:
      *static_cast<const char**>(data) = "RomCloud";
      return true;

    case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
      *static_cast<int*>(data) = 3;  // vidéo et audio
      return true;

    case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
      *static_cast<bool*>(data) = false;
      return true;

    case RETRO_ENVIRONMENT_SET_MESSAGE: {
      const auto* msg = static_cast<const retro_message*>(data);
      if (msg->msg) {
        logf("[message] %s", msg->msg);
        g.message = msg->msg;
        g.messageFrames = msg->frames ? msg->frames : 180;
      }
      return true;
    }

    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
    case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
    case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
      return true;

    default:
      return false;
  }
}

// ---------------------------------------------------------------------------
// Vidéo, audio, entrées
// ---------------------------------------------------------------------------

static void RETRO_CALLCONV videoRefresh(const void* data, unsigned width, unsigned height, size_t pitch) {
  if (g.video) g.video->onFrame(data, width, height, pitch);
}

static void RETRO_CALLCONV audioSample(int16_t left, int16_t right) {
  int16_t frame[2] = {left, right};
  if (g.audio) g.audio->push(frame, 1);
}

static size_t RETRO_CALLCONV audioSampleBatch(const int16_t* data, size_t frames) {
  if (g.audio) g.audio->push(data, frames);
  return frames;
}

static void RETRO_CALLCONV inputPoll() {
  // L'état des manettes est tenu à jour par SDL dans la boucle principale.
}

static int16_t RETRO_CALLCONV inputState(unsigned port, unsigned device, unsigned index, unsigned id) {
  return g.input ? g.input->state(port, device, index, id) : 0;
}

void initCore() {
  g.api.set_environment(environment);
  g.api.set_video_refresh(videoRefresh);
  g.api.set_audio_sample(audioSample);
  g.api.set_audio_sample_batch(audioSampleBatch);
  g.api.set_input_poll(inputPoll);
  g.api.set_input_state(inputState);
  g.api.init();
}
