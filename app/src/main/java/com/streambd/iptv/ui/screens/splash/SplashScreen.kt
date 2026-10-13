package com.streambd.iptv.ui.screens.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambd.iptv.ui.theme.Accent
import com.streambd.iptv.ui.theme.BgDark
import com.streambd.iptv.ui.theme.TextPrimary

@Composable
fun SplashScreen(
    onToActivation: () -> Unit,
    onToHome: () -> Unit,
    vm: SplashViewModel = hiltViewModel(),
) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    LaunchedEffect(nav) {
        when (nav) {
            SplashNav.ToActivation -> onToActivation()
            SplashNav.ToHome -> onToHome()
            null -> Unit
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0B0B0B), BgDark)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("STREAMBD", color = Accent, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text("IPTV", color = TextPrimary, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(40.dp))
            CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text(status, color = TextPrimary, fontSize = 15.sp)
        }
    }
}