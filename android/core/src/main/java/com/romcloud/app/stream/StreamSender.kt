package com.romcloud.app.stream

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import com.romcloud.app.I18n
import com.romcloud.app.data.StreamReceiver
import com.romcloud.core.R
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Diffuse le jeu affiché par [view] (vue OpenGL de l'émulateur intégré) vers la TV [target] (voir
 * [StreamProtocol]). Image : celle du cœur à sa taille d'origine, lue dans l'adaptateur natif
 * ([StreamTap], rendu logiciel), sinon copiée depuis la surface du jeu (PixelCopy, cœurs à rendu
 * OpenGL) ; la manette tactile et les menus, superposés dans d'autres vues, ne sont pas diffusés.
 * L'image est mise à la taille de l'écran de la TV et encodée en H.264 ; le son est lu dans
 * l'adaptateur et coupé sur le téléphone. [onEvent] est appelé sur le thread principal.
 */
class StreamSender(
    private val view: SurfaceView,
    private var target: StreamReceiver,
    private val device: String,
    private val title: String,
    /** Informations de la TV relues sur le serveur (port ou clé changés depuis le lancement) ; appelé hors du thread principal. */
    private val refreshTarget: () -> StreamReceiver?,
    private val onEvent: (Event) -> Unit,
) {
    sealed interface Event {
        data object Connected : Event

        /**
         * Diffusion terminée ; [error] : échec de la connexion à la TV (null : arrêt normal),
         * [details] : cause complète (journal des erreurs de l'administration).
         */
        data class Stopped(val error: String?, val details: String? = null) : Event
    }

    private val main = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private val writeLock = Any()
    private val surfaceLock = Any()
    private var inputSurface: Surface? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var renderer: EncoderRenderer? = null

    fun start() {
        running = true
        thread(name = "stream-connect") {
            try {
                connectAndRun()
            } catch (e: Throwable) {
                Log.w(TAG, "stream failed", e)
                if (connected) finish(null) else finish(e.message ?: e.javaClass.simpleName, describe(e))
            }
        }
    }

    /** Arrête la diffusion (le jeu continue sur le téléphone, avec son). */
    fun stop() = finish(null)

    /** Échec de la connexion ou refus de la TV : un nouvel essai est possible avec des informations relues. */
    private class HandshakeException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Cause complète d'un échec, pour le journal des erreurs. */
    private fun describe(e: Throwable): String =
        "TV ${target.name} ${target.addresses.joinToString()} port ${target.port} (${target.width}x${target.height})\n" +
            e.stackTraceToString().take(6000)

    private fun connect(): Socket {
        val failures = mutableListOf<String>()
        for (address in target.addresses) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                // File d'envoi courte : Wi-Fi ralenti, l'encodeur attend au lieu d'accumuler du retard.
                s.sendBufferSize = SEND_BUFFER
                s.connect(InetSocketAddress(address, target.port), CONNECT_TIMEOUT_MS)
                return s
            } catch (e: IOException) {
                runCatching { s.close() }
                failures += "$address:${target.port} (${e.message ?: e.javaClass.simpleName})"
            }
        }
        throw HandshakeException(I18n.get(R.string.stream_unreachable, target.name) + "\n" + failures.joinToString("\n"))
    }

    /** Connexion et présentation de la clé ; renvoie la connexion acceptée et la réponse de la TV. */
    private fun handshake(): Triple<Socket, DataInputStream, StreamProtocol.Welcome> {
        val s = connect()
        try {
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val out = DataOutputStream(s.getOutputStream())
            val json = StreamProtocol.json
            out.writeUTF(json.encodeToString(StreamProtocol.Hello.serializer(), StreamProtocol.Hello(key = target.key, device = device, title = title)))
            out.flush()
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
            val welcome = json.decodeFromString(StreamProtocol.Welcome.serializer(), input.readUTF())
            s.soTimeout = 0
            if (!welcome.ok) throw HandshakeException(welcome.error ?: I18n.get(R.string.stream_refused, target.name))
            return Triple(s, input, welcome)
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e as? HandshakeException
                ?: HandshakeException(I18n.get(R.string.stream_refused, target.name) + " (${e.message ?: e.javaClass.simpleName})", e)
        }
    }

    private fun connectAndRun() {
        val (s, input, welcome) = try {
            handshake()
        } catch (e: HandshakeException) {
            // TV revenue au premier plan, redémarrée… : port ou clé relus sur le serveur, un nouvel essai.
            val fresh = refreshTarget()?.takeIf { it.port != target.port || it.key != target.key || it.addresses != target.addresses }
                ?: throw e
            target = fresh
            handshake()
        }
        socket = s
        if (!running) throw IOException("stopped")
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
        val json = StreamProtocol.json

        val tvWidth = welcome.width.takeIf { it > 0 } ?: target.width ?: 0
        val tvHeight = welcome.height.takeIf { it > 0 } ?: target.height ?: 0
        val (width, height) = supportedSize(aspect(), tvWidth, tvHeight)
        val sampleRate = StreamTap.sampleRate.takeIf { StreamTap.active && it in 8000..192000 } ?: 0
        out.writeUTF(json.encodeToString(StreamProtocol.Format.serializer(), StreamProtocol.Format(width, height, sampleRate)))
        out.flush()
        output = out

        startVideo(width, height)
        if (sampleRate > 0) {
            StreamTap.setCapture(enabled = true, muteLocal = true)
            thread(name = "stream-audio") { sendAudio() }
        }
        connected = true
        main.post { if (running) onEvent(Event.Connected) }

        // La TV ferme la connexion pour arrêter (Retour sur la télécommande).
        try {
            while (running && input.read() >= 0) Unit
        } catch (_: IOException) {
        }
        finish(null)
    }

    /** Proportions de l'image du jeu (adaptateur natif), sinon celles de la vue. */
    private fun aspect(): Float =
        StreamTap.aspectRatio.takeIf { it > 0 } ?: (view.width.toFloat() / view.height.coerceAtLeast(1))

    /** Taille d'encodage acceptée par l'encodeur H.264 de l'appareil (réduite si besoin). */
    private fun supportedSize(aspect: Float, tvWidth: Int, tvHeight: Int): Pair<Int, Int> {
        val caps = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, true) } }
                ?.getCapabilitiesForType(MIME)?.videoCapabilities
        }.getOrNull()
        var maxHeight = StreamProtocol.MAX_HEIGHT
        var size = StreamProtocol.encodeSize(aspect, tvWidth, tvHeight, maxHeight)
        while (caps != null && !caps.isSizeSupported(size.first, size.second) && maxHeight > 240) {
            maxHeight = maxHeight * 3 / 4
            size = StreamProtocol.encodeSize(aspect, tvWidth, tvHeight, maxHeight)
        }
        return size
    }

    // ---- Image ----

    /**
     * Format de l'encodeur. Faible latence : temps réel, sans images B, profil H.264 « Baseline »
     * (les décodeurs des TV affichent alors chaque image sans en garder d'avance) et débit constant
     * (pas de grosse image qui retarde les suivantes sur le Wi-Fi), si l'encodeur les connaît.
     */
    private fun videoFormat(width: Int, height: Int, lowLatency: Boolean, caps: MediaCodecInfo.CodecCapabilities?) =
        MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * FPS / 12).toInt().coerceIn(MIN_BITRATE, MAX_BITRATE))
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (lowLatency) {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // temps réel
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
                val levels = caps?.profileLevels.orEmpty()
                val baseline = levels.filter { it.profile == AVC_CONSTRAINED_BASELINE }.ifEmpty {
                    levels.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }
                }.maxByOrNull { it.level }
                if (baseline != null) {
                    setInteger(MediaFormat.KEY_PROFILE, baseline.profile)
                    setInteger(MediaFormat.KEY_LEVEL, baseline.level)
                }
                if (caps?.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
            }
        }

    private fun startVideo(width: Int, height: Int) {
        var codec = MediaCodec.createEncoderByType(MIME)
        val caps = runCatching { codec.codecInfo.getCapabilitiesForType(MIME) }.getOrNull()
        try {
            codec.configure(videoFormat(width, height, lowLatency = true, caps), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Réglages de faible latence refusés par cet encodeur : format de base.
            Log.w(TAG, "low-latency encoder format refused", e)
            codec.release()
            codec = MediaCodec.createEncoderByType(MIME)
            codec.configure(videoFormat(width, height, lowLatency = false, caps), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = codec.createInputSurface()
        codec.start()
        thread(name = "stream-video") { sendVideo(codec) }
        startCapture(width, height)
    }

    /**
     * Envoie chaque image du jeu à la surface de l'encodeur (OpenGL ES, [EncoderRenderer]), sur le
     * thread « stream-capture » : image du cœur lue dans l'adaptateur dès qu'elle change (rendu
     * logiciel : petite copie, sans attendre l'écran), sinon copie de l'écran au plus [FPS] fois par
     * seconde (rendu OpenGL, ou adaptateur absent).
     */
    private fun startCapture(width: Int, height: Int) {
        val handlerThread = HandlerThread("stream-capture").also { it.start() }
        captureThread = handlerThread
        val handler = Handler(handlerThread.looper)
        captureHandler = handler
        val bitmap by lazy { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }

        fun draw() = synchronized(surfaceLock) {
            if (inputSurface == null) return@synchronized
            renderer?.draw(bitmap, System.nanoTime())
        }

        lateinit var capture: () -> Unit

        // Image du cœur : interrogée toutes les [POLL_MS] ms, envoyée dès qu'elle change.
        val info = IntArray(6)
        var frame = ByteBuffer.allocateDirect(512 * 512 * 4).order(ByteOrder.nativeOrder())
        var serial = 0
        lateinit var copyFrame: () -> Unit
        copyFrame = copyFrame@{
            if (!running) return@copyFrame
            try {
                val next = StreamTap.readFrame(frame, info, serial)
                when {
                    // Rendu OpenGL : rien à lire ici, l'écran est copié.
                    info[4] == 1 -> {
                        capture()
                        return@copyFrame
                    }
                    next == -1 -> {
                        frame = ByteBuffer.allocateDirect(info[5]).order(ByteOrder.nativeOrder())
                        handler.post(copyFrame)
                        return@copyFrame
                    }
                    next != serial -> {
                        serial = next
                        synchronized(surfaceLock) {
                            if (inputSurface != null) renderer?.drawFrame(frame, info[0], info[1], info[2], info[3], System.nanoTime())
                        }
                    }
                }
                handler.postDelayed(copyFrame, POLL_MS)
            } catch (e: Throwable) {
                fail(e)
            }
        }

        capture = capture@{
            if (!running) return@capture
            val began = SystemClock.uptimeMillis()
            if (view.width <= 0 || !view.holder.surface.isValid) {
                // Jeu en arrière-plan : surface détruite, reprise à son retour.
                handler.postDelayed(capture, 200)
                return@capture
            }
            val area = StreamProtocol.gameArea(view.width, view.height, aspect())
            try {
                PixelCopy.request(view, Rect(area.left, area.top, area.right, area.bottom), bitmap, { result ->
                    try {
                        if (result == PixelCopy.SUCCESS && running) draw()
                        handler.postAtTime(capture, began + FRAME_MS)
                    } catch (e: Throwable) {
                        fail(e)
                    }
                }, handler)
            } catch (e: IllegalArgumentException) {
                // Surface du jeu indisponible un instant (rotation, retour au premier plan).
                handler.postDelayed(capture, 200)
            }
        }
        handler.post {
            try {
                synchronized(surfaceLock) {
                    val surface = inputSurface ?: return@post
                    renderer = EncoderRenderer(surface, width, height)
                }
                if (StreamTap.active) {
                    StreamTap.setFrameCapture(true)
                    copyFrame()
                } else {
                    capture()
                }
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    /** Erreur pendant la diffusion : arrêt de la diffusion (le jeu continue), cause signalée. */
    private fun fail(e: Throwable) {
        Log.w(TAG, "stream error", e)
        finish(I18n.get(R.string.stream_error, e.message ?: e.javaClass.simpleName), describe(e))
    }

    private fun sendVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var buffer = ByteArray(256 * 1024)
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index < 0) continue
                val data = codec.getOutputBuffer(index)
                if (data != null && info.size > 0) {
                    if (buffer.size < info.size) buffer = ByteArray(info.size)
                    data.position(info.offset)
                    data.get(buffer, 0, info.size)
                    val type = if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) StreamProtocol.VIDEO_CONFIG else StreamProtocol.VIDEO_FRAME
                    send(type, info.presentationTimeUs, buffer, info.size)
                }
                codec.releaseOutputBuffer(index, false)
            }
        } catch (e: IOException) {
            finish(null) // TV déconnectée
        } catch (e: Throwable) {
            if (running) fail(e)
        } finally {
            synchronized(surfaceLock) {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                inputSurface?.release()
                inputSurface = null
            }
        }
    }

    // ---- Son ----

    private fun sendAudio() {
        val samples = ShortArray(8192)
        val bytes = ByteArray(samples.size * 2)
        try {
            while (running) {
                val count = StreamTap.read(samples)
                if (count == 0) {
                    Thread.sleep(4)
                    continue
                }
                // PCM 16 bits petit-boutiste (ordre de AudioTrack).
                for (i in 0 until count) {
                    val v = samples[i].toInt()
                    bytes[2 * i] = v.toByte()
                    bytes[2 * i + 1] = (v shr 8).toByte()
                }
                send(StreamProtocol.AUDIO, System.nanoTime() / 1000, bytes, count * 2)
            }
        } catch (e: IOException) {
            finish(null) // TV déconnectée
        } catch (e: Throwable) {
            if (running) fail(e)
        }
    }

    private fun send(type: Int, ptsUs: Long, data: ByteArray, length: Int) {
        val out = output ?: return
        synchronized(writeLock) { StreamProtocol.writePacket(out, type, ptsUs, data, length) }
    }

    private fun finish(error: String?, details: String? = null) {
        if (!finished.compareAndSet(false, true)) return
        running = false
        StreamTap.setCapture(enabled = false, muteLocal = false)
        StreamTap.setFrameCapture(false)
        runCatching { socket?.close() }
        // Contexte OpenGL libéré sur son thread, avant l'arrêt de celui-ci.
        captureHandler?.post {
            synchronized(surfaceLock) {
                runCatching { renderer?.release() }
                renderer = null
            }
        }
        captureThread?.quitSafely()
        main.post { onEvent(Event.Stopped(error, details)) }
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val FPS = 60
        const val FRAME_MS = 1000L / FPS
        const val MIN_BITRATE = 3_000_000
        const val MAX_BITRATE = 12_000_000
        /** MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline (Android 8.1). */
        const val AVC_CONSTRAINED_BASELINE = 0x10000
        /** Intervalle de lecture de l'image du cœur (ms). */
        const val POLL_MS = 2L
        /** File d'envoi du système (octets) : environ 0,2 s d'image au débit maximal. */
        const val SEND_BUFFER = 256 * 1024
        const val TAG = "RomCloudStream"
        const val CONNECT_TIMEOUT_MS = 5000
        const val HANDSHAKE_TIMEOUT_MS = 8000
    }
}
