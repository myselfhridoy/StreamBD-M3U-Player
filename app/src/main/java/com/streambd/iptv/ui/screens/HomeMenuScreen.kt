package com.streambd.iptv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable // ★ নতুন ইমপোর্ট
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streambd.iptv.ui.components.ConfirmDialog
import com.streambd.iptv.ui.nav.Routes
import com.streambd.iptv.ui.theme.*
import com.streambd.iptv.util.DeviceType
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

private const val HOME_COLUMNS = 4

private data class HomeItem(val label: String, val icon: ImageVector, val route: String?)

private val homeItems = listOf(
    HomeItem("Live",     Icons.Filled.LiveTv,       Routes.LIVE),
    HomeItem("Movies",   Icons.Filled.Videocam,     Routes.MOVIES),
    HomeItem("Series",   Icons.Filled.VideoLibrary, Routes.SERIES),
    HomeItem("FTP",      Icons.Filled.Dns,          Routes.FTP),
    HomeItem("TV Guide", Icons.Filled.Dvr,          Routes.GUIDE),
    HomeItem("Search",   Icons.Filled.Search,       Routes.SEARCH),
    HomeItem("Settings", Icons.Filled.Settings,     Routes.SETTINGS),
    HomeItem("Exit",     Icons.Filled.ExitToApp,    null),
)

@Composable
fun HomeMenuScreen(onOpen: (String) -> Unit, onExit: () -> Unit) {
    val ctx = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }

    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000L)
        }
    }

    val isTv = remember { DeviceType.isTv(ctx) }

    // ★ নতুন: শেষবার ফোকাস করা টাইলের ইনডেক্স এবং প্রতিটি টাইলের জন্য আলাদা FocusRequester
    var lastIndex by rememberSaveable { mutableIntStateOf(0) }
    val requesters = remember { List(homeItems.size) { FocusRequester() } }

    LaunchedEffect(Unit) {
        if (isTv) runCatching {
            requesters[lastIndex.coerceIn(0, homeItems.lastIndex)].requestFocus()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1B1B), Color(0xFF0B0B0B)))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = timeFormat.format(java.util.Date(now)),
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.height(20.dp))

            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                homeItems.chunked(HOME_COLUMNS).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { colIndex, item ->
                            // ★ নতুন: গ্লোবাল ইনডেক্স বের করা হলো
                            val index = rowIndex * HOME_COLUMNS + colIndex
                            
                            HomeTile(
                                item = item,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(requesters[index]), // ★ নির্দিষ্ট ইনডেক্সের রিকোয়েস্টার
                                onFocus = { lastIndex = index }, // ★ ফোকাস পেলে ইনডেক্স আপডেট
                            ) {
                                if (item.route == null) showExitDialog = true
                                else onOpen(item.route)
                            }
                        }
                        repeat(HOME_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) } // শেষ সারির ফাঁকা ঘর
                    }
                }
            }
        }
    }

    if (showExitDialog) {
        ConfirmDialog(
            title = "Exit StreamBD IPTV?",
            body = "The app will close and playback will stop.",
            cancelLabel = "CANCEL",
            confirmLabel = "EXIT",
            confirmIsDangerous = true,
            onCancel = { showExitDialog = false },
            onConfirm = {
                showExitDialog = false
                onExit()
            },
        )
    }
}

// ★ নতুন: onFocus প্যারামিটার যুক্ত করা হলো
@Composable
private fun HomeTile(
    item: HomeItem, 
    modifier: Modifier = Modifier, 
    onFocus: () -> Unit = {},
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val tint = if (focused) Accent else TextPrimary

    Column(
        modifier
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Accent else Color.Transparent, shape)
            .onFocusChanged { 
                focused = it.isFocused
                if (it.isFocused) onFocus() // ★ ফোকাস পেলে কলব্যাক ট্রিগার
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            item.label,
            color = tint,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
