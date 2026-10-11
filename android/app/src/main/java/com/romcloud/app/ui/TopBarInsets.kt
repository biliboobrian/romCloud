package com.romcloud.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable

/**
 * Marge du haut des barres d'écran : sous la barre d'état quand elle est affichée ; en plein écran,
 * tout en haut (la place de l'heure est utilisée, encoche de la caméra comprise).
 */
val topBarInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.only(WindowInsetsSides.Top)
