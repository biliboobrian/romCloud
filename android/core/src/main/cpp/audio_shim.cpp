// Adaptateur chargé par LibretroDroid à la place d'un cœur libretro, dont le chemin est dans la
// variable d'environnement ROMCLOUD_SHIM_CORE ; le cœur réel reçoit toutes les fonctions telles
// quelles, sauf :
// - son échantillon par échantillon (retro_audio_sample_t, comme cap32) : LibretroDroid ignore ce
//   canal et ne lit que les lots (retro_audio_sample_batch_t) ; les échantillons reçus pendant une
//   image sont renvoyés en un lot à la fin de retro_run ;
// - image (ROMCLOUD_SHIM_BLIT=1, flycast) : le cœur dessine bien dans le framebuffer de
//   LibretroDroid, mais l'affichage de ce framebuffer par LibretroDroid reste noir (état OpenGL
//   laissé par le cœur). Après l'affichage de LibretroDroid, l'image est copiée à l'écran
//   (glBlitFramebuffer), centrée à ses proportions dans la zone d'affichage de LibretroDroid ;
// - boutons lus d'un coup (RETRO_DEVICE_ID_JOYPAD_MASK, comme LRPS2) : LibretroDroid traite cette
//   demande comme un bouton (toujours relâché) ; le masque est reconstitué bouton par bouton ;
// - diffusion sur une TV (tous les cœurs passent alors par l'adaptateur) : le son est copié dans une
//   file lue par l'application (StreamTap, JNI) et peut être coupé sur le téléphone (silence envoyé à
//   LibretroDroid, même cadence d'émulation) ; proportions de l'image et fréquence du son exposées.
// Pas de bibliothèque C++ (ni STL, ni variable statique locale) : rien d'autre à embarquer.

#include <GLES3/gl3.h>
#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "RomCloudShim"
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

// Diffusion : copie du son (section « Diffusion » en fin de fichier) et son coupé sur le téléphone.
static void tap(const int16_t *data, size_t frames);
static bool mute_local = false;
static int16_t *silence = nullptr;
static size_t silence_frames = 0;

/** Lot transmis à LibretroDroid (silence de même durée si le son est coupé sur le téléphone). */
static size_t deliver(const int16_t *data, size_t frames) {
    tap(data, frames);
    if (__atomic_load_n(&mute_local, __ATOMIC_RELAXED)) {
        if (frames > silence_frames) {
            auto grown = static_cast<int16_t *>(realloc(silence, frames * 2 * sizeof(int16_t)));
            if (grown) {
                silence = grown;
                memset(silence, 0, frames * 2 * sizeof(int16_t));
                silence_frames = frames;
            }
        }
        if (frames <= silence_frames) data = silence;
    }
    return batch ? batch(data, frames) : frames;
}

static void flush() {
    if (pending_frames) deliver(pending, pending_frames);
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
    return deliver(data, frames);
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
// --- Image (ROMCLOUD_SHIM_BLIT) ---

// Structures de libretro.h utilisées ici (même disposition).
struct retro_game_geometry {
    unsigned base_width, base_height, max_width, max_height;
    float aspect_ratio;
};
struct retro_system_timing {
    double fps, sample_rate;
};
struct retro_av_info {
    retro_game_geometry geometry;
    retro_system_timing timing;
};
static double sample_rate = 0;
typedef uintptr_t (*get_current_framebuffer_t)();
struct retro_hw_render_callback {
    int context_type;
    void (*context_reset)();
    get_current_framebuffer_t get_current_framebuffer;
    void *(*get_proc_address)(const char *sym);
    bool depth, stencil, bottom_left_origin;
    unsigned version_major, version_minor;
    bool cache_context;
    void (*context_destroy)();
    bool debug_context;
};
const unsigned SET_HW_RENDER = 14, SET_SYSTEM_AV_INFO = 32, SET_GEOMETRY = 37;

static environment_t frontend_environment = nullptr;
static video_refresh_t frontend_video = nullptr;
static get_current_framebuffer_t frontend_framebuffer = nullptr;
static bool bottom_left_origin = true;
static float aspect_ratio = 0;

static bool blit_enabled() {
    const char *value = getenv("ROMCLOUD_SHIM_BLIT");
    return value && value[0] == '1';
}

static void on_geometry(const retro_game_geometry *geometry) {
    aspect_ratio = geometry->aspect_ratio > 0 ? geometry->aspect_ratio
        : geometry->base_height ? (float)geometry->base_width / (float)geometry->base_height : 0;
}

static bool on_environment(unsigned cmd, void *data) {
    bool handled = frontend_environment(cmd, data);
    if (!handled || !data) return handled;
    if (cmd == SET_HW_RENDER) {
        auto *hw = static_cast<retro_hw_render_callback *>(data);
        frontend_framebuffer = hw->get_current_framebuffer;
        bottom_left_origin = hw->bottom_left_origin;
    } else if (cmd == SET_SYSTEM_AV_INFO || cmd == SET_GEOMETRY) {
        // retro_system_av_info commence par sa géométrie.
        on_geometry(static_cast<const retro_game_geometry *>(data));
        if (cmd == SET_SYSTEM_AV_INFO) sample_rate = static_cast<const retro_av_info *>(data)->timing.sample_rate;
    }
    return handled;
}

/** Copie l'image du cœur ([width] x [height] dans son framebuffer) sur l'écran. */
static void blit_frame(unsigned width, unsigned height) {
    GLint viewport[4] = {0};
    glGetIntegerv(GL_VIEWPORT, viewport);  // zone d'affichage choisie par LibretroDroid
    int areaWidth = viewport[2], areaHeight = viewport[3];
    if (areaWidth <= 0 || areaHeight <= 0 || !width || !height) return;
    float aspect = aspect_ratio > 0 ? aspect_ratio : (float)width / (float)height;
    int w = areaWidth, h = (int)((float)areaWidth / aspect);
    if (h > areaHeight) {
        h = areaHeight;
        w = (int)((float)areaHeight * aspect);
    }
    int x = viewport[0] + (areaWidth - w) / 2, y = viewport[1] + (areaHeight - h) / 2;
    GLint read = 0, draw = 0;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)frontend_framebuffer());
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glDisable(GL_SCISSOR_TEST);
    // Origine en haut à gauche : image retournée.
    GLint top = bottom_left_origin ? (GLint)height : 0, bottom = bottom_left_origin ? 0 : (GLint)height;
    glBlitFramebuffer(0, bottom, (GLint)width, top, x, y, x + w, y + h, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
}

static void on_video(const void *data, unsigned width, unsigned height, size_t pitch) {
    frontend_video(data, width, height, pitch);
    // Image en double (data == nullptr) : LibretroDroid redessine aussi l'écran, copie refaite.
    if (frontend_framebuffer && blit_enabled()) blit_frame(width, height);
}

CORE_FN(void, retro_set_environment, (environment_t cb))
EXPORT void retro_set_environment(environment_t cb) {
    frontend_environment = cb;
    real_retro_set_environment()(on_environment);
}

CORE_FN(void, retro_set_video_refresh, (video_refresh_t cb))
EXPORT void retro_set_video_refresh(video_refresh_t cb) {
    frontend_video = cb;
    real_retro_set_video_refresh()(on_video);
}

CORE_FN(void, retro_get_system_av_info, (retro_system_av_info *info))
EXPORT void retro_get_system_av_info(retro_system_av_info *info) {
    real_retro_get_system_av_info()(info);
    // retro_system_av_info commence par sa géométrie.
    on_geometry(reinterpret_cast<const retro_game_geometry *>(info));
    sample_rate = reinterpret_cast<const retro_av_info *>(info)->timing.sample_rate;
}
FORWARD(void, retro_set_input_poll, (input_poll_t cb), (cb))
// --- Boutons lus d'un coup (RETRO_DEVICE_ID_JOYPAD_MASK) ---

const unsigned DEVICE_JOYPAD = 1, DEVICE_TYPE_MASK = 0xff, JOYPAD_MASK = 256, JOYPAD_BUTTONS = 16;

static input_state_t frontend_input = nullptr;

static int16_t on_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if ((device & DEVICE_TYPE_MASK) == DEVICE_JOYPAD && id == JOYPAD_MASK) {
        int16_t mask = 0;
        for (unsigned button = 0; button < JOYPAD_BUTTONS; button++) {
            if (frontend_input(port, DEVICE_JOYPAD, index, button)) mask |= (int16_t)(1 << button);
        }
        return mask;
    }
    return frontend_input(port, device, index, id);
}

CORE_FN(void, retro_set_input_state, (input_state_t cb))
EXPORT void retro_set_input_state(input_state_t cb) {
    frontend_input = cb;
    real_retro_set_input_state()(on_input_state);
}

// --- Diffusion sur une TV (StreamTap) ---

// File circulaire du son copié (gauche, droite entrelacés) : écrite par le thread d'émulation, lue
// par le thread de diffusion ; pleine, les nouveaux échantillons sont perdus.
const size_t RING_SAMPLES = 1 << 17;  // ~1,4 s en stéréo à 48 kHz
static int16_t ring[RING_SAMPLES];
static size_t ring_write = 0, ring_read = 0;  // compteurs croissants (modulo RING_SAMPLES à l'usage)
static bool capturing = false;

static void tap(const int16_t *data, size_t frames) {
    if (!__atomic_load_n(&capturing, __ATOMIC_ACQUIRE) || !data) return;
    size_t write = __atomic_load_n(&ring_write, __ATOMIC_RELAXED);
    size_t read = __atomic_load_n(&ring_read, __ATOMIC_ACQUIRE);
    size_t count = frames * 2;
    if (count > RING_SAMPLES - (write - read)) count = (RING_SAMPLES - (write - read)) & ~(size_t)1;
    for (size_t i = 0; i < count; i++) ring[(write + i) & (RING_SAMPLES - 1)] = data[i];
    __atomic_store_n(&ring_write, write + count, __ATOMIC_RELEASE);
}

#define JNI_FN(ret, name) EXPORT JNIEXPORT ret JNICALL Java_com_romcloud_app_stream_StreamTap_##name

/** Le cœur passe-t-il par l'adaptateur (son copiable) ? */
JNI_FN(jboolean, nativeActive)(JNIEnv *, jobject) { return core != nullptr; }

/** Copie du son (file vidée au démarrage) ; [mute] : silence sur le téléphone. */
JNI_FN(void, nativeSetCapture)(JNIEnv *, jobject, jboolean enabled, jboolean mute) {
    if (enabled) __atomic_store_n(&ring_read, __atomic_load_n(&ring_write, __ATOMIC_ACQUIRE), __ATOMIC_RELEASE);
    __atomic_store_n(&capturing, (bool)enabled, __ATOMIC_RELEASE);
    __atomic_store_n(&mute_local, (bool)(enabled && mute), __ATOMIC_RELAXED);
}

/** Échantillons disponibles copiés dans [buffer] (nombre pair : gauche, droite) ; renvoie leur nombre. */
JNI_FN(jint, nativeRead)(JNIEnv *env, jobject, jshortArray buffer) {
    size_t read = __atomic_load_n(&ring_read, __ATOMIC_RELAXED);
    size_t write = __atomic_load_n(&ring_write, __ATOMIC_ACQUIRE);
    size_t count = write - read;
    size_t capacity = (size_t)env->GetArrayLength(buffer) & ~(size_t)1;
    if (count > capacity) count = capacity;
    if (!count) return 0;
    jshort *out = env->GetShortArrayElements(buffer, nullptr);
    for (size_t i = 0; i < count; i++) out[i] = ring[(read + i) & (RING_SAMPLES - 1)];
    env->ReleaseShortArrayElements(buffer, out, 0);
    __atomic_store_n(&ring_read, read + count, __ATOMIC_RELEASE);
    return (jint)count;
}

JNI_FN(jdouble, nativeSampleRate)(JNIEnv *, jobject) { return sample_rate; }

/** Proportions de l'image du jeu (largeur / hauteur), 0 si inconnues. */
JNI_FN(jfloat, nativeAspectRatio)(JNIEnv *, jobject) { return aspect_ratio; }
