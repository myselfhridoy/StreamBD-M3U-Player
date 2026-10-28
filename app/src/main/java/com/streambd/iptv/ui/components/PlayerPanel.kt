package com.streambd.iptv.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streambd.iptv.ui.theme.*

/** ট্যাপ খেয়ে ফেলে, যাতে প্যানেলের ফাঁকা জায়গায় ট্যাপে তালিকা বন্ধ না হয়। */
fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

data class PanelTab(val id: Long?, val label: String)

/**
 * LiveScreen-এর ধাঁচ: বাম অর্ধেকে হালকা গ্রেডিয়েন্ট ও তালিকা, উপরে পুরো প্রস্থে সলিড ট্যাব বার,
 * ডান অর্ধেকে ভিডিও দেখা যায়। নচের জায়গাতেও ব্যাকগ্রাউন্ড ঢোকে।
 */
@Composable
fun PanelScaffold(
    tabs: List<PanelTab>,
    selectedTab: Long?,
    onSelectTab: (Long?) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.35f)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.4f)),
                    ),
                ),
        )
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().background(Color(0xFF101010))) {
                PanelTabs(tabs, selectedTab, onSelectTab)
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(0.5f).fillMaxHeight().consumeTaps(), content = content)
                Spacer(Modifier.weight(0.5f))
            }
        }
    }
}

@Composable
private fun PanelTabs(tabs: List<PanelTab>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .consumeTaps()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        items(tabs, key = { it.id ?: Long.MIN_VALUE }) { tab ->
            PanelTabItem(tab.label, selectedId == tab.id) { onSelect(tab.id) }
        }
    }
}

@Composable
private fun PanelTabItem(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = when {
        selected -> Accent
        focused -> TextPrimary
        else -> TextMuted
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .height(2.dp)
                .width(if (selected) 24.dp else 0.dp)
                .background(if (selected) Accent else Color.Transparent),
        )
    }
}

/** তালিকার এক সারি: ঐচ্ছিক নম্বর, লোগো, নাম, উপশিরোনাম, লক/ফেভারিট চিহ্ন। */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PanelRow(
    title: String,
    modifier: Modifier = Modifier,
    number: String? = null,
    logoUrl: String? = null,
    subtitle: String? = null,
    playing: Boolean = false,
    locked: Boolean = false,
    favorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)

    // focused অথবা playing—যেকোনোটি সত্য হলে হাইলাইট (বর্ডার) দেখাবে
    val isHighlighted = focused || playing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (focused) Color.White.copy(alpha = 0.15f) 
                else if (playing) Accent.copy(alpha = 0.1f) 
                else Color.Transparent
            )
            .border(1.dp, if (isHighlighted) Accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { handler ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        handler()
                    }
                },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text(number, color = TextMuted, fontSize = 13.sp, modifier = Modifier.width(28.dp))
        }
        AsyncImage(
            model = logoUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (playing) Accent else TextPrimary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (locked) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = Accent, modifier = Modifier.size(14.dp))
        }
        if (favorite) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Favorite, contentDescription = "Favorite", tint = Accent, modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * যেকোনো তালিকার জন্য: আগে স্ক্রল, তারপর ফোকাস। ফোকাস শুধু প্রথমবার নেয় (ট্যাবে ঘোরার সময় নয়)।
 * [resetKey] বদলালে (গ্রুপ বদল ইত্যাদি) [startIndex]-এ স্ক্রল হয়।
 */
@Composable
fun <T> PanelList(
    items: List<T>,
    key: (T) -> Any,
    startIndex: Int,
    resetKey: Any?,
    emptyText: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    row: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
            } else {
                Text(emptyText, color = TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val start = startIndex.coerceIn(0, items.size - 1)
    val startFocus = remember(resetKey) { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(resetKey) {
        listState.scrollToItem((start - 2).coerceAtLeast(0))
        if (!focusedOnce) {
            focusedOnce = true
            withFrameNanos { }
            runCatching { startFocus.requestFocus() }
        }
    }

    LazyColumn(
        modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(items.size, key = { key(items[it]) }) { index ->
            row(items[index], if (index == start) Modifier.focusRequester(startFocus) else Modifier)
        }
    }
}
