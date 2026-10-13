package com.streambd.iptv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streambd.iptv.ui.theme.Accent
import com.streambd.iptv.ui.theme.Panel
import com.streambd.iptv.ui.theme.TextPrimary

@Composable
fun VolumeOverlay(level: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Panel.copy(alpha = 0.95f))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = Accent)
                Spacer(Modifier.width(12.dp))
                Text("${(level * 100).toInt()}%", color = TextPrimary, fontSize = 15.sp)
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { level },
                color = Accent,
                trackColor = TextPrimary.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
    }
}
