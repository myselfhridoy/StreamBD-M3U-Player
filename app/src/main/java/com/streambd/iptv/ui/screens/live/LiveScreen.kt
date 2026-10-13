package com.streambd.iptv.ui.screens.live

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.streambd.iptv.ui.components.PinDialog
import com.streambd.iptv.ui.components.ChannelActionsDialog
import com.streambd.iptv.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(
    preselectChannelId: Long = -1L,
    vm: LiveViewModel = hiltViewModel(),
) {
    val groups   by vm.groups.collectAsStateWithLifecycle()
    val selected by vm.selectedGroup.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val allChannels by vm.allChannels.collectAsStateWithLifecycle()
    val focused  by vm.focusedChannel.collectAsStateWithLifecycle()
    val state    by vm.playerState.collectAsStateWithLifecycle()
    val jumpBuffer by vm.jumpDisplay.collectAsStateWithLifecycle()
    val unlocked by vm.sessionUnlocked.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()

    var showTracks by remember { mutableStateOf(false) }
    var pendingUnlock by remember { mutableStateOf<ChannelEntity?>(null) }
    var actionsChannelId by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(vm) {
        onDispose { vm.setJumpEnabled(false) }
    }
    LaunchedEffect(state.isFullscreen) {
        vm.setJumpEnabled(!state.isFullscreen)
    }

    LaunchedEffect(preselectChannelId) {
        if (preselectChannelId > 0L) {
            vm.selectGroup(null)
            vm.focusChannel(preselectChannelId)
        }
    }

    LaunchedEffect(focused?.id, unlocked) {
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
                        onSelect = { channel ->
                            if (channel.isLocked && !unlocked) pendingUnlock = channel
                            else vm.openFullscreen()
                        },
                        onLongPress = { id -> actionsChannelId = id },
                    )
                }
                PreviewPane(state, focused, unlocked, vm, Modifier.weight(0.55f).fillMaxHeight())
            }
        }

        if (state.isFullscreen) {
            val current = allChannels.firstOrNull { it.id == state.current?.id } ?: state.current
            val groupName = groups.firstOrNull { it.id == current?.groupId }?.name ?: "Live"
            FullscreenPlayer(
                player = vm.player.player,
                state = state,
                isFavorite = current?.isFavorite == true,
                isLocked = current?.isLocked == true,
                kindLabel = "LIVE",
                groupName = groupName,
                nowNext = nowNext,
                showNumber = true,
                onExit = vm::closeFullscreen,
                onTogglePlay = vm::togglePlayPause,
                onToggleFavorite = {
                    current?.let(vm::toggleFavorite)
                },
                onToggleLock = { current?.let(vm::toggleLock) },
                onOpenTracks = { showTracks = true },
                onOpenSettings = { /* step 4 */ },
            )
        }

        if (jumpBuffer.isNotEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopEnd) {
                Text(
                    text = jumpBuffer,
                    color = Accent,
                    fontSize = 32.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(Panel.copy(alpha = 0.92f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
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

    pendingUnlock?.let { target ->
        PinDialog(
            title = "Enter PIN",
            verify = vm::verifyPin,
            onSuccess = {
                pendingUnlock = null
                vm.unlockAndPlay(target)
            },
            onDismiss = { pendingUnlock = null },
        )
    }

    val actionsChannel = actionsChannelId?.let { id -> allChannels.firstOrNull { it.id == id } }
    if (actionsChannel != null) {
        ChannelActionsDialog(
            channel = actionsChannel,
            onToggleFavorite = { vm.toggleFavorite(actionsChannel) },
            onToggleLock = { vm.toggleLock(actionsChannel) },
            onDismiss = { actionsChannelId = null },
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
        item {
            GroupTab("Favorites", selectedId == LIVE_GROUP_FAVORITES) {
                onSelect(LIVE_GROUP_FAVORITES)
            }
        }
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
    onSelect: (ChannelEntity) -> Unit,
    onLongPress: (Long) -> Unit,
) {
    if (channels.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading playlist…", color = TextMuted)
        }
        return
    }

    val listState = rememberLazyListState()
    val focusIndex = channels.indexOfFirst { it.id == focusedId }.let { if (it >= 0) it else 0 }
    val rowRequester = remember(focusedId, channels.first().id) { FocusRequester() }
    LaunchedEffect(focusIndex, channels.first().id) {
        listState.scrollToItem(focusIndex)
        runCatching { rowRequester.requestFocus() }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        itemsIndexed(channels, key = { _, c -> c.id }) { index, ch ->
            ChannelRow(
                channel = ch,
                focused = ch.id == focusedId,
                onFocus = { onFocus(ch.id) },
                onSelect = { onSelect(ch) },
                onLongPress = { onLongPress(ch.id) },
                modifier = if (index == focusIndex) Modifier.focusRequester(rowRequester) else Modifier,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    focused: Boolean,
    onFocus: () -> Unit,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val borderColor = if (focused) Accent else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Panel else Color.Transparent)
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
            .onFocusChanged { if (it.isFocused) onFocus() }
            .combinedClickable(
                onClick = {
                    onFocus()
                    onSelect()
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onFocus()
                    onLongPress()
                },
            )
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
        if (channel.isLocked) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Locked",
                tint = Accent,
                modifier = Modifier.size(14.dp),
            )
        }
        if (channel.isFavorite) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "Favorite",
                tint = Accent,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = channel.name,
            color = TextPrimary,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/* ------------------------------ preview -------------------------------- */

@Composable
private fun PreviewPane(
    state: PlayerUiState,
    focused: ChannelEntity?,
    unlocked: Boolean,
    vm: LiveViewModel,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val lockedHere = focused?.isLocked == true && !unlocked
        if (lockedHere) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Accent, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("Locked channel", color = TextMuted, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("Enter PIN to watch", color = TextMuted, fontSize = 12.sp)
            }
        } else if (state.current == null) {
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
