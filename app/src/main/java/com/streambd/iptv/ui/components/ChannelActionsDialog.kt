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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.ui.theme.Accent
import com.streambd.iptv.ui.theme.Panel
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
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 360.dp)
                .clip(RoundedCornerShape(8.dp)).background(Panel).padding(20.dp),
        ) {
            Text(
                text = channel.name,
                color = TextPrimary,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text("%02d".format(channel.number), color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))

            ActionRow(
                icon = if (channel.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                tint = if (channel.isFavorite) Accent else TextPrimary,
                label = if (channel.isFavorite) "Remove from Favorites" else "Add to Favorites",
                onClick = onToggleFavorite,
            )
            Spacer(Modifier.height(6.dp))
            ActionRow(
                icon = if (channel.isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                tint = if (channel.isLocked) Accent else TextPrimary,
                label = if (channel.isLocked) "Unlock channel" else "Lock channel",
                onClick = onToggleLock,
            )

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("DONE", color = Accent) }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, tint: Color, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (focused) Surface1 else Color.Transparent)
            .border(2.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = TextPrimary, fontSize = 15.sp)
    }
}