package com.romcloud.app.stream

import android.graphics.Bitmap
import android.graphics.Paint
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Diffuse le jeu affiché par [view] (vue OpenGL de l'émulateur intégré) vers la TV [target] (voir
 * [StreamProtocol]). L'image est copiée depuis la surface du jeu (PixelCopy) : la manette tactile et
 * les menus, superposés dans d'autres vues, ne sont pas diffusés. Seule la zone du jeu est copiée,
 * mise à la taille de l'écran de la TV et encodée en H.264 ; le son est lu dans l'adaptateur natif
 * ([StreamTap]) et coupé sur le téléphone. [onEvent] est appelé sur le thread principal.
 */
class StreamSender(
    private val view: SurfaceView,
    private val target: StreamReceiver,
    private val device: String,
    private val title: String,
    private val onEvent: (Event) -> Unit,
) {
    sealed interface Event {
        data object Connected : Event

        /** Diffusion terminée ; [error] : échec de la connexion à la TV (null : arrêt normal). */
        data class Stopped(val error: String?) : Event
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

    fun start() {
        running = true
        thread(name = "stream-connect") {
            try {
                connectAndRun()
            } catch (e: Exception) {
                finish(if (connected) null else e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Arrête la diffusion (le jeu continue sur le téléphone, avec son). */
    fun stop() = finish(null)

    private fun connect(): Socket {
        for (address in target.addresses) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(address, target.port), CONNECT_TIMEOUT_MS)
                return s
            } catch (_: IOException) {
                runCatching { s.close() }
            }
        }
        throw IOException(I18n.get(R.string.stream_unreachable, target.name))
    }

    private fun connectAndRun() {
        val s = connect()
        socket = s
        if (!running) throw IOException("stopped")
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
        val json = StreamProtocol.json
        out.writeUTF(json.encodeToString(StreamProtocol.Hello.serializer(), StreamProtocol.Hello(key = target.key, device = device, title = title)))
        out.flush()
        s.soTimeout = HANDSHAKE_TIMEOUT_MS
        val welcome = json.decodeFromString(StreamProtocol.Welcome.serializer(), input.readUTF())
        s.soTimeout = 0
        if (!welcome.ok) throw IOException(welcome.error ?: I18n.get(R.string.stream_refused, target.name))

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

    private fun startVideo(width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * FPS / 8).toInt().coerceIn(MIN_BITRATE, MAX_BITRATE))
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // temps réel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        thread(name = "stream-video") { sendVideo(codec) }
        startCapture(width, height)
    }

    /** Copie l'image du jeu (au plus [FPS] fois par seconde) sur la surface de l'encodeur. */
    private fun startCapture(width: Int, height: Int) {
        val handlerThread = HandlerThread("stream-capture").also { it.start() }
        captureThread = handlerThread
        val handler = Handler(handlerThread.looper)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val destination = Rect(0, 0, width, height)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        fun draw() = synchronized(surfaceLock) {
            val surface = inputSurface ?: return@synchronized
            val canvas = runCatching { surface.lockHardwareCanvas() }.getOrNull() ?: return@synchronized
            canvas.drawBitmap(bitmap, null, destination, paint)
            surface.unlockCanvasAndPost(canvas)
        }

        lateinit var capture: () -> Unit
        capture = capture@{
            if (!running) return@capture
            val began = SystemClock.uptimeMillis()
            if (view.width <= 0 || !view.holder.surface.isValid) {
                // Jeu en arrière-plan : surface détruite, reprise à son retour.
                handler.postDelayed(capture, 200)
                return@capture
            }
            val area = StreamProtocol.gameArea(view.width, view.height, aspect())
            runCatching {
                PixelCopy.request(view, Rect(area.left, area.top, area.right, area.bottom), bitmap, { result ->
                    if (result == PixelCopy.SUCCESS && running) draw()
                    handler.postAtTime(capture, began + FRAME_MS)
                }, handler)
            }.onFailure { handler.postDelayed(capture, 200) }
        }
        handler.post(capture)
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
        } catch (_: Exception) {
            finish(null)
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
        } catch (_: Exception) {
            finish(null)
        }
    }

    private fun send(type: Int, ptsUs: Long, data: ByteArray, length: Int) {
        val out = output ?: return
        synchronized(writeLock) { StreamProtocol.writePacket(out, type, ptsUs, data, length) }
    }

    private fun finish(error: String?) {
        if (!finished.compareAndSet(false, true)) return
        running = false
        StreamTap.setCapture(enabled = false, muteLocal = false)
        runCatching { socket?.close() }
        captureThread?.quitSafely()
        main.post { onEvent(Event.Stopped(error)) }
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val FPS = 60
        const val FRAME_MS = 1000L / FPS
        const val MIN_BITRATE = 4_000_000
        const val MAX_BITRATE = 16_000_000
        const val CONNECT_TIMEOUT_MS = 3000
        const val HANDSHAKE_TIMEOUT_MS = 8000
    }
}
