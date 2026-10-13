package com.streambd.iptv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streambd.iptv.ui.theme.Accent
import com.streambd.iptv.ui.theme.BgDark
import com.streambd.iptv.ui.theme.TextPrimary

@Composable
fun PlaceholderScreen(title: String, onBack: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(BgDark),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = TextPrimary, fontSize = 32.sp)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onBack) { Text("Back", color = Color.Black) }
        }
    }
}
