package com.romcloud.app.stream

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dessine l'image du jeu sur la surface d'entrée de l'encodeur H.264, avec un contexte OpenGL ES
 * propre (surface « enregistrable », comme les enregistreurs d'écran d'Android) et l'horodatage de
 * chaque image : copie de l'écran ([draw]) ou image du cœur à sa taille d'origine, agrandie avec des
 * pixels nets ([drawFrame]). À utiliser depuis un seul thread (création, dessin, libération).
 */
internal class EncoderRenderer(surface: Surface, private val width: Int, private val height: Int) {

    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val eglSurface: EGLSurface
    private val program: Int
    private val texture: Int
    private var textureReady = false
    private val frameProgram: Int
    private val frameTexture: Int
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameBpp = 0

    // Rectangle plein écran : position (x, y) et coordonnées de texture (s, t), ligne 0 de l'image en haut.
    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
        position(0)
    }

    init {
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw IOException("eglInitialize")
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) throw IOException("eglChooseConfig")
        val config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw IOException("eglCreateContext 0x${Integer.toHexString(EGL14.eglGetError())}")
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw IOException("eglCreateWindowSurface 0x${Integer.toHexString(EGL14.eglGetError())}")
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) throw IOException("eglMakeCurrent")

        program = link(
            """
            attribute vec2 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() { vTex = aTex; gl_Position = vec4(aPos, 0.0, 1.0); }
            """,
            """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
            """,
        )
        // Image du cœur : rotation (quarts de tour antihoraires) appliquée au rectangle ; pixels nets
        // (lissés sur un pixel de l'image encodée, « sharp bilinear ») ; XRGB8888 lu en BGRA.
        frameProgram = link(
            """
            attribute vec2 aPos;
            attribute vec2 aTex;
            uniform vec2 uRot;
            varying vec2 vTex;
            void main() {
                vTex = aTex;
                gl_Position = vec4(aPos.x * uRot.x - aPos.y * uRot.y, aPos.x * uRot.y + aPos.y * uRot.x, 0.0, 1.0);
            }
            """,
            """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTex;
            uniform sampler2D uTex;
            uniform vec2 uSize;
            uniform vec2 uScale;
            uniform float uSwap;
            void main() {
                vec2 texel = vTex * uSize;
                vec2 f = fract(texel) - 0.5;
                vec2 region = max(0.5 - 0.5 / uScale, 0.0);
                vec2 uv = (floor(texel) + (f - clamp(f, -region, region)) * uScale + 0.5) / uSize;
                vec4 c = texture2D(uTex, uv);
                gl_FragColor = vec4(mix(c.rgb, c.bgr, uSwap), 1.0);
            }
            """,
        )
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        texture = textures[0]
        frameTexture = textures[1]
        for (t in textures) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
    }

    /** Envoie [bitmap] (taille de l'encodage) à l'encodeur, horodaté [timeNanos]. */
    fun draw(bitmap: Bitmap, timeNanos: Long) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (textureReady) {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        } else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            textureReady = true
        }
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val tex = GLES20.glGetAttribLocation(program, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(pos)
        quad.position(2)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, timeNanos)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    /**
     * Envoie l'image du cœur ([pixels] serrés, [frameW] x [frameH], [bytesPerPixel] : 2 = RGB565,
     * 4 = 32 bits, octets RGBA si [rgba], sinon BGRX), tournée de [rotation] quarts de tour
     * antihoraires, à l'encodeur.
     */
    fun drawFrame(pixels: ByteBuffer, frameW: Int, frameH: Int, bytesPerPixel: Int, rgba: Boolean, rotation: Int, timeNanos: Long) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(frameProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        val format = if (bytesPerPixel == 4) GLES20.GL_RGBA else GLES20.GL_RGB
        val type = if (bytesPerPixel == 4) GLES20.GL_UNSIGNED_BYTE else GLES20.GL_UNSIGNED_SHORT_5_6_5
        pixels.position(0)
        if (frameW != frameWidth || frameH != frameHeight || bytesPerPixel != frameBpp) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, frameW, frameH, 0, format, type, pixels)
            frameWidth = frameW
            frameHeight = frameH
            frameBpp = bytesPerPixel
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frameW, frameH, format, type, pixels)
        }
        val turns = rotation and 3
        // Pixels de l'image encodée par pixel du jeu, dans le sens de l'image (axes échangés par un quart de tour).
        val outW = if (turns % 2 == 1) height else width
        val outH = if (turns % 2 == 1) width else height
        val pos = GLES20.glGetAttribLocation(frameProgram, "aPos")
        val tex = GLES20.glGetAttribLocation(frameProgram, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(pos)
        quad.position(2)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(frameProgram, "uTex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(frameProgram, "uSize"), frameW.toFloat(), frameH.toFloat())
        GLES20.glUniform2f(GLES20.glGetUniformLocation(frameProgram, "uScale"), outW.toFloat() / frameW, outH.toFloat() / frameH)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(frameProgram, "uSwap"), if (bytesPerPixel == 4 && !rgba) 1f else 0f)
        val cos = floatArrayOf(1f, 0f, -1f, 0f)[turns]
        val sin = floatArrayOf(0f, 1f, 0f, -1f)[turns]
        GLES20.glUniform2f(GLES20.glGetUniformLocation(frameProgram, "uRot"), cos, sin)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, timeNanos)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IOException("shader: " + GLES20.glGetShaderInfoLog(shader))
            return shader
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IOException("program: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
