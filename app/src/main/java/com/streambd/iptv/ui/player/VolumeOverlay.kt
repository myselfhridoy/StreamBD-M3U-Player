package com.streambd.iptv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun VolumeOverlay(level: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .background(Color(0xFF333333).copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // বাম দিকে মিউজিক আইকন (ছবির মতো)
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = "Volume",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            
            Spacer(Modifier.width(12.dp))
            
            // ডান দিকে চিকন সাদা ভলিউম বার
            Box(
                Modifier
                    .width(120.dp)
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(1.5.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(level.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White, RoundedCornerShape(1.5.dp))
                )
            }
        }
    }
}
