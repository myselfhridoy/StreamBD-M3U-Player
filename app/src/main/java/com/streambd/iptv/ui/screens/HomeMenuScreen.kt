package com.streambd.iptv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streambd.iptv.ui.components.ConfirmDialog
import com.streambd.iptv.ui.nav.Routes
import com.streambd.iptv.ui.theme.*
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private data class HomeItem(val label: String, val icon: ImageVector, val route: String?)

private val homeItems = listOf(
    HomeItem("All",       Icons.Filled.Apps,        Routes.LIVE),
    HomeItem("Live",      Icons.Filled.LiveTv,      Routes.LIVE),
    HomeItem("Movies",    Icons.Filled.Movie,       Routes.MOVIES),
    HomeItem("Series",    Icons.Filled.VideoLibrary,Routes.SERIES),
    HomeItem("TV Guide",  Icons.Filled.DateRange,   Routes.GUIDE),
    HomeItem("Search",    Icons.Filled.Search,      Routes.SEARCH),
    HomeItem("Settings",  Icons.Filled.Settings,    Routes.SETTINGS),
    HomeItem("Exit",      Icons.Filled.ExitToApp,   null),
)

@Composable
fun HomeMenuScreen(onOpen: (String) -> Unit, onExit: () -> Unit) {
    var showExitDialog by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { now = LocalTime.now(); delay(1000L) }
    }
    val clock = now.format(DateTimeFormatter.ofPattern("h:mm a"))

    Box(Modifier.fillMaxSize().background(BgDark)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 32.dp)) {
            Text(clock, color = TextPrimary, fontSize = 72.sp)
            Spacer(Modifier.height(32.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(homeItems) { item ->
                    HomeTile(item) {
                        if (item.route == null) showExitDialog = true
                        else onOpen(item.route)
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
private fun HomeTile(item: HomeItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) Accent else Divider
    val tint        = if (focused) Accent else TextPrimary

    Box(
        Modifier
            .aspectRatio(1.4f)
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(10.dp))
            Text(item.label, color = tint, fontSize = 14.sp)
        }
    }
}
