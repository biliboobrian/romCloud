package com.romcloud.app.launch

import com.romcloud.app.data.Player

/** Choix des modèles d'émulateur adaptés à un fichier (acceptedFilenameRegex des modèles Daijishou). */
object PlayerFilter {

    /**
     * Le modèle accepte-t-il ce fichier ? Comparaison insensible à la casse (« Jeu.DSK »
     * comme « jeu.dsk ») ; un modèle sans regex, ou avec une regex invalide, accepte tout.
     */
    fun accepts(player: Player, fileName: String): Boolean {
        val regex = player.acceptedFilenameRegex?.takeIf { it.isNotBlank() } ?: return true
        return runCatching { Regex(regex, RegexOption.IGNORE_CASE).matches(fileName) }.getOrDefault(true)
    }

    /**
     * Modèles compatibles avec le fichier. Si aucun ne l'est (extension absente des modèles),
     * tous les modèles du système sont proposés plutôt qu'aucun.
     */
    fun compatible(players: List<Player>, fileName: String): List<Player> =
        players.filter { accepts(it, fileName) }.ifEmpty { players }
}
