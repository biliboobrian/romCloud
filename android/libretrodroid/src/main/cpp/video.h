/*
 *     Copyright (C) 2019  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#ifndef LIBRETRODROID_VIDEO_H
#define LIBRETRODROID_VIDEO_H

#include <GLES3/gl3.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <optional>
#include <array>

#include "renderers/renderer.h"
#include "shadermanager.h"
#include "utils/rect.h"
#include "immersivemode.h"
#include "videolayout.h"

namespace libretrodroid {

class Video {
public:

    struct RenderingOptions {
        bool hardwareAccelerated = false;
        unsigned int width;
        unsigned int height;
        bool useDepth;
        bool useStencil;
        int openglESVersion;
        int pixelFormat;
    };

    struct ShaderChainEntry {
        GLint gProgram = 0;
        GLint gvPositionHandle = 0;
        GLint gvCoordinateHandle = 0;
        GLint gTextureHandle = 0;
        GLint gPreviousPassTextureHandle = 0;
        GLint gScreenDensityHandle = 0;
        GLint gTextureSizeHandle = 0;
    };

    Video(
        RenderingOptions renderingOptions,
        ShaderManager::Config shaderConfig,
        bool bottomLeftOrigin,
        float rotation,
        bool skipDuplicateFrames,
        bool immersiveMode,
        Rect viewportRect,
        ImmersiveMode::Config immersiveModeConfig,
        unsigned int viewportAlignment
    );

    VideoLayout& getLayout() { return videoLayout; }

    void updateAspectRatio(float aspectRatio);
    void updateScreenSize(unsigned screenWidth, unsigned screenHeight);
    void updateViewportSize(Rect viewportRect);
    void updateViewportAlignment(unsigned int viewportAlignment);
    void updateRendererSize(unsigned width, unsigned height);
    void updateRotation(float rotation);
    void updateShaderType(ShaderManager::Config shaderConfig);

    void renderFrame();
    /** RomCloud : image redessinée même sans nouvelle image du cœur (émulation figée). */
    void markDirty() { isDirty = true; }

    void onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch);

    uintptr_t getCurrentFramebuffer() {
        return renderer->getFramebuffer();
    };

    bool rendersInVideoCallback() {
        return renderer->rendersInVideoCallback();
    }

private:
    void updateProgram();

    float getScreenDensity();
    float getTextureWidth();
    float getTextureHeight();

    void initializeRenderer(RenderingOptions renderingOptions);

    // RomCloud : image des cœurs Vulkan partagée par deux AHardwareBuffer (EGLImage), sans recopie.
    bool showShared(unsigned width, unsigned height);
    void dropShared();

private:
    ShaderManager::Config requestedShaderConfig = ShaderManager::Config {
        ShaderManager::Type::SHADER_DEFAULT
    };
    std::optional<ShaderManager::Config> loadedShaderType = std::nullopt;

    bool isDirty = false;
    bool skipDuplicateFrames = false;

    std::vector<ShaderChainEntry> shadersChain;

    bool immersiveModeEnabled = false;
    ImmersiveMode immersiveMode;
    VideoLayout videoLayout;

    Renderer* renderer;

    // Image partagée : EGLImage de chaque tampon, barrière après l'affichage de chacun (Vulkan ne le
    // réécrit qu'une fois la lecture finie), tampon rempli ensuite, tampon affiché.
    bool shareTried = false;
    bool shareOk = false;
    EGLDisplay shareDisplay = EGL_NO_DISPLAY;
    EGLImageKHR shareImages[2] = {EGL_NO_IMAGE_KHR, EGL_NO_IMAGE_KHR};
    GLsync shareFences[2] = {nullptr, nullptr};
    unsigned shareGeneration = 0;
    int nextSlot = 0;
    int shownSlot = -1;

public:
    ~Video() { dropShared(); }
};

}

#endif //LIBRETRODROID_VIDEO_H
