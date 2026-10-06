package com.romcloud.tv

import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.romcloud.app.AppLanguage
import com.romcloud.app.stream.StreamProtocol
import com.romcloud.core.R
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Jeu diffusé depuis un téléphone du profil (voir StreamProtocol) : image H.264 décodée en plein
 * écran, aux proportions du jeu, et son PCM. Retour sur la télécommande : fin de la diffusion (le
 * jeu continue sur le téléphone).
 */
class TvStreamActivity : ComponentActivity(), SurfaceHolder.Callback {

    /** Connexion acceptée par TvStreamHost, après vérification de la clé. */
    class Session(val socket: Socket, val input: DataInputStream, val device: String, val title: String) {
        val createdAt: Long = SystemClock.elapsedRealtime()
    }

    private var session: Session? = null
    private lateinit var container: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private var player: Player? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taken = pending.getAndSet(null)
        if (taken == null) {
            finish()
            return
        }
        session = taken
        current.set(taken)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        container.addView(surfaceView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        setContentView(container)
        surfaceView.holder.addCallback(this)
        Toast.makeText(
            this,
            getString(R.string.stream_tv_from, taken.device) + "\n" + getString(R.string.stream_tv_back_hint),
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val s = session ?: return
        if (player != null) return
        player = Player(s, holder.surface, onFormat = { format -> runOnUiThread { fit(format.width, format.height) } }, onEnd = { runOnUiThread { finish() } })
            .also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // TV en veille ou autre application : la diffusion s'arrête.
        player?.stop()
        player = null
        finish()
    }

    /** Image du jeu aussi grande que possible à ses proportions, centrée (bandes noires sinon). */
    private fun fit(videoWidth: Int, videoHeight: Int) {
        val w = container.width
        val h = container.height
        if (w <= 0 || h <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val scale = min(w.toFloat() / videoWidth, h.toFloat() / videoHeight)
        surfaceView.layoutParams = FrameLayout.LayoutParams((videoWidth * scale).toInt(), (videoHeight * scale).toInt(), Gravity.CENTER)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.stop()
        player = null
        session?.let {
            runCatching { it.socket.close() }
            current.compareAndSet(it, null)
        }
    }

    /** Lecture de la connexion : image vers le décodeur (affichée sur [surface]), son vers AudioTrack. */
    private class Player(
        private val session: Session,
        private val surface: Surface,
        private val onFormat: (StreamProtocol.Format) -> Unit,
        private val onEnd: () -> Unit,
    ) {
        @Volatile private var running = true
        private val audio = LinkedBlockingQueue<ByteArray>()
        private val audioBytes = AtomicInteger(0)

        fun start() {
            thread(name = "stream-receive") { receive() }
        }

        fun stop() {
            running = false
            runCatching { session.socket.close() }
        }

        private fun receive() {
            var decoder: MediaCodec? = null
            try {
                val input = session.input
                val format = StreamProtocol.json.decodeFromString(StreamProtocol.Format.serializer(), input.readUTF())
                onFormat(format)
                val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                decoder = codec
                val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, format.width, format.height).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                codec.configure(videoFormat, surface, null, 0)
                codec.start()
                thread(name = "stream-render") { render(codec) }
                if (format.sampleRate > 0) thread(name = "stream-audio") { play(format.sampleRate) }

                while (running) {
                    val packet = StreamProtocol.readPacket(input)
                    when (packet.type) {
                        StreamProtocol.VIDEO_CONFIG -> decode(codec, packet, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, required = true)
                        StreamProtocol.VIDEO_FRAME -> decode(codec, packet, 0, required = false)
                        StreamProtocol.AUDIO -> {
                            audio.offer(packet.data)
                            audioBytes.addAndGet(packet.data.size)
                        }
                    }
                }
            } catch (_: Exception) {
                // Connexion fermée (téléphone ou Retour) ou flux invalide : fin de la diffusion.
            } finally {
                running = false
                runCatching { session.socket.close() }
                decoder?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
                onEnd()
            }
        }

        /** Image au décodeur ; sans tampon libre, une image ordinaire est sautée (pas la configuration). */
        private fun decode(codec: MediaCodec, packet: StreamProtocol.Packet, flags: Int, required: Boolean) {
            var index: Int
            do {
                index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            } while (index < 0 && required && running)
            if (index < 0) return
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(packet.data)
            codec.queueInputBuffer(index, 0, packet.data.size, packet.ptsUs, flags)
        }

        /** Images décodées affichées dès qu'elles sont prêtes (latence minimale). */
        private fun render(codec: MediaCodec) {
            val info = MediaCodec.BufferInfo()
            while (running) {
                val index = runCatching { codec.dequeueOutputBuffer(info, 20_000) }.getOrElse { return }
                if (index >= 0) runCatching { codec.releaseOutputBuffer(index, true) }
            }
        }

        /** Son : au-delà de [MAX_AUDIO_LATENCY_MS] en attente (horloges des deux appareils), le plus ancien est sauté. */
        private fun play(sampleRate: Int) {
            val bytesPerSecond = sampleRate * 4
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, bytesPerSecond * 80 / 1000))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            }.getOrNull() ?: return
            val maxQueued = bytesPerSecond * MAX_AUDIO_LATENCY_MS / 1000
            try {
                track.play()
                while (running) {
                    val chunk = audio.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    audioBytes.addAndGet(-chunk.size)
                    if (audioBytes.get() > maxQueued) continue
                    track.write(chunk, 0, chunk.size)
                }
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 30_000L
        private const val MAX_AUDIO_LATENCY_MS = 150
        private const val PENDING_TIMEOUT_MS = 10_000L

        /** Connexion en attente d'ouverture de l'écran, et diffusion affichée. */
        private val pending = AtomicReference<Session?>(null)
        private val current = AtomicReference<Session?>(null)

        /** Propose une connexion ; false si une diffusion est déjà en cours ou en attente. */
        fun offer(session: Session): Boolean {
            // Écran jamais ouvert pour la connexion précédente : abandonnée.
            pending.get()?.let { stale ->
                if (SystemClock.elapsedRealtime() - stale.createdAt > PENDING_TIMEOUT_MS && pending.compareAndSet(stale, null)) {
                    runCatching { stale.socket.close() }
                }
            }
            val shown = current.get()
            if (shown != null && !shown.socket.isClosed) return false
            return pending.compareAndSet(null, session)
        }
    }
}
