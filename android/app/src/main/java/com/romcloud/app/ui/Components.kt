package com.romcloud.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/** Jaquette du jeu, avec repli sur le titre si aucune image n'est disponible. */
@Composable
fun Cover(url: String?, title: String, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val placeholder = @Composable {
        Box(
            Modifier
                .size(width, height)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (width >= 80.dp) {
                Text(
                    title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(6.dp), maxLines = 4,
                )
            } else {
                Icon(Icons.Filled.SportsEsports, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (url == null) {
        Box(modifier) { placeholder() }
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = title,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(width, height).clip(shape),
        loading = { placeholder() },
        error = { placeholder() },
    )
}

@Composable
fun Banner(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
