package com.streambd.iptv.ui.screens.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import coil.compose.AsyncImage
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.ui.components.ChannelActionsDialog
import com.streambd.iptv.ui.components.PinDialog
import com.streambd.iptv.ui.player.FullscreenPlayer
import com.streambd.iptv.ui.player.PlayerSettingsDialog
import com.streambd.iptv.ui.player.TrackSelectionDialog
import com.streambd.iptv.ui.theme.*

private val PanelScrim = Color.Black.copy(alpha = 0.72f)

@Composable
fun LiveScreen(
    preselectChannelId: Long = -1L,
    onBack: () -> Unit = {},
    vm: LiveViewModel = hiltViewModel(),
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val selected by vm.selectedGroup.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val allChannels by vm.allChannels.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val state by vm.playerState.collectAsStateWithLifecycle()
    val jumpBuffer by vm.jumpDisplay.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val pendingUnlock by vm.pendingUnlock.collectAsStateWithLifecycle()

    // Search থেকে নির্দিষ্ট চ্যানেল নিয়ে এলে তালিকা বন্ধ, নইলে খোলা অবস্থায় শুরু
    var listOpen by rememberSaveable { mutableStateOf(preselectChannelId <= 0L) }
    var showTracks by remember { mutableStateOf(false) }
    var showAspect by remember { mutableStateOf(false) }
    var actionsChannelId by remember { mutableStateOf<Long?>(null) }

    val emptyText = when {
        allChannels.isEmpty() ->
            "No live channels in your playlist.\nTry Movies or Series, or refresh the playlist in Settings."
        selected == LIVE_GROUP_FAVORITES ->
            "No favorite channels yet.\nLong-press a channel to add one."
        else -> "No channels in this group."
    }

    LaunchedEffect(Unit) { vm.onEnter() }
    LaunchedEffect(preselectChannelId) {
        if (preselectChannelId > 0L) vm.playById(preselectChannelId)
    }

    DisposableEffect(vm) {
        vm.setJumpEnabled(true)
        onDispose {
            vm.setJumpEnabled(false)
            vm.player.pause()
            vm.player.setFullscreen(false)
        }
    }

    // নতুন চ্যানেল চালু হলে (তালিকা থেকে, নম্বর টিপে, zap) তালিকা বন্ধ
    var lastPlayingId by remember { mutableStateOf(state.current?.id) }
    LaunchedEffect(state.current?.id) {
        val id = state.current?.id
        if (id != null && id != lastPlayingId) listOpen = false
        lastPlayingId = id
    }

    val current = allChannels.firstOrNull { it.id == state.current?.id } ?: state.current
    val groupName = groups.firstOrNull { it.id == current?.groupId }?.name ?: "Live"

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        FullscreenPlayer(
            player = vm.player.player,
            state = state,
            isFavorite = current?.isFavorite == true,
            isLocked = current?.isLocked == true,
            kindLabel = "LIVE",
            groupName = groupName,
            nowNext = nowNext,
            showNumber = true,
            onExit = onBack,
            onTogglePlay = vm::togglePlayPause,
            onToggleFavorite = { current?.let(vm::toggleFavorite) },
            onToggleLock = { current?.let(vm::toggleLock) },
            onOpenTracks = { showTracks = true },
            onOpenSettings = { showAspect = true },
            onStep = vm::step,
            onRetry = vm::retry,
            listOpen = listOpen,
            onListOpenChange = { listOpen = it },
            listContent = {
                LivePanel(
                    groups = groups,
                    selected = selected,
                    onSelectGroup = vm::selectGroup,
                    channels = channels,
                    playingId = state.current?.id,
                    loading = !loaded,
                    emptyText = emptyText,
                    onPlay = vm::requestPlay,
                    onLongPress = { id -> actionsChannelId = id },
                )
            },
        )

        if (jumpBuffer.isNotEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopEnd) {
                Text(
                    text = jumpBuffer,
                    color = Accent,
                    fontSize = 32.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Panel.copy(alpha = 0.92f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

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

    if (showAspect) {
        PlayerSettingsDialog(
            currentAspect = state.aspectMode,
            onAspectChange = vm::setAspectMode,
            onDismiss = { showAspect = false },
        )
    }

    if (pendingUnlock != null) {
        PinDialog(
            title = "Enter PIN",
            verify = vm::verifyPin,
            onSuccess = vm::confirmUnlock,
            onDismiss = vm::dismissUnlock,
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

/** ট্যাপ খেয়ে ফেলে, যাতে প্যানেলের ফাঁকা জায়গায় ট্যাপে তালিকা বন্ধ না হয়। */
private fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/* ------------------------------- panel -------------------------------- */

/** উপরে পুরো প্রস্থের গ্রুপ ট্যাব, নিচে বাম অর্ধেকে চ্যানেল তালিকা, ডান অর্ধেকে ভিডিও। */
@Composable
private fun LivePanel(
    groups: List<GroupEntity>,
    selected: Long?,
    onSelectGroup: (Long?) -> Unit,
    channels: List<ChannelEntity>,
    playingId: Long?,
    loading: Boolean,
    emptyText: String,
    onPlay: (ChannelEntity) -> Unit,
    onLongPress: (Long) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // ১. স্ক্রিনের একদম বাম দিক থেকে ব্যাকগ্রাউন্ড শুরু হবে (নচ থাকলেও সরে যাবে না)
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.5f)
                .background(PanelScrim)
        )

        // ২. কন্টেন্ট (ট্যাব এবং লিস্ট) সেফ এরিয়ার ভেতরে থাকবে
        Column(Modifier.fillMaxSize().displayCutoutPadding()) {
            GroupTabsBar(groups, selected, onSelectGroup)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    Modifier
                        .weight(0.5f)
                        .fillMaxHeight()
                        .consumeTaps(),
                ) {
                    ChannelList(
                        channels = channels,
                        playingId = playingId,
                        loading = loading,
                        emptyText = emptyText,
                        groupKey = selected,
                        onPlay = onPlay,
                        onLongPress = onLongPress,
                    )
                }
                Spacer(Modifier.weight(0.5f))
            }
        }
    }
}

@Composable
private fun GroupTabsBar(groups: List<GroupEntity>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelScrim)
            .consumeTaps()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { GroupTab("Favorites", selectedId == LIVE_GROUP_FAVORITES) { onSelect(LIVE_GROUP_FAVORITES) } }
        item { GroupTab("All", selectedId == null) { onSelect(null) } }
        items(groups, key = { it.id }) { g ->
            GroupTab(g.name, selectedId == g.id) { onSelect(g.id) }
        }
    }
}

@Composable
private fun GroupTab(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = if (selected) Accent else if (focused) TextPrimary else TextMuted,
        fontSize = 14.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp), // Alignment fixed
    )
}

/* ----------------------------- channel list ----------------------------- */

@Composable
private fun ChannelList(
    channels: List<ChannelEntity>,
    playingId: Long?,
    loading: Boolean,
    emptyText: String,
    groupKey: Long?,
    onPlay: (ChannelEntity) -> Unit,
    onLongPress: (Long) -> Unit,
) {
    if (channels.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
            } else {
                Text(emptyText, color = TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
        return
    }

    val listState = rememberLazyListState()
    // শুরুর সারি: চলমান চ্যানেল (গ্রুপে থাকলে), নইলে প্রথমটা। গ্রুপ বদলালেই কেবল নতুন করে ঠিক হয়,
    // ফোকাস সরলে নয়, ফলে আগের মতো স্ক্রল ঝাঁকুনি হয় না।
    val startIndex = remember(groupKey) {
        channels.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
    }
    val startFocus = remember(groupKey) { FocusRequester() }

    LaunchedEffect(groupKey) {
        listState.scrollToItem((startIndex - 2).coerceAtLeast(0))
    }
    // TV: তালিকা খোলার সময় একবারই ফোকাস (ট্যাবে ঘোরার সময় ফোকাস কাড়ি না)
    LaunchedEffect(Unit) {
        runCatching { startFocus.requestFocus() }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(channels.size, key = { channels[it].id }) { index ->
            val channel = channels[index]
            ChannelRow(
                channel = channel,
                playing = channel.id == playingId,
                onClick = { onPlay(channel) },
                onLongClick = { onLongPress(channel.id) },
                modifier = if (index == startIndex) Modifier.focusRequester(startFocus) else Modifier,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    playing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .border(2.dp, if (focused) Accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = 12.dp, vertical = 6.dp), // Alignment fixed (8+12 = 20dp)
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d".format(channel.number),
            color = TextMuted,
            fontSize = 14.sp,
            modifier = Modifier.width(34.dp),
        )
        AsyncImage(
            model = channel.logoUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = channel.name,
            color = if (playing) Accent else TextPrimary,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (channel.isLocked) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = Accent, modifier = Modifier.size(14.dp))
        }
        if (channel.isFavorite) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Favorite, contentDescription = "Favorite", tint = Accent, modifier = Modifier.size(14.dp))
        }
    }
}
