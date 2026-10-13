package com.streambd.iptv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.streambd.iptv.player.AspectMode
import com.streambd.iptv.ui.theme.*

@Composable
fun AspectRatioDialog(
    current: AspectMode,
    onPick: (AspectMode) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(400.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Panel)
                .padding(20.dp),
        ) {
            Text("Select Aspect Ratio", color = TextPrimary, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            AspectMode.values().forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(mode) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = mode == current,
                        onClick = { onPick(mode) },
                        colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = TextMuted),
                    )
                    Column(Modifier.padding(start = 6.dp)) {
                        Text(mode.label, color = TextPrimary, fontSize = 15.sp)
                        Text(mode.description, color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("CANCEL", color = TextMuted) }
            }
        }
    }
}
