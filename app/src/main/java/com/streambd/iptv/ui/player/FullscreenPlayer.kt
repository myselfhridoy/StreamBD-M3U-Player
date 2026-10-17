package com.streambd.iptv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.streambd.iptv.data.epg.NowNext
import com.streambd.iptv.player.PlayerUiState
import com.streambd.iptv.ui.theme.*

@Composable
fun FullscreenPlayer(
    player: ExoPlayer,
    state: PlayerUiState,
    isFavorite: Boolean,
    isLocked: Boolean,
    kindLabel: String,
    groupName: String,
    nowNext: NowNext? = null,
    showNumber: Boolean = true,
    onExit: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleLock: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BackHandler(enabled = true) { onExit() }

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(interactionTick, state.isPlaying) {
        if (!state.isPlaying) {
            controlsVisible = true
            return@LaunchedEffect
        }
        controlsVisible = true
        kotlinx.coroutines.delay(4_000L)
        controlsVisible = false
    }

    fun kickControls() {
        interactionTick++
        controlsVisible = true
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) kickControls()
                false
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                    kickControls()
                }
            },
    ) {
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
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.align(Alignment.TopStart).padding(24.dp)
                            .clip(RoundedCornerShape(10.dp)).background(Panel.copy(alpha = 0.85f))
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
                            Text(
                                text = if (showNumber) "%02d  ${ch.name}".format(ch.number) else ch.name,
                                color = TextPrimary,
                                fontSize = 18.sp,
                                maxLines = 1,
                            )
                            Text(groupName, color = TextMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Chip(kindLabel)
                                Chip(state.aspectMode.label.uppercase())
                            }
                            if (nowNext != null) {
                                Spacer(Modifier.height(8.dp))
                                NowNextStrip(nowNext)
                            }
                        }
                    }

                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f))
                            .navigationBarsPadding()
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
                                onClick = { kickControls(); onTogglePlay() },
                            )
                            Spacer(Modifier.width(8.dp))
                            IconAction(
                                icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                tint = if (isFavorite) Accent else TextPrimary,
                                onClick = { kickControls(); onToggleFavorite() },
                            )
                            Spacer(Modifier.width(8.dp))
                            IconAction(
                                icon = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                tint = if (isLocked) Accent else TextPrimary,
                                onClick = { kickControls(); onToggleLock() },
                            )
                            Spacer(Modifier.weight(1f))
                            IconAction(
                                icon = Icons.Filled.AspectRatio,
                                onClick = { kickControls(); onOpenSettings() },
                            )
                            Spacer(Modifier.width(8.dp))
                            IconAction(
                                icon = Icons.Filled.Settings,
                                onClick = { kickControls(); onOpenTracks() },
                            )
                        }
                    }
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
private fun NowNextStrip(nowNext: NowNext) {
    val timeFormat = remember {
        java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault())
    }

    Column(Modifier.widthIn(max = 380.dp)) {
        nowNext.current?.let { program ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NOW", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    program.title,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(timeFormat.format(java.util.Date(program.start)), color = TextMuted, fontSize = 10.sp)
            }
        }
        if (nowNext.current != null && nowNext.next != null) Spacer(Modifier.height(4.dp))
        nowNext.next?.let { program ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NEXT", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    program.title,
                    color = TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(timeFormat.format(java.util.Date(program.start)), color = TextMuted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    tint: Color = TextPrimary,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    )
}
