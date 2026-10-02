// Adaptateur chargé par LibretroDroid à la place d'un cœur libretro qui envoie son son
// échantillon par échantillon (retro_audio_sample_t, comme cap32) : LibretroDroid ignore ce
// canal et ne lit que les lots (retro_audio_sample_batch_t). Le cœur réel, dont le chemin est
// dans la variable d'environnement ROMCLOUD_SHIM_CORE, reçoit toutes les fonctions telles
// quelles ; les échantillons reçus pendant une image sont renvoyés en un lot à la fin de retro_run.
// Pas de bibliothèque C++ (ni STL, ni variable statique locale) : rien d'autre à embarquer.

#include <android/log.h>
#include <dlfcn.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>

#define LOG_TAG "RomCloudAudioShim"
#define EXPORT extern "C" __attribute__((visibility("default")))

typedef void (*audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef bool (*environment_t)(unsigned cmd, void *data);
typedef void (*video_refresh_t)(const void *data, unsigned width, unsigned height, size_t pitch);
typedef void (*input_poll_t)();
typedef int16_t (*input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);
struct retro_system_info;
struct retro_system_av_info;
struct retro_game_info;

static void *core = nullptr;

/** Fonction du cœur réel, chargé au premier appel ; arrêt net s'il est introuvable. */
static void *symbol(const char *name) {
    if (!core) {
        const char *path = getenv("ROMCLOUD_SHIM_CORE");
        core = path ? dlopen(path, RTLD_LOCAL | RTLD_NOW) : nullptr;
        if (!core) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "dlopen %s: %s", path ? path : "(null)", dlerror());
            abort();
        }
    }
    void *f = dlsym(core, name);
    if (!f) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "dlsym %s: %s", name, dlerror());
        abort();
    }
    return f;
}

// real_<nom>() : fonction <nom> du cœur réel, résolue au premier appel.
#define CORE_FN(ret, name, params)                                                         \
    typedef ret (*name##_fn) params;                                                       \
    static name##_fn core_##name = nullptr;                                                \
    static name##_fn real_##name() {                                                       \
        if (!core_##name) core_##name = reinterpret_cast<name##_fn>(symbol(#name));        \
        return core_##name;                                                                \
    }

// Fonction transmise telle quelle au cœur réel.
#define FORWARD(ret, name, params, args) \
    CORE_FN(ret, name, params)           \
    EXPORT ret name params { return real_##name() args; }

// Échantillons (gauche, droite entrelacés) reçus depuis le dernier lot envoyé.
static int16_t *pending = nullptr;
static size_t pending_frames = 0;
static size_t pending_capacity = 0;
static audio_sample_batch_t batch = nullptr;

static void flush() {
    if (pending_frames && batch) batch(pending, pending_frames);
    pending_frames = 0;
}

static void on_sample(int16_t left, int16_t right) {
    if (pending_frames == pending_capacity) {
        size_t capacity = pending_capacity ? pending_capacity * 2 : 4096;
        auto grown = static_cast<int16_t *>(realloc(pending, capacity * 2 * sizeof(int16_t)));
        if (!grown) return;
        pending = grown;
        pending_capacity = capacity;
    }
    pending[pending_frames * 2] = left;
    pending[pending_frames * 2 + 1] = right;
    pending_frames++;
}

/** Lot envoyé par le cœur lui-même : après les échantillons isolés reçus avant lui. */
static size_t on_batch(const int16_t *data, size_t frames) {
    flush();
    return batch ? batch(data, frames) : frames;
}

CORE_FN(void, retro_set_audio_sample, (audio_sample_t cb))
EXPORT void retro_set_audio_sample(audio_sample_t) {
    real_retro_set_audio_sample()(on_sample);
}

CORE_FN(void, retro_set_audio_sample_batch, (audio_sample_batch_t cb))
EXPORT void retro_set_audio_sample_batch(audio_sample_batch_t cb) {
    batch = cb;
    real_retro_set_audio_sample_batch()(on_batch);
}

CORE_FN(void, retro_run, ())
EXPORT void retro_run() {
    real_retro_run()();
    flush();
}

CORE_FN(void, retro_unload_game, ())
EXPORT void retro_unload_game() {
    real_retro_unload_game()();
    pending_frames = 0;
}

FORWARD(void, retro_init, (), ())
FORWARD(void, retro_deinit, (), ())
FORWARD(unsigned, retro_api_version, (), ())
FORWARD(void, retro_get_system_info, (retro_system_info *info), (info))
FORWARD(void, retro_get_system_av_info, (retro_system_av_info *info), (info))
FORWARD(void, retro_set_controller_port_device, (unsigned port, unsigned device), (port, device))
FORWARD(void, retro_reset, (), ())
FORWARD(size_t, retro_serialize_size, (), ())
FORWARD(bool, retro_serialize, (void *data, size_t size), (data, size))
FORWARD(bool, retro_unserialize, (const void *data, size_t size), (data, size))
FORWARD(void, retro_cheat_reset, (), ())
FORWARD(void, retro_cheat_set, (unsigned index, bool enabled, const char *code), (index, enabled, code))
FORWARD(bool, retro_load_game, (const retro_game_info *game), (game))
FORWARD(bool, retro_load_game_special, (unsigned type, const retro_game_info *info, size_t num), (type, info, num))
FORWARD(unsigned, retro_get_region, (), ())
FORWARD(void *, retro_get_memory_data, (unsigned id), (id))
FORWARD(size_t, retro_get_memory_size, (unsigned id), (id))
FORWARD(void, retro_set_environment, (environment_t cb), (cb))
FORWARD(void, retro_set_video_refresh, (video_refresh_t cb), (cb))
FORWARD(void, retro_set_input_poll, (input_poll_t cb), (cb))
FORWARD(void, retro_set_input_state, (input_state_t cb), (cb))
