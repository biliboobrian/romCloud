package com.romcloud.app.launch

import com.romcloud.app.I18n
import com.romcloud.app.data.GameSystem
import com.romcloud.core.R
import java.io.File

/** Problème qui empêche de jouer avec l'émulateur externe choisi ([message] traduit). */
data class GameIssue(val kind: Kind, val message: String) {
    enum class Kind { EMULATOR_MISSING, VITA_TITLE_ID }
}

/**
 * Problèmes qui empêchent de jouer avec l'émulateur externe choisi pour ce fichier : la liste des
 * jeux propose alors « Configurer » (fiche du jeu) au lieu de « Jouer ». Vide pour l'émulateur
 * intégré, qui télécharge lui-même ce qui lui manque, et pour un jeu PC ([folder]) : lancé par
 * RomCloud PC (ou Winlator Cmod), proposé à l'installation au lancement s'il manque.
 */
object GameIssues {

    fun of(launcher: GameLauncher, system: GameSystem, fileName: String, file: File, downloaded: Boolean, folder: Boolean = false): List<GameIssue> {
        if (folder) return emptyList()
        val player = launcher.selectedPlayer(system, fileName) ?: return emptyList()
        if (player.libretroCore != null) return emptyList()
        return buildList {
            if (!launcher.isInstalled(player)) {
                add(GameIssue(GameIssue.Kind.EMULATOR_MISSING, I18n.get(R.string.issue_emulator_missing, player.name)))
            }
            // Vita3K démarre le jeu installé par son identifiant, lu dans le fichier une fois téléchargé.
            if (downloaded && "{tags.vita_game_id}" in player.amStartArguments && VitaGames.titleId(file) == null) {
                add(GameIssue(GameIssue.Kind.VITA_TITLE_ID, I18n.get(R.string.issue_vita_title_id)))
            }
        }
    }
}
