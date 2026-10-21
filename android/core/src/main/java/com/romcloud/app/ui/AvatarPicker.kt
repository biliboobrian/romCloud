package com.romcloud.app.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.romcloud.core.R

/** Avatar du profil (émoji), sinon l'icône du profil. */
@Composable
fun ProfileAvatar(avatar: String?, size: Dp, tint: Color = MaterialTheme.colorScheme.primary) {
    if (avatar.isNullOrBlank()) Icon(Icons.Filled.AccountCircle, null, Modifier.size(size), tint = tint)
    else Box(Modifier.size(size), contentAlignment = Alignment.Center) { Text(avatar, fontSize = (size.value * 0.7f).sp) }
}

/**
 * Choix de l'avatar du profil (téléphone et TV) parmi ceux proposés par le serveur ; la première
 * case retire l'avatar. Case choisie encadrée ; sur la TV, case sous le focus encadrée aussi.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarPicker(selected: String?, avatars: List<String>, onPick: (String?) -> Unit, itemSize: Dp = 48.dp) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AvatarChoice(selected.isNullOrBlank(), itemSize, stringResource(R.string.profile_no_avatar), { onPick(null) }) {
            Icon(Icons.Filled.AccountCircle, null, Modifier.size(itemSize * 0.6f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (avatar in avatars) {
            AvatarChoice(avatar == selected, itemSize, avatar, { onPick(avatar) }) {
                Text(avatar, fontSize = (itemSize.value * 0.5f).sp)
            }
        }
    }
}

@Composable
private fun AvatarChoice(selected: Boolean, size: Dp, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val border = when {
        focused -> MaterialTheme.colorScheme.onSurface
        selected -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (selected) 0.9f else 0.5f))
            .border(if (focused) 3.dp else 2.dp, border, shape)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
