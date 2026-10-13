package com.streambd.iptv.ui.screens.activation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.streambd.iptv.ui.theme.Accent
import com.streambd.iptv.ui.theme.Panel
import com.streambd.iptv.ui.theme.TextMuted
import com.streambd.iptv.ui.theme.TextPrimary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivationSuccessDialog(code: String, expiresAt: Long, onConfirm: () -> Unit) {
    val expiry = if (expiresAt <= 0L) "Never" else {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(expiresAt))
    }

    Dialog(onDismissRequest = {}) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 360.dp)
                .clip(RoundedCornerShape(8.dp)).background(Panel).padding(20.dp),
        ) {
            Text("Activation successful", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("Code", color = TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(code, color = TextPrimary, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("Expires", color = TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(expiry, color = TextPrimary, fontSize = 14.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onConfirm) { Text("CONFIRM", color = Accent) }
            }
        }
    }
}