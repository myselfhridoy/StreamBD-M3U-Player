package com.streambd.iptv.ui.screens.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.ui.theme.*

@Composable
fun SelectGroupDialog(
    groups: List<GroupEntity>,
    current: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .width(340.dp)
                    .heightIn(min = 280.dp, max = 340.dp)
                    .background(Panel)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                Text(
                    "Select Group",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp)
                )

                LazyColumn(
                    Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp)
                ) {
                    item {
                        DialogRadioRow("Favorites", current == GROUP_FAVORITES) { onPick(GROUP_FAVORITES) }
                    }
                    item {
                        DialogRadioRow("All Channels", current == GROUP_ALL) { onPick(GROUP_ALL) }
                    }
                    items(groups, key = { it.id }) { group ->
                        DialogRadioRow(group.name, current == group.id) { onPick(group.id) }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DialogTextButton("CANCEL", TextMuted, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun DialogRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (focused) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = TextMuted),
        )
    }
}

@Composable
private fun DialogTextButton(label: String, color: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
