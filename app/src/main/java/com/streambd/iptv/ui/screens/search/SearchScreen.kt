package com.streambd.iptv.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WorkspacePremium
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.ui.theme.*

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onPickLive: (ChannelEntity) -> Unit,
    onPlayVod: (ChannelEntity) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgDark)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        // TopBar বাদ দেওয়া হয়েছে, সরাসরি কন্টেন্ট শুরু
        Column(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth()
                    .statusBarsPadding() // স্ট্যাটাস বারের নিচে যাওয়া আটকাতে
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Surface1)
                    .border(2.dp, Accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Type channel name here…", color = TextMuted, fontSize = 16.sp)
                        BasicTextField(
                            value = query,
                            onValueChange = vm::setQuery,
                            singleLine = true,
                            cursorBrush = SolidColor(Accent),
                            textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                        )
                    }
                }
            }

            when {
                query.isBlank() -> SearchMessage(
                    "Start typing to search all content",
                    Modifier.weight(1f).fillMaxWidth(),
                )
                results.isEmpty() -> SearchMessage(
                    "No results match ‘$query’",
                    Modifier.weight(1f).fillMaxWidth(),
                )
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(results, key = { it.id }) { channel ->
                        SearchResultRow(channel) {
                            if (channel.kind == "LIVE") onPickLive(channel) else onPlayVod(channel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchMessage(message: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(message, color = TextMuted, fontSize = 14.sp)
    }
}

@Composable
private fun SearchResultRow(channel: ChannelEntity, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp)).background(if (focused) Panel else Color.Transparent)
            .border(2.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("%02d".format(channel.number), color = TextMuted, fontSize = 16.sp, modifier = Modifier.width(36.dp))
        
        // নতুন: লোগোর সাথে প্রিমিয়াম আইকন যুক্ত করা হলো
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
            )
            if (channel.isPremium) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .background(Color.Black, CircleShape)
                        .padding(1.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.WorkspacePremium,
                        contentDescription = "Premium",
                        tint = Color(0xFFFFD700), // Gold Color
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        
        Spacer(Modifier.width(12.dp))
        Text(
            channel.name,
            color = TextPrimary,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        val kindLabel = when (channel.kind) {
            "MOVIE" -> "MOVIE"
            "SERIES" -> "SERIES"
            else -> "LIVE"
        }
        val kindColor = when (channel.kind) {
            "MOVIE" -> Danger
            "SERIES" -> Color(0xFF30A46C)
            else -> Accent
        }
        Text(
            text = kindLabel,
            color = Color.Black,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .background(kindColor)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
