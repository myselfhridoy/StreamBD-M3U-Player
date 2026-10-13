package com.streambd.iptv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.streambd.iptv.ui.theme.*

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    cancelLabel: String = "CANCEL",
    confirmLabel: String,
    confirmIsDangerous: Boolean = true,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Panel)
                .padding(20.dp),
        ) {
            Text(title, color = TextPrimary, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Text(body, color = TextMuted, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text(cancelLabel, color = TextMuted) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onConfirm) {
                    Text(confirmLabel, color = if (confirmIsDangerous) Danger else Accent)
                }
            }
        }
    }
}
