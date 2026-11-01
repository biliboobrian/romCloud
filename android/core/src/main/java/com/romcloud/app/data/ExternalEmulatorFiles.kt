package com.romcloud.app.data

/**
 * Fichiers que les émulateurs externes importent eux-mêmes, avec le sélecteur de fichiers d'Android :
 * clés, firmwares et données de la console (zone « system » du dossier RomCloud : system/switch/keys/
 * prod.keys…) et pilote graphique Turnip des puces Adreno (drivers/), pour les systèmes dont les
 * émulateurs en chargent un (Eden, Dolphin, Vita3K, aPS3e, Cemu). Le dossier RomCloud, dans le
 * stockage de l'appareil, est proposé par le sélecteur : rien à recopier. Pilote retiré si l'option
 * des pilotes est désactivée.
 */
object ExternalEmulatorFiles {

    /** Systèmes dont les émulateurs externes importent ces fichiers. */
    private val SYSTEMS = setOf("switch", "gc", "wii", "vita", "ps3", "wiiu")

    private fun applies(system: GameSystem) = system.id in SYSTEMS || system.shortname in SYSTEMS

    /** Chemin dans le dossier RomCloud d'un pilote graphique (indication propre aux pilotes). */
    fun isDriver(relativePath: String) = relativePath.startsWith("${Folders.DRIVERS}/")

    /**
     * Fichiers disponibles pour les émulateurs externes du système (chemins dans le dossier RomCloud,
     * « system/switch/keys/prod.keys », « drivers/turnip.zip ») ; pilotes compris si [drivers], sinon
     * supprimés.
     */
    fun available(folders: Folders, system: GameSystem, drivers: Boolean): List<String> {
        if (!applies(system)) return emptyList()
        if (!drivers) folders.drivers.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
        val sources = listOfNotNull(folders.systemFiles(system), folders.drivers.takeIf { drivers })
        return sources.flatMap { dir -> dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toList() }
            .map { it.relativeTo(folders.root).invariantSeparatorsPath }
            .sorted()
    }

    /** Dossier RomCloud tel que montré à l'utilisateur (« RomCloud », sans /storage/emulated/0/). */
    fun shownRoot(folders: Folders): String =
        folders.root.absolutePath.removePrefix(Folders.defaultRoot().substringBeforeLast('/') + "/")
}
