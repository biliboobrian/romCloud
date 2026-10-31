package com.romcloud.app.launch

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.romcloud.app.data.ApiClient
import com.romcloud.app.data.Game
import com.romcloud.app.data.MediaSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Raccourci d'un jeu sur l'écran d'accueil (fenêtre de téléchargement) : il ouvre RomCloud et lance
 * le jeu (reprise de la partie s'il y en a une), ou ouvre sa fiche s'il n'est plus sur l'appareil.
 * Icône : la jaquette du jeu, sinon celle de l'application.
 */
object GameShortcuts {

    /** Action de l'intention du raccourci, lue par l'écran principal ([gameOf]). */
    const val ACTION_PLAY = "com.romcloud.app.action.PLAY_GAME"
    private const val EXTRA_SYSTEM = "systemId"
    private const val EXTRA_GAME = "gameId"
    private const val ICON_SIZE = 192

    /** Le lanceur accepte-t-il les raccourcis épinglés ? */
    fun supported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Jeu à lancer (système, identifiant) si [intent] vient d'un raccourci, sinon null. */
    fun gameOf(intent: Intent?): Pair<String, Long>? {
        if (intent?.action != ACTION_PLAY) return null
        val system = intent.getStringExtra(EXTRA_SYSTEM) ?: return null
        val game = intent.getLongExtra(EXTRA_GAME, 0L).takeIf { it > 0 } ?: return null
        return system to game
    }

    /** Propose au lanceur d'ajouter le raccourci du jeu (le lanceur demande confirmation). */
    suspend fun request(context: Context, api: ApiClient, game: Game): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
        val intent = Intent(launch).apply {
            action = ACTION_PLAY
            putExtra(EXTRA_SYSTEM, game.systemId)
            putExtra(EXTRA_GAME, game.id)
            // Écran principal recréé : le jeu s'ouvre par-dessus l'écran des systèmes.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val icon = withContext(Dispatchers.IO) { cover(api, game) }
            ?.let(IconCompat::createWithBitmap)
            ?: IconCompat.createWithResource(context, context.applicationInfo.icon)
        val shortcut = ShortcutInfoCompat.Builder(context, "game-${game.id}")
            .setShortLabel(game.title.ifBlank { game.fileName }.take(25))
            .setLongLabel(game.title.ifBlank { game.fileName })
            .setIcon(icon)
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    /** Jaquette du jeu dans un carré transparent (icône sans rognage), ou null. */
    private fun cover(api: ApiClient, game: Game): Bitmap? = runCatching {
        val url = api.mediaUrl(game, "boxart", MediaSize.THUMB) ?: return null
        val bytes = api.http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null } ?: return null
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = ICON_SIZE.toFloat() / maxOf(image.width, image.height)
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888).also { square ->
            val left = (ICON_SIZE - w) / 2
            val top = (ICON_SIZE - h) / 2
            Canvas(square).drawBitmap(image, null, Rect(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }.getOrNull()
}
