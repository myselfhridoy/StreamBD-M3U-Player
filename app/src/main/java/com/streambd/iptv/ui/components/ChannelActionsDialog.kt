package com.streambd.iptv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.ui.theme.Accent
import com.streambd.iptv.ui.theme.Surface1
import com.streambd.iptv.ui.theme.TextMuted
import com.streambd.iptv.ui.theme.TextPrimary

@Composable
fun ChannelActionsDialog(
    channel: ChannelEntity,
    onToggleFavorite: () -> Unit,
    onToggleLock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    AppDialog(onDismiss = onDismiss, width = 360.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = channel.name,
                color = TextPrimary,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text("%02d".format(channel.number), color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))

            ActionRow(
                icon = if (channel.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                tint = if (channel.isFavorite) Accent else TextPrimary,
                label = if (channel.isFavorite) "Remove from Favorites" else "Add to Favorites",
                modifier = Modifier.focusRequester(firstFocus),
                onClick = onToggleFavorite,
            )
            Spacer(Modifier.height(4.dp))
            ActionRow(
                icon = if (channel.isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                tint = if (channel.isLocked) Accent else TextPrimary,
                label = if (channel.isLocked) "Unlock channel" else "Lock channel",
                onClick = onToggleLock,
            )

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppDialogButton("DONE", Accent, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    tint: Color,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Surface1 else Color.Transparent)
            .border(2.dp, if (focused) Accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = TextPrimary, fontSize = 15.sp)
    }
}
