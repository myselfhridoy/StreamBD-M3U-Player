package com.streambd.iptv.ui.screens.live

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import coil.compose.AsyncImage
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.player.PlayerUiState
import com.streambd.iptv.ui.player.FullscreenPlayer
import com.streambd.iptv.ui.player.PlayerSurface
import com.streambd.iptv.ui.player.TrackSelectionDialog
import com.streambd.iptv.ui.player.VolumeOverlay
import com.streambd.iptv.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(vm: LiveViewModel = hiltViewModel()) {
    val groups   by vm.groups.collectAsStateWithLifecycle()
    val selected by vm.selectedGroup.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val focused  by vm.focusedChannel.collectAsStateWithLifecycle()
    val state    by vm.playerState.collectAsStateWithLifecycle()

    var showTracks by remember { mutableStateOf(false) }

    LaunchedEffect(focused?.id) {
        val ch = focused ?: return@LaunchedEffect
        delay(400L)
        vm.playPreview(ch)
    }

    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(state.isFullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        if (state.isFullscreen) {
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(Modifier.fillMaxSize().background(BgDark)) {
        if (!state.isFullscreen) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.45f).fillMaxHeight()) {
                    GroupTabs(groups, selected, vm::selectGroup)
                    ChannelList(
                        channels = channels,
                        focusedId = focused?.id,
                        onFocus = vm::onFocus,
                        onSelect = { vm.openFullscreen() },
                    )
                }
                PreviewPane(state, vm, Modifier.weight(0.55f).fillMaxHeight())
            }
        }

        if (state.isFullscreen) {
            FullscreenPlayer(
                player = vm.player.player,
                state = state,
                onExit = vm::closeFullscreen,
                onTogglePlay = vm::togglePlayPause,
                onToggleFavorite = { state.current?.let(vm::toggleFavorite) },
                onOpenTracks = { showTracks = true },
                onOpenSettings = { /* step 4 */ },
            )
        }
    }

    if (state.volumeOverlayVisible) VolumeOverlay(level = state.volumeLevel)

    if (showTracks) {
        TrackSelectionDialog(
            info = state.tracks,
            onConfirm = { v, a ->
                vm.applyTrackChoice(C.TRACK_TYPE_VIDEO, v)
                vm.applyTrackChoice(C.TRACK_TYPE_AUDIO, a)
                showTracks = false
            },
            onDismiss = { showTracks = false },
        )
    }
}

/* ------------------------------ group tabs ------------------------------ */

@Composable
private fun GroupTabs(
    groups: List<GroupEntity>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(TopBar)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { GroupTab("All", selectedId == null) { onSelect(null) } }
        items(groups, key = { it.id }) { g ->
            GroupTab(g.name, selectedId == g.id) { onSelect(g.id) }
        }
    }
}

@Composable
private fun GroupTab(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = when {
        selected -> Accent
        focused  -> TextPrimary
        else     -> TextMuted
    }
    Text(
        text = label,
        color = color,
        fontSize = 15.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/* ----------------------------- channel list ----------------------------- */

@Composable
private fun ChannelList(
    channels: List<ChannelEntity>,
    focusedId: Long?,
    onFocus: (Long) -> Unit,
    onSelect: () -> Unit,
) {
    if (channels.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading playlist…", color = TextMuted)
        }
        return
    }

    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(channels.first().id) {
        runCatching { firstRequester.requestFocus() }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(channels, key = { _, c -> c.id }) { index, ch ->
            ChannelRow(
                channel = ch,
                focused = ch.id == focusedId,
                onFocus = { onFocus(ch.id) },
                onSelect = onSelect,
                modifier = if (index == 0) Modifier.focusRequester(firstRequester) else Modifier,
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    focused: Boolean,
    onFocus: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (focused) Accent else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Panel else Color.Transparent)
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
            .onFocusChanged { if (it.isFocused) onFocus() }
            .clickable(onClick = {
                onFocus()
                onSelect()
            })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d".format(channel.number),
            color = TextMuted,
            fontSize = 16.sp,
            modifier = Modifier.width(36.dp),
        )
        AsyncImage(
            model = channel.logoUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = channel.name,
            color = TextPrimary,
            fontSize = 16.sp,
            maxLines = 1,
        )
    }
}

/* ------------------------------ preview -------------------------------- */

@Composable
private fun PreviewPane(
    state: PlayerUiState,
    vm: LiveViewModel,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val ch = state.current
        if (ch == null) {
            Text("Select a channel", color = TextMuted)
        } else {
            PlayerSurface(
                player = vm.player.player,
                aspectMode = state.aspectMode,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
