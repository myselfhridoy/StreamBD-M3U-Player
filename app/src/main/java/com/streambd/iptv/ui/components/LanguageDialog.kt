package com.streambd.iptv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streambd.iptv.ui.theme.*

/** ভাষা বাছাইয়ের তালিকা। [options] = (কোড, নাম)। বর্তমান পছন্দে ফোকাস দিয়ে খোলে। */
@Composable
fun LanguageDialog(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedIndex = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    val startFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        listState.scrollToItem(selectedIndex)
        runCatching { startFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(340.dp)
                    .heightIn(max = 380.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Panel)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Text(
                    title,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                )
                LazyColumn(
                    Modifier
                        .weight(1f, fill = false)
                        .padding(bottom = 8.dp),
                    state = listState,
                ) {
                    itemsIndexed(options) { index, option ->
                        LangRow(
                            label = option.second,
                            selected = option.first == current,
                            modifier = if (index == selectedIndex) Modifier.focusRequester(startFocus) else Modifier,
                        ) { onPick(option.first) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LangRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .background(if (focused) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .border(2.dp, if (selected) Accent else TextMuted, CircleShape)
                .padding(3.dp)
                .background(if (selected) Accent else Color.Transparent, CircleShape),
        )
        Spacer(Modifier.width(14.dp))
        Text(label, color = TextPrimary, fontSize = 15.sp)
    }
}
