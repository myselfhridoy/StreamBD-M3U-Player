package com.streambd.iptv.ui.screens.ftp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C as Media3C
import androidx.media3.common.Player
import com.streambd.iptv.data.db.ProviderEntity
import com.streambd.iptv.data.ftp.FtpEntry
import com.streambd.iptv.ui.components.AppDialog
import com.streambd.iptv.ui.components.AppDialogButton
import com.streambd.iptv.ui.components.ScreenTopBar
import com.streambd.iptv.ui.components.sideInsets
import com.streambd.iptv.ui.player.FullscreenPlayer
import com.streambd.iptv.ui.player.PlayerDialogs
import com.streambd.iptv.ui.player.RadioRow
import com.streambd.iptv.ui.player.rememberPlayerDialogs
import com.streambd.iptv.ui.theme.*
import com.streambd.iptv.util.DeviceType
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun FtpScreen(
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    vm: FtpViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val isTv = remember { DeviceType.isTv(ctx) }
    val servers by vm.servers.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()

    var pickerOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    val dialogs = rememberPlayerDialogs()
    val listState = rememberLazyListState()

    val visible = remember(ui.entries, ui.query) {
        val q = ui.query.trim()
        if (q.isEmpty()) ui.entries else ui.entries.filter { it.name.contains(q, ignoreCase = true) }
    }
    val requesters = remember(visible) { List(visible.size) { FocusRequester() } }

    // ভিডিও শেষ হলে একই ফোল্ডারের পরের ফাইল (এপিসোড) চালায়
    val exo = vm.player.player
    DisposableEffect(exo, playing != null) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !vm.playNext()) vm.closePlayer()
            }
        }
        if (playing != null) exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }
    DisposableEffect(vm) { onDispose { vm.player.pause() } }

    // ফোল্ডার খোলা/ফেরার পর ফোকাস ঠিক জায়গায়
    LaunchedEffect(visible, playing == null, ui.loading, searchOpen, pickerOpen) {
        if (playing != null || ui.loading || visible.isEmpty() || searchOpen || pickerOpen) return@LaunchedEffect
        val idx = visible.indexOfFirst { it.url == ui.focusUrl }.coerceAtLeast(0)
        listState.scrollToItem(idx)
        delay(80)
        if (isTv) runCatching { requesters.getOrNull(idx)?.requestFocus() }
    }

    BackHandler(enabled = playing == null) {
        if (ui.query.isNotEmpty()) vm.setQuery("") else if (!vm.up()) onBack()
    }

    val current = playing
    if (current != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            FullscreenPlayer(
                player = exo,
                state = playerState,
                isFavorite = false,
                isLocked = false,
                kindLabel = "FTP",
                groupName = ui.crumbs.lastOrNull()?.name.orEmpty(),
                showNumber = false,
                onExit = vm::closePlayer,
                onTogglePlay = vm.player::togglePlayPause,
                onToggleFavorite = {},
                onToggleLock = {},
                onOpenTracks = dialogs::openTracks,
                onOpenSettings = dialogs::openAspect,
                onRetry = { vm.player.retry() },
            )
        }
        PlayerDialogs(
            dialogs = dialogs,
            state = playerState,
            items = emptyList(),
            pendingUnlock = null,
            verifyPin = { false },
            onUnlocked = {},
            onDismissUnlock = {},
            onApplyTracks = { video, audio ->
                vm.player.applyTrackChoice(Media3C.TRACK_TYPE_VIDEO, video)
                vm.player.applyTrackChoice(Media3C.TRACK_TYPE_AUDIO, audio)
            },
            onAspectChange = vm.player::setAspectMode,
            onToggleFavorite = {},
            onToggleLock = {},
        )
        return
    }

    Column(Modifier.fillMaxSize().background(BgDark)) {
        val selected = servers.firstOrNull { it.id == ui.serverId }
        ScreenTopBar(title = "FTP", onBack = onBack) {
            if (selected != null) {
                IconChip(Icons.Filled.Search, "Search") { searchOpen = true }
                Spacer(Modifier.width(8.dp))
                IconChip(Icons.Filled.Refresh, "Refresh") { vm.reload() }
                Spacer(Modifier.width(8.dp))
                ServerChip(selected.name) { pickerOpen = true }
            }
        }

        when {
            ui.blocked -> CenterMessage(
                "Activation required",
                "Activate the app to use FTP servers.",
                actionLabel = "BACK", onAction = onBack,
            )
            servers.isEmpty() -> CenterMessage(
                "No FTP server added",
                "Add one from Providers → Add FTP.",
                actionLabel = "OPEN PROVIDERS", onAction = onOpenProviders,
            )
            else -> Column(Modifier.fillMaxSize().sideInsets()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ui.crumbs.joinToString("  ›  ") { it.name.trimEnd('/') },
                        color = TextMuted, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (ui.query.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text("“${ui.query}”  ✕", color = Accent, fontSize = 13.sp,
                            modifier = Modifier.clickable { vm.setQuery("") })
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    when {
                        ui.loading -> CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.Center))
                        ui.error != null -> CenterMessage(
                            "Couldn't open this folder", ui.error.orEmpty(),
                            actionLabel = "RETRY", onAction = vm::reload,
                        )
                        visible.isEmpty() -> Text(
                            if (ui.query.isBlank()) "No folders or videos here." else "No match.",
                            color = TextMuted, modifier = Modifier.align(Alignment.Center),
                        )
                        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(visible, key = { _, e -> e.url }) { i, entry ->
                                FtpRow(
                                    entry = entry,
                                    modifier = Modifier.focusRequester(requesters[i]),
                                ) { if (entry.isDir) vm.open(entry) else vm.play(entry) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        ServerPickerDialog(
            servers = servers,
            selectedId = ui.serverId,
            onPick = { vm.selectServer(it); pickerOpen = false },
            onDismiss = { pickerOpen = false },
        )
    }
    if (searchOpen) {
        SearchDialog(
            initial = ui.query,
            onApply = { vm.setQuery(it); searchOpen = false },
            onDismiss = { searchOpen = false },
        )
    }
}

@Composable
private fun FtpRow(entry: FtpEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val sub = remember(entry) {
        buildList {
            entry.sizeBytes?.let { add(formatSize(it)) }
            entry.modified?.let { add(it) }
            if (entry.likelyUnsupported) add("may not play on this device")
        }.joinToString("  •  ")
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(shape)
            .background(if (focused) Surface1 else Color.Transparent)
            .border(2.dp, if (focused) Accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Movie,
            contentDescription = null,
            tint = if (entry.isDir) Accent else TextMuted,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = TextPrimary, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = TextMuted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun IconChip(icon: ImageVector, desc: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Icon(
        icon, contentDescription = desc, tint = if (focused) Accent else TextPrimary,
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .border(2.dp, if (focused) Accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(8.dp),
    )
}

/** TV-তে সুবিধাজনক ড্রপডাউন: ক্লিক করলে সার্ভারের তালিকা ডায়ালগে খোলে। */
@Composable
private fun ServerChip(name: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .widthIn(max = 200.dp)
            .clip(shape)
            .background(Surface1)
            .border(2.dp, if (focused) Accent else Divider, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = TextPrimary, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select server", tint = TextPrimary)
    }
}

@Composable
private fun ServerPickerDialog(
    servers: List<ProviderEntity>,
    selectedId: Long?,
    onPick: (ProviderEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    AppDialog(onDismiss = onDismiss) {
        Text("Select FTP server", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
        val start = servers.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        Column(Modifier.heightIn(max = 320.dp).padding(horizontal = 8.dp)) {
            LazyColumn {
                itemsIndexed(servers, key = { _, s -> s.id }) { i, s ->
                    RadioRow(
                        label = s.name,
                        sublabel = s.url,
                        selected = s.id == selectedId,
                        modifier = if (i == start) Modifier.focusRequester(first) else Modifier,
                    ) { onPick(s) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            AppDialogButton("CLOSE", TextMuted, onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
}

@Composable
private fun SearchDialog(initial: String, onApply: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    AppDialog(onDismiss = onDismiss) {
        Text("Search in this folder", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 12.dp))
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(4.dp)
        Box(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .height(48.dp)
                .clip(shape)
                .background(Surface1)
                .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Divider, shape)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                cursorBrush = SolidColor(Accent),
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onApply(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { focused = it.hasFocus },
            )
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
            AppDialogButton("CANCEL", TextMuted, onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            AppDialogButton("SEARCH", Accent) { onApply(text) }
        }
    }
    LaunchedEffect(Unit) { delay(100); runCatching { field.requestFocus() } }
}

@Composable
private fun CenterMessage(title: String, body: String, actionLabel: String, onAction: () -> Unit) {
    val btn = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = TextMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            AppDialogButton(actionLabel, Accent, Modifier.focusRequester(btn), onAction)
        }
    }
    LaunchedEffect(Unit) { delay(100); runCatching { btn.requestFocus() } }
}

private fun formatSize(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024 * 1024)
    if (gb >= 1) return String.format(Locale.US, "%.2f GB", gb)
    val mb = bytes / (1024.0 * 1024)
    if (mb >= 1) return String.format(Locale.US, "%.0f MB", mb)
    return String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}
