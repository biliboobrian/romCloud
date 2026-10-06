package com.romcloud.app.stream

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Diffusion d'un jeu de l'émulateur intégré du téléphone vers une TV du même profil, en direct sur
 * le réseau local (TCP, la TV écoute) :
 * 1. téléphone -> TV : [Hello] (clé de la TV, publiée par le serveur aux seuls appareils du profil) ;
 * 2. TV -> téléphone : [Welcome] (accepté ou non, définition de l'écran de la TV) ;
 * 3. téléphone -> TV : [Format] (taille de l'image encodée, fréquence du son) ;
 * 4. téléphone -> TV : paquets (type, horodatage en µs, taille, données) : configuration et images
 *    H.264, son PCM 16 bits stéréo. La TV ferme la connexion pour arrêter.
 * Les messages JSON sont envoyés avec writeUTF / readUTF.
 */
object StreamProtocol {
    const val VERSION = 1

    const val VIDEO_CONFIG = 1
    const val VIDEO_FRAME = 2
    const val AUDIO = 3

    /** Aucun paquet légitime n'est plus gros (image clé 4K très détaillée comprise). */
    private const val MAX_PACKET = 16 * 1024 * 1024

    /** Hauteur maximale de l'image encodée (au-delà, le téléphone ne suit plus). */
    const val MAX_HEIGHT = 1080

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Hello(val version: Int = VERSION, val key: String, val device: String, val title: String)

    @Serializable
    data class Welcome(val ok: Boolean, val error: String? = null, val width: Int = 0, val height: Int = 0)

    @Serializable
    data class Format(val width: Int, val height: Int, val sampleRate: Int, val channels: Int = 2)

    class Packet(val type: Int, val ptsUs: Long, val data: ByteArray)

    /** Zone rectangulaire en pixels (bords gauche et haut inclus, droit et bas exclus). */
    data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun writePacket(out: DataOutputStream, type: Int, ptsUs: Long, data: ByteArray, length: Int = data.size) {
        out.writeByte(type)
        out.writeLong(ptsUs)
        out.writeInt(length)
        out.write(data, 0, length)
        out.flush()
    }

    fun readPacket(input: DataInputStream): Packet {
        val type = input.readUnsignedByte()
        val pts = input.readLong()
        val length = input.readInt()
        if (length < 0 || length > MAX_PACKET) throw IOException("packet size $length")
        val data = ByteArray(length)
        input.readFully(data)
        return Packet(type, pts, data)
    }

    /**
     * Taille de l'image encodée : image du jeu ([aspect] = largeur / hauteur) aussi grande que
     * possible dans l'écran de la TV ([tvWidth] x [tvHeight], ramené à [maxHeight] de haut au plus),
     * multiples de 16 (exigés par certains encodeurs). TV inconnue : écran 1920 x 1080.
     */
    fun encodeSize(aspect: Float, tvWidth: Int, tvHeight: Int, maxHeight: Int = MAX_HEIGHT): Pair<Int, Int> {
        val screenW = if (tvWidth > 0 && tvHeight > 0) tvWidth else 1920
        val screenH = if (tvWidth > 0 && tvHeight > 0) tvHeight else 1080
        val scale = min(1f, maxHeight.toFloat() / screenH)
        val boxW = screenW * scale
        val boxH = screenH * scale
        val ratio = if (aspect > 0) aspect else boxW / boxH
        val width = min(boxW, boxH * ratio)
        val height = width / ratio
        fun align(v: Float) = ((v / 16).toInt() * 16).coerceAtLeast(16)
        return align(width) to align(height)
    }

    /**
     * Zone du jeu dans la vue de l'émulateur ([viewWidth] x [viewHeight]) : image centrée à ses
     * proportions (comme LibretroDroid l'affiche), sans les bandes noires. Proportions inconnues :
     * toute la vue.
     */
    fun gameArea(viewWidth: Int, viewHeight: Int, aspect: Float): Area {
        if (aspect <= 0 || viewWidth <= 0 || viewHeight <= 0) return Area(0, 0, viewWidth, viewHeight)
        var w = viewWidth.toFloat()
        var h = w / aspect
        if (h > viewHeight) {
            h = viewHeight.toFloat()
            w = h * aspect
        }
        val left = ((viewWidth - w) / 2).roundToInt()
        val top = ((viewHeight - h) / 2).roundToInt()
        return Area(left, top, left + w.roundToInt(), top + h.roundToInt())
    }
}
