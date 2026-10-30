package com.streambd.iptv.ui.screens.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import com.streambd.iptv.ui.components.PanelList
import com.streambd.iptv.ui.components.PanelRow
import com.streambd.iptv.ui.components.PanelScaffold
import com.streambd.iptv.ui.components.PanelTab
import com.streambd.iptv.ui.player.FullscreenPlayer
import com.streambd.iptv.ui.player.PlayerDialogs
import com.streambd.iptv.ui.player.rememberPlayerDialogs
import com.streambd.iptv.ui.theme.*

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

    var listOpen by rememberSaveable { mutableStateOf(preselectChannelId <= 0L) }
    val dialogs = rememberPlayerDialogs()

    val playingId = state.current?.id

    val tabs = remember(groups) {
        listOf(PanelTab(null, "All"), PanelTab(LIVE_GROUP_FAVORITES, "Favorites")) +
            groups.map { PanelTab(it.id, it.name) }
    }

    val emptyText = when {
        allChannels.isEmpty() ->
            "No live channels in your playlist.\nTry Movies or Series, or refresh the playlist in Settings."
        selected == LIVE_GROUP_FAVORITES ->
            "No favorite channels yet.\nLong-press a channel to add one."
        else -> "No channels in this group."
    }

    LaunchedEffect(Unit) { vm.onEnter() }
    LaunchedEffect(preselectChannelId) {
        // চ্যানেল আর না থাকলে (প্লেলিস্ট বদলে গেছে) তালিকা খুলে দেওয়া, কালো স্ক্রিন নয়
        if (preselectChannelId > 0L && !vm.playById(preselectChannelId)) {
            listOpen = true
        }
    }

    DisposableEffect(vm) {
        vm.setJumpEnabled(true)
        onDispose {
            vm.setJumpEnabled(false)
            vm.player.pause()
            vm.player.setFullscreen(false)
        }
    }

    val current = allChannels.firstOrNull { it.id == playingId } ?: state.current
    val groupName = groups.firstOrNull { it.id == current?.groupId }?.name ?: "Live"
    val startIndex = remember(channels, playingId) { channels.indexOfFirst { it.id == playingId } }

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
            onOpenTracks = dialogs::openTracks,
            onOpenSettings = dialogs::openAspect,
            onStep = vm::step,
            onRetry = vm::retry,
            listOpen = listOpen,
            onListOpenChange = { listOpen = it },
            listContent = {
                PanelScaffold(tabs, selected, vm::selectGroup) {
                    PanelList(
                        items = channels,
                        key = { it.id },
                        startIndex = startIndex,
                        resetKey = selected,
                        emptyText = emptyText,
                        loading = !loaded,
                    ) { channel, rowModifier ->
                        PanelRow(
                            title = channel.name,
                            modifier = rowModifier,
                            number = "%02d".format(channel.number),
                            logoUrl = channel.logoUrl,
                            playing = channel.id == playingId,
                            locked = channel.isLocked,
                            favorite = channel.isFavorite,
                            isPremium = channel.isPremium, // নতুন: প্রিমিয়াম আইকন দেখানোর জন্য
                            onLongClick = { dialogs.openActions(channel.id) },
                        ) { vm.requestPlay(channel) }
                    }
                }
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

    PlayerDialogs(
        dialogs = dialogs,
        state = state,
        items = allChannels,
        pendingUnlock = pendingUnlock,
        verifyPin = vm::verifyPin,
        onUnlocked = vm::confirmUnlock,
        onDismissUnlock = vm::dismissUnlock,
        onApplyTracks = { video, audio ->
            vm.applyTrackChoice(C.TRACK_TYPE_VIDEO, video)
            vm.applyTrackChoice(C.TRACK_TYPE_AUDIO, audio)
        },
        onAspectChange = vm::setAspectMode,
        onToggleFavorite = vm::toggleFavorite,
        onToggleLock = vm::toggleLock,
    )
}
