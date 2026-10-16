/*
 *     Copyright (C) 2022  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.swordfish.libretrodroid

/** RomCloud : couleurs de l'image finale (1 = inchangée ; gamma : exposant inverse). */
data class ShaderColors(
    val brightness: Float = 1.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val gamma: Float = 1.0f,
)

sealed interface ShaderConfig {

    /** RomCloud : couleurs appliquées après le shader. */
    val colors: ShaderColors

    data class Default(override val colors: ShaderColors = ShaderColors()) : ShaderConfig

    /** RomCloud : force des lignes de balayage et éclat de leur centre. */
    data class CRT(
        val intensity: Float = 0.30f,
        val brightBoost: Float = 0.30f,
        override val colors: ShaderColors = ShaderColors(),
    ) : ShaderConfig

    /** RomCloud : force de la grille et éclat du centre des pixels. */
    data class LCD(
        val intensity: Float = 0.25f,
        val brightBoost: Float = 0.25f,
        override val colors: ShaderColors = ShaderColors(),
    ) : ShaderConfig

    /** RomCloud : largeur de la transition entre pixels (en pixels de l'écran). */
    data class Sharp(
        val width: Float = 1.0f,
        override val colors: ShaderColors = ShaderColors(),
    ) : ShaderConfig

    data class CUT(
        val useDynamicBlend: Boolean = true,
        val blendMinContrastEdge: Float = 0.0f,
        val blendMaxContrastEdge: Float = 1.0f,
        val blendMinSharpness: Float = 0.0f,
        val blendMaxSharpness: Float = 1.0f,
        val staticSharpness: Float = 0.5f,
        val edgeUseFastLuma: Boolean = true,
        val edgeMinValue: Float = 0.05f,
        val edgeMinContrast: Float = 2.00f,
        override val colors: ShaderColors = ShaderColors(),
    ) : ShaderConfig

    data class CUT2(
        val useDynamicBlend: Boolean = true,
        val blendMinContrastEdge: Float = 0.00f,
        val blendMaxContrastEdge: Float = 0.50f,
        val blendMinSharpness: Float = 0.0f,
        val blendMaxSharpness: Float = 0.75f,
        val staticSharpness: Float = 0.75f,
        val edgeUseFastLuma: Boolean = false,
        val softEdgesSharpening: Boolean = true,
        val softEdgesSharpeningAmount: Float = 1.0f,
        val hardEdgesSearchMaxError: Float = 0.25f,
        override val colors: ShaderColors = ShaderColors(),
    ) : ShaderConfig

    data class CUT3(
        val useDynamicBlend: Boolean = true,
        val blendMinContrastEdge: Float = 0.00f,
        val blendMaxContrastEdge: Float = 0.50f,
        val blendMinSharpness: Float = 0.0f,
        val blendMaxSharpness: Float = 0.75f,
        val staticSharpness: Float = 0.75f,
        val edgeUseFastLuma: Boolean = false,
        val softEdgesSharpening: Boolean = true,
        val softEdgesSharpeningAmount: Float = 1.0f,
        val hardEdgesSearchMaxError: Float = 0.25f,
        val hardEdgesSearchMaxDistance: Int = 4,
        override val colors: ShaderColors = ShaderColors(),
    ) : ShaderConfig
}
