package com.romcloud.app.stream

/** Image diffusée sur la TV, choisie avant de lancer la diffusion. */
enum class StreamMode {
    /** Image du cœur à sa taille d'origine, lue dans l'adaptateur natif : quasi instantanée, pixels nets. */
    NATIVE,

    /** Copie de l'écran du jeu sur le téléphone (filtre d'image et format compris) : un peu plus de retard. */
    VIDEO,
}
