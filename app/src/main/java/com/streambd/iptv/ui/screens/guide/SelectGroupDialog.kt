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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.streambd.iptv.data.db.GroupEntity
import com.streambd.iptv.ui.theme.Accent
import com.streambd.iptv.ui.theme.Panel
import com.streambd.iptv.ui.theme.TextMuted
import com.streambd.iptv.ui.theme.TextPrimary

@Composable
fun SelectGroupDialog(
    groups: List<GroupEntity>,
    current: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 340.dp)
                .clip(RoundedCornerShape(8.dp)).background(Panel).padding(16.dp),
        ) {
            Text("Select Group", color = TextPrimary, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item { OptionRow("Favorites", current == GROUP_FAVORITES) { onPick(GROUP_FAVORITES) } }
                item { OptionRow("All Channels", current == GROUP_ALL) { onPick(GROUP_ALL) } }
                items(groups, key = { it.id }) { group ->
                    OptionRow(group.name, current == group.id) { onPick(group.id) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("CANCEL", color = TextMuted) }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = TextMuted),
        )
        Text(label, color = TextPrimary, fontSize = 15.sp)
    }
}