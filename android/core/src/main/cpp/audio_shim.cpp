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
//   LibretroDroid, même cadence d'émulation) ; proportions de l'image et fréquence du son exposées ;
//   image du cœur copiée à sa taille d'origine (lue par StreamTap, bien moins coûteux qu'une copie
//   de l'écran), relue dans son framebuffer pour les cœurs à rendu OpenGL ;
// - format d'image choisi par l'utilisateur (CoreShim, JNI) : proportions annoncées à LibretroDroid
//   remplacées (géométrie du cœur), y compris en cours de partie ;
// - jeu à plusieurs en réseau local (Netplay, JNI) : touches échangées image par image avec l'autre
//   appareil, qui émule le même jeu (section « Jeu à plusieurs » en fin de fichier) ; ou liaison entre
//   consoles (câble, adaptateur sans fil) : paquets du cœur échangés (interface netpacket de libretro).
// Pas de bibliothèque C++ (ni STL, ni variable statique locale) : rien d'autre à embarquer.

#include <GLES3/gl3.h>
#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
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
// Format d'image choisi (section « Image ») : appliqué avant chaque image.
static void apply_aspect();
// Diffusion : copie de l'image du cœur (section « Diffusion »).
static void tap_frame(const void *data, unsigned width, unsigned height, size_t pitch);
// Jeu à plusieurs (section « Jeu à plusieurs ») : avant et après chaque image ; touches des joueurs.
static void np_before_run();
static void np_after_run();
static bool np_input(unsigned port, unsigned device, unsigned index, unsigned id, int16_t *value);
// Liaison entre consoles : interface netpacket déclarée par le cœur (gpSP), gérée ici.
static bool np_on_netpacket(const void *data);
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
    apply_aspect();
    np_before_run();
    real_retro_run()();
    np_after_run();
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
const unsigned SET_ROTATION = 1, SET_PIXEL_FORMAT = 10, SET_HW_RENDER = 14, SET_SYSTEM_AV_INFO = 32, SET_GEOMETRY = 37;
// Format des pixels (RETRO_PIXEL_FORMAT_*) et rotation (quarts de tour antihoraires) demandés par le cœur.
const int PIXEL_0RGB1555 = 0, PIXEL_XRGB8888 = 1;
static int pixel_format = PIXEL_0RGB1555;
static unsigned rotation = 0;

static environment_t frontend_environment = nullptr;
static video_refresh_t frontend_video = nullptr;
static get_current_framebuffer_t frontend_framebuffer = nullptr;
static bool bottom_left_origin = true;
// Proportions annoncées par le cœur, et celles affichées (format choisi, sinon celles du cœur).
static float core_aspect_ratio = 0;
static float aspect_ratio = 0;
// Dernière géométrie du cœur (renvoyée à LibretroDroid quand le format change).
static retro_game_geometry last_geometry = {0, 0, 0, 0, 0};
static bool has_geometry = false;
// Format choisi (largeur / hauteur ; 0 : celui du cœur), écrit par l'application ; format appliqué.
static float wanted_aspect = 0;
static float applied_aspect = 0;

static float load_wanted_aspect() {
    float value;
    __atomic_load(&wanted_aspect, &value, __ATOMIC_RELAXED);
    return value;
}

static bool blit_enabled() {
    const char *value = getenv("ROMCLOUD_SHIM_BLIT");
    return value && value[0] == '1';
}

/**
 * Géométrie du cœur reçue : mémorisée, et proportions remplacées par le format choisi dans
 * [geometry] (copie envoyée à LibretroDroid).
 */
static void on_geometry(retro_game_geometry *geometry) {
    last_geometry = *geometry;
    has_geometry = true;
    core_aspect_ratio = geometry->aspect_ratio > 0 ? geometry->aspect_ratio
        : geometry->base_height ? (float)geometry->base_width / (float)geometry->base_height : 0;
    float wanted = load_wanted_aspect();
    applied_aspect = wanted;
    if (wanted > 0) geometry->aspect_ratio = wanted;
    aspect_ratio = wanted > 0 ? wanted : core_aspect_ratio;
}

/** Format changé depuis la dernière image : géométrie renvoyée à LibretroDroid (thread d'émulation). */
static void apply_aspect() {
    if (!has_geometry || !frontend_environment) return;
    if (load_wanted_aspect() == applied_aspect) return;
    retro_game_geometry geometry = last_geometry;
    on_geometry(&geometry);
    frontend_environment(SET_GEOMETRY, &geometry);
}

const unsigned SET_NETPACKET_INTERFACE = 78;

static bool on_environment(unsigned cmd, void *data) {
    // Inconnue de LibretroDroid : la liaison entre consoles passe par l'adaptateur.
    if (cmd == SET_NETPACKET_INTERFACE) return np_on_netpacket(data);
    if ((cmd == SET_SYSTEM_AV_INFO || cmd == SET_GEOMETRY) && data) {
        // Copie envoyée à LibretroDroid, proportions remplacées par le format choisi
        // (retro_system_av_info commence par sa géométrie).
        retro_av_info info;
        if (cmd == SET_SYSTEM_AV_INFO) info = *static_cast<const retro_av_info *>(data);
        else info.geometry = *static_cast<const retro_game_geometry *>(data);
        on_geometry(&info.geometry);
        if (cmd == SET_SYSTEM_AV_INFO) sample_rate = info.timing.sample_rate;
        return frontend_environment(cmd, &info);
    }
    bool handled = frontend_environment(cmd, data);
    if (!handled || !data) return handled;
    if (cmd == SET_HW_RENDER) {
        auto *hw = static_cast<retro_hw_render_callback *>(data);
        frontend_framebuffer = hw->get_current_framebuffer;
        bottom_left_origin = hw->bottom_left_origin;
    } else if (cmd == SET_PIXEL_FORMAT) {
        __atomic_store_n(&pixel_format, *static_cast<const int *>(data), __ATOMIC_RELAXED);
    } else if (cmd == SET_ROTATION) {
        __atomic_store_n(&rotation, *static_cast<const unsigned *>(data) & 3, __ATOMIC_RELAXED);
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
    tap_frame(data, width, height, pitch);
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
    on_geometry(reinterpret_cast<retro_game_geometry *>(info));
    sample_rate = reinterpret_cast<const retro_av_info *>(info)->timing.sample_rate;
}
FORWARD(void, retro_set_input_poll, (input_poll_t cb), (cb))
// --- Boutons lus d'un coup (RETRO_DEVICE_ID_JOYPAD_MASK) ---

const unsigned DEVICE_JOYPAD = 1, DEVICE_TYPE_MASK = 0xff, JOYPAD_MASK = 256, JOYPAD_BUTTONS = 16;

static input_state_t frontend_input = nullptr;

static int16_t on_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    int16_t networked;
    if (np_input(port, device, index, id, &networked)) return networked;
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

/** Proportions de l'image du jeu affichée (largeur / hauteur, rotation comprise), 0 si inconnues. */
JNI_FN(jfloat, nativeAspectRatio)(JNIEnv *, jobject) {
    float aspect = aspect_ratio;
    return aspect > 0 && (__atomic_load_n(&rotation, __ATOMIC_RELAXED) & 1) ? 1.0f / aspect : aspect;
}

// Image du cœur copiée pour la diffusion, pixels serrés, numéro d'image croissant :
// - rendu logiciel : copie de l'image reçue (0RGB1555 converti en RGB565, XRGB8888 tel quel) ;
// - rendu OpenGL : image relue dans le framebuffer du cœur (OpenGL ES 3 : deux tampons de pixels,
//   lecture asynchrone, rendue une image plus tard, sans attendre le processeur graphique), lignes
//   remises dans l'ordre, en RGBA. Contexte OpenGL ES 2 : rien (la diffusion copie l'écran).
static pthread_mutex_t frame_lock = PTHREAD_MUTEX_INITIALIZER;
static bool frame_capture = false;
static bool hardware_frames = false;  // aucune image copiable ici : la diffusion doit copier l'écran
static uint8_t *frame = nullptr;
static size_t frame_capacity = 0;
static unsigned frame_width = 0, frame_height = 0, frame_bpp = 0;
static bool frame_rgba = false;  // octets R, G, B, A (sinon B, G, R, X pour XRGB8888)
static int frame_serial = 0;

enum FrameSource { SOURCE_1555, SOURCE_RAW, SOURCE_RGBA_BOTTOM_UP, SOURCE_RGBA_TOP_DOWN };

/** Copie l'image ([pitch] octets par ligne de [data]) sous le verrou. */
static void store_frame(const void *data, unsigned width, unsigned height, size_t pitch, unsigned bpp, FrameSource source) {
    const size_t row = (size_t)width * bpp, size = row * height;
    pthread_mutex_lock(&frame_lock);
    if (size > frame_capacity) {
        auto grown = static_cast<uint8_t *>(realloc(frame, size));
        if (!grown) {
            pthread_mutex_unlock(&frame_lock);
            return;
        }
        frame = grown;
        frame_capacity = size;
    }
    const auto *src = static_cast<const uint8_t *>(data);
    for (unsigned y = 0; y < height; y++) {
        uint8_t *dst = frame + row * y;
        const uint8_t *line = src + pitch * (source == SOURCE_RGBA_BOTTOM_UP ? height - 1 - y : y);
        if (source == SOURCE_1555) {
            const auto *in = reinterpret_cast<const uint16_t *>(line);
            auto *out = reinterpret_cast<uint16_t *>(dst);
            for (unsigned x = 0; x < width; x++) {
                uint16_t p = in[x];
                uint16_t g = (p >> 5) & 0x1f;
                out[x] = (uint16_t)(((p & 0x7c00) << 1) | (g << 6) | ((g >> 4) << 5) | (p & 0x1f));
            }
        } else {
            memcpy(dst, line, row);
        }
    }
    frame_width = width;
    frame_height = height;
    frame_bpp = bpp;
    frame_rgba = source == SOURCE_RGBA_BOTTOM_UP || source == SOURCE_RGBA_TOP_DOWN;
    frame_serial = frame_serial == 0x7fffffff ? 1 : frame_serial + 1;
    pthread_mutex_unlock(&frame_lock);
}

// Relecture asynchrone du framebuffer du cœur (rendu OpenGL), sur le thread d'émulation.
static int gles_major = -1;  // version d'OpenGL ES du contexte (-1 : pas encore lue)
static GLuint pack_buffers[2] = {0, 0};
static size_t pack_sizes[2] = {0, 0};
static unsigned pack_width[2] = {0, 0}, pack_height[2] = {0, 0};
static bool pack_pending[2] = {false, false};
static int pack_next = 0;

static void read_hw_frame(unsigned width, unsigned height) {
    if (gles_major < 0) {
        const char *version = reinterpret_cast<const char *>(glGetString(GL_VERSION));
        const char *digits = version ? strstr(version, "OpenGL ES ") : nullptr;
        gles_major = digits ? atoi(digits + 10) : 2;
    }
    if (gles_major < 3 || !frontend_framebuffer) {
        __atomic_store_n(&hardware_frames, true, __ATOMIC_RELEASE);
        return;
    }
    GLint read_fb = 0, pack_buffer = 0, pack_alignment = 4;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read_fb);
    glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &pack_buffer);
    glGetIntegerv(GL_PACK_ALIGNMENT, &pack_alignment);
    if (!pack_buffers[0]) glGenBuffers(2, pack_buffers);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)frontend_framebuffer());
    glPixelStorei(GL_PACK_ALIGNMENT, 4);

    // Image de ce tour : lecture lancée dans un tampon (le processeur graphique la fait plus tard).
    const int current = pack_next;
    pack_next ^= 1;
    const size_t size = (size_t)width * height * 4;
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pack_buffers[current]);
    if (pack_sizes[current] < size) {
        glBufferData(GL_PIXEL_PACK_BUFFER, (GLsizeiptr)size, nullptr, GL_STREAM_READ);
        pack_sizes[current] = size;
    }
    glReadPixels(0, 0, (GLsizei)width, (GLsizei)height, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    pack_width[current] = width;
    pack_height[current] = height;
    pack_pending[current] = true;

    // Image du tour précédent : prête, copiée.
    const int previous = current ^ 1;
    if (pack_pending[previous]) {
        const unsigned w = pack_width[previous], h = pack_height[previous];
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pack_buffers[previous]);
        const void *pixels = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, (GLsizeiptr)w * h * 4, GL_MAP_READ_BIT);
        if (pixels) {
            store_frame(pixels, w, h, (size_t)w * 4, 4, bottom_left_origin ? SOURCE_RGBA_BOTTOM_UP : SOURCE_RGBA_TOP_DOWN);
            glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
        }
        pack_pending[previous] = false;
    }

    glBindBuffer(GL_PIXEL_PACK_BUFFER, (GLuint)pack_buffer);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)read_fb);
    glPixelStorei(GL_PACK_ALIGNMENT, pack_alignment);
}

static void tap_frame(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!__atomic_load_n(&frame_capture, __ATOMIC_ACQUIRE) || !data || !width || !height) return;
    if (data == reinterpret_cast<const void *>(-1)) {  // RETRO_HW_FRAME_BUFFER_VALID : rendu OpenGL
        read_hw_frame(width, height);
        return;
    }
    const int format = __atomic_load_n(&pixel_format, __ATOMIC_RELAXED);
    store_frame(data, width, height, pitch, format == PIXEL_XRGB8888 ? 4 : 2, format == PIXEL_0RGB1555 ? SOURCE_1555 : SOURCE_RAW);
}

/** Copie de l'image active ou non (diffusion en cours). */
JNI_FN(void, nativeSetFrameCapture)(JNIEnv *, jobject, jboolean enabled) {
    __atomic_store_n(&frame_capture, (bool)enabled, __ATOMIC_RELEASE);
}

/**
 * Dernière image copiée, si elle a changé depuis [last] : pixels dans [buffer] (direct) et [info] =
 * largeur, hauteur, octets par pixel (2 : RGB565, 4 : 32 bits), rotation, aucune image copiable
 * (1 : l'écran doit être copié), taille nécessaire, ordre des octets (1 : RGBA, 0 : BGRX). Renvoie
 * le numéro de l'image ([last] si rien de neuf, -1 si [buffer] est trop petit).
 */
JNI_FN(jint, nativeReadFrame)(JNIEnv *env, jobject, jobject buffer, jintArray info, jint last) {
    jint values[7] = {0, 0, 0, (jint)__atomic_load_n(&rotation, __ATOMIC_RELAXED),
                      __atomic_load_n(&hardware_frames, __ATOMIC_ACQUIRE) ? 1 : 0, 0, 0};
    jint serial = last;
    pthread_mutex_lock(&frame_lock);
    if (frame_serial != last && frame_serial != 0) {
        const size_t size = (size_t)frame_width * frame_height * frame_bpp;
        auto *out = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
        values[0] = (jint)frame_width;
        values[1] = (jint)frame_height;
        values[2] = (jint)frame_bpp;
        values[5] = (jint)size;
        values[6] = frame_rgba ? 1 : 0;
        if (!out || env->GetDirectBufferCapacity(buffer) < (jlong)size) {
            serial = -1;
        } else {
            memcpy(out, frame, size);
            serial = frame_serial;
        }
    }
    pthread_mutex_unlock(&frame_lock);
    env->SetIntArrayRegion(info, 0, 7, values);
    return serial;
}

// --- Format d'image (CoreShim) ---

/** Format choisi (largeur / hauteur ; 0 : celui du cœur), appliqué à la prochaine image. */
EXPORT JNIEXPORT void JNICALL Java_com_romcloud_app_libretro_CoreShim_nativeSetAspectRatio(JNIEnv *, jobject, jfloat aspect) {
    float value = aspect > 0 ? (float)aspect : 0.0f;
    __atomic_store(&wanted_aspect, &value, __ATOMIC_RELAXED);
}


// --- Jeu à plusieurs en réseau local (Netplay) ---
//
// Chaque appareil émule le même jeu ; seules les touches circulent (connexion TCP ouverte par
// l'application, sur le réseau local). Avant l'image f, l'appareil relève les touches de son joueur
// (port 0 de LibretroDroid), les envoie pour l'image f + délai, puis attend celles de l'autre joueur
// pour l'image f : les deux émulations reçoivent les mêmes touches à la même image. Les premières
// images (moins que le délai) se jouent sans touche des deux côtés.
// Départ : état du jeu de l'hôte copié chez l'invité entre deux images. Contrôle : empreinte de l'état
// comparée toutes les NP_CHECK images ; différence : l'invité demande l'état de l'hôte, chargé à
// l'image où l'hôte l'a pris (l'invité revient en arrière s'il l'a dépassée : touches gardées).
// Messages : type (1 octet), longueur du contenu (4 octets, gros-boutiste), contenu.
//
// Liaison entre consoles (mode « paquets ») : chaque appareil émule sa console, avec son jeu ; le cœur
// envoie et reçoit ses propres paquets (câble, adaptateur sans fil) par l'interface netpacket de
// libretro, transmis tels quels sur la connexion (message NP_MSG_PACKET). Hôte : client 0, invité : 1.

struct np_pad {
    uint16_t buttons;   // boutons RetroPad (bit = RETRO_DEVICE_ID_JOYPAD_*)
    int16_t analog[4];  // stick gauche x / y, stick droit x / y
};
enum { NP_MSG_INPUT = 1, NP_MSG_STATE = 2, NP_MSG_CHECK = 3, NP_MSG_RESYNC = 4, NP_MSG_BYE = 5, NP_MSG_PACKET = 6 };
enum { NP_MODE_INPUTS = 0, NP_MODE_PACKETS = 1 };
enum { NP_OFF = 0, NP_STARTING = 1, NP_RUNNING = 2, NP_ENDED = 3 };
const unsigned NP_RING = 256;      // images de touches gardées (retour en arrière compris)
const unsigned NP_CHECK = 120;     // contrôle de l'état toutes les 2 s à 60 images/s
const unsigned NP_CHECKS = 8;      // empreintes gardées
const long NP_TIMEOUT_MS = 120000; // autre joueur muet : partie arrêtée (menu ouvert compris)
const unsigned DEVICE_ANALOG = 5;

static int np_state = NP_OFF;      // lu par l'application (atomique)
static int np_fd = -1;
static bool np_host = false;
static unsigned np_local_port = 0, np_remote_port = 1, np_delay = 3;
static uint32_t np_frame = 0;      // image émulée ensuite
static uint32_t np_sent = 0;       // première image dont les touches locales restent à envoyer
static np_pad np_local[NP_RING];
static np_pad np_remote[NP_RING];
static uint32_t np_remote_frame[NP_RING];  // image des touches reçues + 1 (0 : aucune)
static np_pad np_current[2];
static bool np_in_frame = false;   // touches du réseau données au cœur (pendant retro_run)
static bool np_waiting = false;    // en attente de l'autre joueur (atomique, affiché)
static int np_desyncs = 0;         // différences d'état constatées (atomique)
static bool np_stop = false;       // arrêt demandé par l'application (atomique)
// Réception : octets en attente d'un message complet.
static uint8_t *np_buffer = nullptr;
static size_t np_buffer_length = 0, np_buffer_capacity = 0;
// Invité : état de l'hôte reçu, à charger à l'image np_pending_frame.
static uint8_t *np_pending = nullptr;
static size_t np_pending_size = 0;
static uint32_t np_pending_frame = 0;
static bool np_has_pending = false;
// Hôte : état demandé par l'invité.
static bool np_resync = false;
// Empreintes (image + 1, valeur) : les miennes et celles de l'hôte (invité).
static uint32_t np_mine_frame[NP_CHECKS], np_mine_hash[NP_CHECKS];
static uint32_t np_host_frame[NP_CHECKS], np_host_hash[NP_CHECKS];
static uint8_t *np_state_buffer = nullptr;
static size_t np_state_capacity = 0;

// Liaison entre consoles : interface du cœur (retro_netpacket_callback) et session commencée.
typedef void (*np_send_t)(int flags, const void *buf, size_t len, uint16_t client_id);
typedef void (*np_poll_receive_t)();
struct np_netpacket {
    void (*start)(uint16_t client_id, np_send_t send, np_poll_receive_t poll_receive);
    void (*receive)(const void *buf, size_t len, uint16_t client_id);
    void (*stop)();
    void (*poll)();
    bool (*connected)(uint16_t client_id);
    void (*disconnected)(uint16_t client_id);
    const char *protocol_version;
};
static int np_mode = NP_MODE_INPUTS;
static np_netpacket np_packets = {};
static bool np_has_packets = false;
static bool np_packets_active = false;

static bool np_on_netpacket(const void *data) {
    np_has_packets = data != nullptr;
    if (data) np_packets = *static_cast<const np_netpacket *>(data);
    return true;
}

static long np_now_ms() {
    timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000L + t.tv_nsec / 1000000L;
}

static void np_put32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)v;
}

static uint32_t np_get32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}

/** Fin de la partie à plusieurs : connexion fermée, chaque appareil continue seul. */
static void np_end() {
    if (np_fd >= 0) {
        uint8_t bye[5] = {NP_MSG_BYE, 0, 0, 0, 0};
        send(np_fd, bye, sizeof bye, MSG_NOSIGNAL | MSG_DONTWAIT);
        close(np_fd);
    }
    np_fd = -1;
    np_in_frame = false;
    np_has_pending = false;
    __atomic_store_n(&np_waiting, false, __ATOMIC_RELAXED);
    __atomic_store_n(&np_state, (int)NP_ENDED, __ATOMIC_RELEASE);
}

/** Envoie tout [data] (connexion bloquante) ; faux si la connexion est perdue. */
static bool np_write(const void *data, size_t size) {
    const auto *p = static_cast<const uint8_t *>(data);
    while (size) {
        ssize_t n = send(np_fd, p, size, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        size -= (size_t)n;
    }
    return true;
}

static bool np_send(uint8_t type, const void *payload, size_t size, const void *extra = nullptr, size_t extra_size = 0) {
    uint8_t header[5];
    header[0] = type;
    np_put32(header + 1, (uint32_t)(size + extra_size));
    return np_write(header, sizeof header) && (!size || np_write(payload, size)) && (!extra_size || np_write(extra, extra_size));
}

/** État du cœur sérialisé dans np_state_buffer ; renvoie sa taille (0 : impossible). */
static size_t np_serialize() {
    size_t size = real_retro_serialize_size()();
    if (!size) return 0;
    if (size > np_state_capacity) {
        auto grown = static_cast<uint8_t *>(realloc(np_state_buffer, size));
        if (!grown) return 0;
        np_state_buffer = grown;
        np_state_capacity = size;
    }
    return real_retro_serialize()(np_state_buffer, size) ? size : 0;
}

/** Hôte : état pris avant l'image [frame], envoyé à l'invité. */
static bool np_send_state(uint32_t frame) {
    size_t size = np_serialize();
    if (!size) return false;
    uint8_t head[4];
    np_put32(head, frame);
    return np_send(NP_MSG_STATE, head, sizeof head, np_state_buffer, size);
}

static uint32_t np_hash(const uint8_t *data, size_t size) {
    uint32_t h = 2166136261u;  // FNV-1a
    for (size_t i = 0; i < size; i++) h = (h ^ data[i]) * 16777619u;
    return h;
}

/** Invité : empreintes de l'image [frame] connues des deux côtés et différentes -> état de l'hôte demandé. */
static void np_compare(uint32_t frame) {
    unsigned slot = (frame / NP_CHECK) % NP_CHECKS;
    if (np_mine_frame[slot] != frame + 1 || np_host_frame[slot] != frame + 1) return;
    if (np_mine_hash[slot] == np_host_hash[slot] || np_has_pending) return;
    __atomic_add_fetch(&np_desyncs, 1, __ATOMIC_RELAXED);
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "netplay: state differs at frame %u, asking the host for its state", frame);
    if (!np_send(NP_MSG_RESYNC, nullptr, 0)) np_end();
}

static void np_message(uint8_t type, const uint8_t *p, uint32_t size) {
    if (type == NP_MSG_INPUT && size >= 14) {
        uint32_t frame = np_get32(p);
        np_pad &pad = np_remote[frame % NP_RING];
        pad.buttons = (uint16_t)((p[4] << 8) | p[5]);
        for (int i = 0; i < 4; i++) pad.analog[i] = (int16_t)((p[6 + 2 * i] << 8) | p[7 + 2 * i]);
        np_remote_frame[frame % NP_RING] = frame + 1;
    } else if (type == NP_MSG_STATE && size >= 4 && !np_host) {
        size_t length = size - 4;
        auto copy = static_cast<uint8_t *>(realloc(np_pending, length ? length : 1));
        if (!copy) return;
        memcpy(copy, p + 4, length);
        np_pending = copy;
        np_pending_size = length;
        np_pending_frame = np_get32(p);
        np_has_pending = true;
    } else if (type == NP_MSG_CHECK && size >= 8 && !np_host) {
        uint32_t frame = np_get32(p);
        unsigned slot = (frame / NP_CHECK) % NP_CHECKS;
        np_host_frame[slot] = frame + 1;
        np_host_hash[slot] = np_get32(p + 4);
        np_compare(frame);
    } else if (type == NP_MSG_RESYNC && np_host) {
        np_resync = true;
    } else if (type == NP_MSG_PACKET && np_packets_active && np_packets.receive) {
        np_packets.receive(p, size, np_host ? 1 : 0);
    } else if (type == NP_MSG_BYE) {
        np_end();
    }
}

/** Lit ce qui est arrivé (attente au plus [timeout] ms) et traite les messages complets ; faux si la partie est finie. */
static bool np_pump(int timeout) {
    pollfd pfd = {np_fd, POLLIN, 0};
    int ready = poll(&pfd, 1, timeout);
    if (ready < 0) return errno == EINTR;
    if (ready == 0) return true;
    for (;;) {
        if (np_buffer_capacity - np_buffer_length < 65536) {
            size_t capacity = np_buffer_capacity ? np_buffer_capacity * 2 : 262144;
            auto grown = static_cast<uint8_t *>(realloc(np_buffer, capacity));
            if (!grown) return false;
            np_buffer = grown;
            np_buffer_capacity = capacity;
        }
        ssize_t n = recv(np_fd, np_buffer + np_buffer_length, np_buffer_capacity - np_buffer_length, MSG_DONTWAIT);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
        if (n <= 0) return false;
        np_buffer_length += (size_t)n;
    }
    size_t used = 0;
    while (np_buffer_length - used >= 5) {
        uint32_t size = np_get32(np_buffer + used + 1);
        if (np_buffer_length - used - 5 < size) break;
        np_message(np_buffer[used], np_buffer + used + 5, size);
        if (__atomic_load_n(&np_state, __ATOMIC_ACQUIRE) == NP_ENDED) return false;
        used += 5 + size;
    }
    if (used) {
        memmove(np_buffer, np_buffer + used, np_buffer_length - used);
        np_buffer_length -= used;
    }
    return true;
}

/** Touches du joueur de cet appareil (port 0 de LibretroDroid). */
static np_pad np_capture() {
    np_pad pad = {0, {0, 0, 0, 0}};
    if (!frontend_input) return pad;
    for (unsigned button = 0; button < JOYPAD_BUTTONS; button++) {
        if (frontend_input(0, DEVICE_JOYPAD, 0, button)) pad.buttons |= (uint16_t)(1 << button);
    }
    for (unsigned i = 0; i < 4; i++) pad.analog[i] = frontend_input(0, DEVICE_ANALOG, i / 2, i % 2);
    return pad;
}

static void np_reset_rings() {
    memset(np_local, 0, sizeof np_local);
    memset(np_remote, 0, sizeof np_remote);
    memset(np_remote_frame, 0, sizeof np_remote_frame);
    memset(np_mine_frame, 0, sizeof np_mine_frame);
    memset(np_host_frame, 0, sizeof np_host_frame);
    // Premières images (moins que le délai) : sans touche des deux côtés.
    for (uint32_t f = 0; f < np_delay; f++) np_remote_frame[f] = f + 1;
    np_sent = np_delay;
    np_frame = 0;
}

/** Attend (au plus NP_TIMEOUT_MS) que [ready] soit vrai ; faux si la partie est finie entre-temps. */
template <typename Ready>
static bool np_wait(Ready ready) {
    if (ready()) return true;
    long start = np_now_ms();
    __atomic_store_n(&np_waiting, true, __ATOMIC_RELAXED);
    while (!ready()) {
        if (!np_pump(50) || np_now_ms() - start > NP_TIMEOUT_MS || __atomic_load_n(&np_stop, __ATOMIC_ACQUIRE)) {
            np_end();
            return false;
        }
    }
    __atomic_store_n(&np_waiting, false, __ATOMIC_RELAXED);
    return true;
}

// --- Liaison entre consoles (mode « paquets ») ---

/** Paquet du cœur pour l'autre console (une seule : destinataire ignoré). */
static void np_packet_send(int, const void *buf, size_t len, uint16_t) {
    if (np_fd < 0 || !buf || !len) return;
    if (!np_send(NP_MSG_PACKET, buf, len)) np_end();
}

/** Lecture demandée par le cœur entre deux images (paquets arrivés remis tout de suite). */
static void np_packet_poll_receive() {
    if (np_fd >= 0 && !np_pump(0)) np_end();
}

/** Session terminée (connexion perdue, liaison coupée) : signalée au cœur, hors de ses appels. */
static void np_packets_stopped() {
    if (!np_packets_active) return;
    np_packets_active = false;
    if (np_host && np_packets.disconnected) np_packets.disconnected(1);
    if (np_packets.stop) np_packets.stop();
}

static void np_packets_frame(int state) {
    if (state == NP_STARTING) {
        // Cœur sans interface netpacket : pas de liaison possible.
        if (!np_has_packets || !np_packets.start || !np_packets.receive) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "link: the core has no netpacket interface");
            np_end();
            return;
        }
        np_packets.start(np_host ? 0 : 1, np_packet_send, np_packet_poll_receive);
        np_packets_active = true;
        if (np_host && np_packets.connected && !np_packets.connected(1)) {
            np_end();
            np_packets_stopped();
            return;
        }
        __atomic_store_n(&np_state, (int)NP_RUNNING, __ATOMIC_RELEASE);
    }
    if (!np_pump(0)) np_end();
    if (__atomic_load_n(&np_state, __ATOMIC_ACQUIRE) != NP_RUNNING) {
        np_packets_stopped();
        return;
    }
    if (np_packets.poll) np_packets.poll();
}

static void np_before_run() {
    int state = __atomic_load_n(&np_state, __ATOMIC_ACQUIRE);
    if (np_packets_active && state != NP_RUNNING) np_packets_stopped();
    if (state == NP_OFF || state == NP_ENDED) return;
    if (__atomic_load_n(&np_stop, __ATOMIC_ACQUIRE)) {
        np_end();
        np_packets_stopped();
        return;
    }
    if (np_mode == NP_MODE_PACKETS) {
        np_packets_frame(state);
        return;
    }
    if (state == NP_STARTING) {
        // Départ : état de l'hôte copié chez l'invité, puis image 0 des deux côtés. Tampons remis à
        // zéro avant : les touches de l'hôte arrivent juste après son état, parfois dans la même lecture.
        np_reset_rings();
        if (np_host) {
            if (!np_send_state(0)) {
                np_end();
                return;
            }
        } else {
            if (!np_wait([] { return np_has_pending; })) return;
            real_retro_unserialize()(np_pending, np_pending_size);
            np_has_pending = false;
        }
        __atomic_store_n(&np_state, (int)NP_RUNNING, __ATOMIC_RELEASE);
    }
    if (!np_pump(0)) {
        np_end();
        return;
    }
    // Hôte : état demandé par l'invité, pris avant cette image.
    if (np_host && np_resync) {
        np_resync = false;
        if (!np_send_state(np_frame)) {
            np_end();
            return;
        }
    }
    // Invité : état de l'hôte chargé à son image (retour en arrière si elle est passée).
    if (!np_host && np_has_pending && np_pending_frame <= np_frame) {
        real_retro_unserialize()(np_pending, np_pending_size);
        np_frame = np_pending_frame;
        np_has_pending = false;
    }
    // Touches de ce joueur pour l'image frame + délai (une seule fois par image, même après un retour en arrière).
    const uint32_t target = np_frame + np_delay;
    if (target >= np_sent) {
        const np_pad pad = np_capture();
        for (uint32_t f = np_sent; f <= target; f++) {
            np_local[f % NP_RING] = pad;
            uint8_t message[14];
            np_put32(message, f);
            message[4] = (uint8_t)(pad.buttons >> 8);
            message[5] = (uint8_t)pad.buttons;
            for (int i = 0; i < 4; i++) {
                message[6 + 2 * i] = (uint8_t)((uint16_t)pad.analog[i] >> 8);
                message[7 + 2 * i] = (uint8_t)pad.analog[i];
            }
            if (!np_send(NP_MSG_INPUT, message, sizeof message)) {
                np_end();
                return;
            }
        }
        np_sent = target + 1;
    }
    // Touches de l'autre joueur pour cette image : attendues.
    const unsigned slot = np_frame % NP_RING;
    if (!np_wait([slot] { return np_remote_frame[slot] == np_frame + 1; })) return;
    np_current[np_local_port] = np_local[slot];
    np_current[np_remote_port] = np_remote[slot];
    np_in_frame = true;
}

static void np_after_run() {
    if (!np_in_frame) return;
    np_in_frame = false;
    np_frame++;
    if (np_frame % NP_CHECK) return;
    size_t size = np_serialize();
    if (!size) return;
    const uint32_t hash = np_hash(np_state_buffer, size);
    if (np_host) {
        uint8_t message[8];
        np_put32(message, np_frame);
        np_put32(message + 4, hash);
        if (!np_send(NP_MSG_CHECK, message, sizeof message)) np_end();
    } else {
        const unsigned slot = (np_frame / NP_CHECK) % NP_CHECKS;
        np_mine_frame[slot] = np_frame + 1;
        np_mine_hash[slot] = hash;
        np_compare(np_frame);
    }
}

/** Touches des joueurs pendant une image de la partie à plusieurs (sinon faux : touches de LibretroDroid). */
static bool np_input(unsigned port, unsigned device, unsigned index, unsigned id, int16_t *value) {
    if (!np_in_frame) return false;
    *value = 0;
    if (port > 1) return true;
    const np_pad &pad = np_current[port];
    const unsigned type = device & DEVICE_TYPE_MASK;
    if (type == DEVICE_JOYPAD) {
        if (id == JOYPAD_MASK) *value = (int16_t)pad.buttons;
        else if (id < JOYPAD_BUTTONS) *value = (int16_t)((pad.buttons >> id) & 1);
    } else if (type == DEVICE_ANALOG && index < 2 && id < 2) {
        *value = pad.analog[index * 2 + id];
    }
    // Autres périphériques (souris, pistolet…) : non partagés, au repos des deux côtés.
    return true;
}

#define NP_FN(ret, name) EXPORT JNIEXPORT ret JNICALL Java_com_romcloud_app_libretro_Netplay_##name

/**
 * Commence la partie à plusieurs sur la connexion [fd] (désormais gérée ici) : [host] (hôte : son état
 * est copié chez l'invité), [localPort] (port du joueur de cet appareil : 0 hôte, 1 invité), [delay]
 * (images entre l'appui et son effet) ; [packets] : liaison entre consoles (paquets du cœur échangés,
 * chacun son jeu). Pris en compte à la prochaine image.
 */
NP_FN(void, nativeStart)(JNIEnv *, jobject, jint fd, jboolean host, jint localPort, jint delay, jboolean packets) {
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    np_fd = fd;
    np_host = host;
    np_local_port = localPort == 1 ? 1 : 0;
    np_remote_port = 1 - np_local_port;
    np_delay = delay < 1 ? 1 : delay > 30 ? 30 : (unsigned)delay;
    np_mode = packets ? NP_MODE_PACKETS : NP_MODE_INPUTS;
    np_buffer_length = 0;
    np_has_pending = false;
    np_resync = false;
    __atomic_store_n(&np_desyncs, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&np_stop, false, __ATOMIC_RELAXED);
    __atomic_store_n(&np_state, (int)NP_STARTING, __ATOMIC_RELEASE);
}

/** Arrêt demandé (fin de partie, activité fermée) : attente interrompue, pris en compte à la prochaine image. */
NP_FN(void, nativeStop)(JNIEnv *, jobject) {
    __atomic_store_n(&np_stop, true, __ATOMIC_RELEASE);
    const int fd = np_fd;
    if (fd >= 0) shutdown(fd, SHUT_RDWR);
}

/** [état (0 aucune, 1 départ, 2 en cours, 3 terminée), attente de l'autre joueur (1), différences d'état, image]. */
NP_FN(void, nativeStatus)(JNIEnv *env, jobject, jintArray out) {
    jint values[4] = {
        __atomic_load_n(&np_state, __ATOMIC_ACQUIRE),
        __atomic_load_n(&np_waiting, __ATOMIC_RELAXED) ? 1 : 0,
        __atomic_load_n(&np_desyncs, __ATOMIC_RELAXED),
        (jint)np_frame,
    };
    env->SetIntArrayRegion(out, 0, 4, values);
}
