package com.streambd.iptv.ui.screens

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
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
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

private data class HomeItem(val label: String, val icon: ImageVector, val route: String?)

private val homeItems = listOf(
    HomeItem("All",      Icons.Filled.Tv,           Routes.LIVE),
    HomeItem("Live",     Icons.Filled.LiveTv,       Routes.LIVE),
    HomeItem("Movies",   Icons.Filled.Videocam,     Routes.MOVIES),
    HomeItem("Series",   Icons.Filled.VideoLibrary, Routes.SERIES),
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

    // শুধু TV-তে প্রথম টাইলে ফোকাস। ফোনে দিলে "All" সবসময় হাইলাইট দেখাত।
    val isTv = remember {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
    val firstTile = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (isTv) runCatching { firstTile.requestFocus() }
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
                Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                homeItems.chunked(4).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { colIndex, item ->
                            HomeTile(
                                item = item,
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (rowIndex == 0 && colIndex == 0) Modifier.focusRequester(firstTile)
                                        else Modifier,
                                    ),
                            ) {
                                if (item.route == null) showExitDialog = true
                                else onOpen(item.route)
                            }
                        }
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

@Composable
private fun HomeTile(item: HomeItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val tint = if (focused) Accent else TextPrimary

    Column(
        modifier
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            item.label,
            color = tint,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
