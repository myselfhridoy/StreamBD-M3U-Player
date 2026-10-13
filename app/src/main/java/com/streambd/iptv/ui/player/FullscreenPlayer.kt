package com.streambd.iptv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.streambd.iptv.player.PlayerUiState
import com.streambd.iptv.ui.theme.*

@Composable
fun FullscreenPlayer(
    player: ExoPlayer,
    state: PlayerUiState,
    onExit: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BackHandler(enabled = true) { onExit() }

    Box(Modifier.fillMaxSize().background(Color.Black).focusable()) {
        PlayerSurface(
            player = player,
            aspectMode = state.aspectMode,
            modifier = Modifier.fillMaxSize(),
        )

        if (state.isBuffering) {
            CircularProgressIndicator(
                color = Accent,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        val ch = state.current
        if (ch != null) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel.copy(alpha = 0.85f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = ch.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("%02d  ${ch.name}".format(ch.number), color = TextPrimary, fontSize = 18.sp)
                    Text("Group", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("LIVE")
                        Chip(state.aspectMode.label.uppercase())
                    }
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                val showSeek = state.duration > 0L
                if (showSeek) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatClock(state.position), color = TextPrimary, fontSize = 12.sp)
                        Slider(
                            value = state.position.toFloat(),
                            onValueChange = { player.seekTo(it.toLong()) },
                            valueRange = 0f..state.duration.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Accent,
                                activeTrackColor = Accent,
                                inactiveTrackColor = TextPrimary.copy(alpha = 0.25f),
                            ),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text(formatClock(state.duration), color = TextPrimary, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconAction(
                        icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        onClick = onTogglePlay,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconAction(Icons.Filled.Favorite, onClick = onToggleFavorite)
                    Spacer(Modifier.width(8.dp))
                    IconAction(Icons.Filled.Lock, onClick = { /* wired in step 7 */ })
                    Spacer(Modifier.weight(1f))
                    IconAction(Icons.Filled.Tune, onClick = onOpenTracks)
                    Spacer(Modifier.width(8.dp))
                    IconAction(Icons.Filled.Settings, onClick = onOpenSettings)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Accent.copy(alpha = 0.25f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun IconAction(
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = TextPrimary,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    )
}
