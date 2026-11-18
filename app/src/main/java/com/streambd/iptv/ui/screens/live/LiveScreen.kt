package com.streambd.iptv.ui.screens.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
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
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.ui.components.PanelList
import com.streambd.iptv.ui.components.PanelRow
import com.streambd.iptv.ui.components.PanelScaffold
import com.streambd.iptv.ui.components.PanelTab
import com.streambd.iptv.ui.player.FullscreenPlayer
import com.streambd.iptv.ui.player.PlayerDialogs
import com.streambd.iptv.ui.player.rememberPlayerDialogs
import com.streambd.iptv.ui.theme.*

/** তালিকার সারি: ফোল্ডার, "ফিরে যান", বা চ্যানেল। */
private sealed interface LiveRow {
    val key: Any
    data class Folder(val folder: LiveFolder) : LiveRow { override val key: Any get() = "f:" + folder.key }
    data class Up(val title: String, val hint: String) : LiveRow { override val key: Any get() = "up" }
    data class Chan(val channel: ChannelEntity) : LiveRow { override val key: Any get() = channel.id }
}

@Composable
fun LiveScreen(
    preselectChannelId: Long = -1L,
    onBack: () -> Unit = {},
    onOpenProviders: () -> Unit = {},
    vm: LiveViewModel = hiltViewModel(),
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val userGroups by vm.userGroups.collectAsStateWithLifecycle()
    val hasStreamBd by vm.hasStreamBd.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val openFolder by vm.openFolder.collectAsStateWithLifecycle()
    val openSub by vm.openSub.collectAsStateWithLifecycle()
    val lastFolder by vm.lastFolder.collectAsStateWithLifecycle()
    val lastSub by vm.lastSub.collectAsStateWithLifecycle()
    val subFolders by vm.subFolders.collectAsStateWithLifecycle()
    val listChannels by vm.listChannels.collectAsStateWithLifecycle()
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

    val tabs = remember(userGroups, hasStreamBd) {
        listOf(PanelTab(null, "All"), PanelTab(LIVE_GROUP_FAVORITES, "Favorites")) +
            (if (hasStreamBd) listOf(PanelTab(LIVE_GROUP_STREAMBD, "StreamBD IPTV")) else emptyList()) +
            userGroups.map { PanelTab(it.id, it.name) }
    }

    val inStreamBd = selected == LIVE_GROUP_STREAMBD
    val folderMode = inStreamBd && openFolder == null
    val topTitle = folders.firstOrNull { it.key == openFolder }?.name ?: "StreamBD IPTV"
    val openSubName by vm.openSubName.collectAsStateWithLifecycle()
    val upTitle = if (openSub != null) openSubName else topTitle
    val upHint = if (openSub != null) "Back to $topTitle" else "Back to folders"
    val rows = remember(listChannels, subFolders, folders, folderMode, inStreamBd, upTitle, upHint) {
        when {
            folderMode -> folders.map { LiveRow.Folder(it) }
            inStreamBd -> listOf<LiveRow>(LiveRow.Up(upTitle, upHint)) +
                subFolders.map { LiveRow.Folder(it) } + listChannels.map { LiveRow.Chan(it) }
            else -> listChannels.map { LiveRow.Chan(it) }
        }
    }

    val emptyText = when {
        allChannels.isEmpty() ->
            "No live channels yet.\nAdd a playlist in Providers (Settings), or try Movies/Series."
        selected == LIVE_GROUP_FAVORITES ->
            "No favorite channels yet.\nLong-press a channel to add one."
        inStreamBd -> "No channels in this folder."
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
    val startIndex = remember(rows, playingId, folderMode, lastFolder, lastSub, openSub) {
        when {
            folderMode -> rows.indexOfFirst { it is LiveRow.Folder && it.folder.key == lastFolder }.coerceAtLeast(0)
            inStreamBd && openSub == null && rows.indexOfFirst { it is LiveRow.Folder && it.folder.key == lastSub } >= 0 ->
                rows.indexOfFirst { it is LiveRow.Folder && it.folder.key == lastSub }
            else -> {
                val i = rows.indexOfFirst { it is LiveRow.Chan && it.channel.id == playingId }
                // ফোল্ডারের ভেতরে প্রথম সারি "ফিরে যান"; চালু চ্যানেল না থাকলে প্রথম চ্যানেলে ফোকাস
                if (i >= 0) i else if (inStreamBd && rows.size > 1) 1 else 0
            }
        }
    }

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
                    // ফোল্ডারের ভেতরে Back চাপলে তালিকা বন্ধ না হয়ে ফোল্ডারের তালিকায় ফেরে
                    BackHandler(enabled = inStreamBd && openFolder != null) { vm.closeFolder() }
                    PanelList(
                        items = rows,
                        key = { it.key },
                        startIndex = startIndex,
                        resetKey = listOf(selected, openFolder, openSub, folderMode),
                        focusKey = openSub ?: openFolder,
                        emptyText = emptyText,
                        loading = !loaded,
                        emptyActionLabel = if (allChannels.isEmpty()) "Add a provider" else null,
                        onEmptyAction = onOpenProviders,
                    ) { row, rowModifier ->
                        when (row) {
                            is LiveRow.Folder -> PanelRow(
                                title = row.folder.name,
                                modifier = rowModifier,
                                subtitle = "${row.folder.count} channels",
                                leadingIcon = Icons.Filled.Folder,
                                showChevron = true,
                            ) { vm.openFolder(row.folder.key, row.folder.name) }
                            is LiveRow.Up -> PanelRow(
                                title = row.title,
                                modifier = rowModifier,
                                subtitle = row.hint,
                                leadingIcon = Icons.Filled.ArrowBack,
                            ) { vm.closeFolder() }
                            is LiveRow.Chan -> {
                                val channel = row.channel
                                PanelRow(
                                    title = channel.name,
                                    modifier = rowModifier,
                                    number = "%02d".format(channel.number),
                                    logoUrl = channel.logoUrl,
                                    playing = channel.id == playingId,
                                    locked = channel.isLocked,
                                    favorite = channel.isFavorite,
                                    isPremium = channel.isPremium,
                                    onLongClick = { dialogs.openActions(channel.id) },
                                ) { vm.requestPlay(channel) }
                            }
                        }
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