package com.streambd.iptv.ui.screens.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streambd.iptv.BuildConfig
import com.streambd.iptv.ui.theme.Accent
import com.streambd.iptv.ui.theme.BgDark
import com.streambd.iptv.ui.theme.Surface1
import com.streambd.iptv.ui.theme.TextMuted
import com.streambd.iptv.ui.theme.TextPrimary

@Composable
fun SplashScreen(
    onToActivation: () -> Unit,
    onToHome: () -> Unit,
    onToLive: (Long) -> Unit,
    vm: SplashViewModel = hiltViewModel(),
) {
    val nav by vm.nav.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val failure by vm.failure.collectAsStateWithLifecycle()

    LaunchedEffect(nav) {
        when (val target = nav) {
            SplashNav.ToActivation -> onToActivation()
            SplashNav.ToHome -> onToHome()
            is SplashNav.ToLive -> onToLive(target.channelId)
            null -> Unit
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0B0B0B), BgDark)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("STREAMBD", color = Accent, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text("IPTV", color = TextPrimary, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(40.dp))

            val problem = failure
            if (problem == null) {
                CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                Text(status, color = TextPrimary, fontSize = 15.sp)
            } else {
                FailureContent(
                    message = problem.message,
                    onRetry = vm::retry,
                    onChange = vm::changePlaylist,
                )
            }
        }
    }
}

@Composable
private fun FailureContent(message: String, onRetry: () -> Unit, onChange: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }

    Column(
        Modifier.widthIn(max = 420.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Couldn't load your channels",
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(message, color = TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SplashButton("Retry", primary = true, onClick = onRetry, modifier = Modifier.focusRequester(retryFocus))
            SplashButton(
                label = if (BuildConfig.ACTIVATION_ENABLED) "Enter activation code" else "Change playlist",
                primary = false,
                onClick = onChange,
            )
        }
    }
}

@Composable
private fun SplashButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Text(
        text = label,
        color = if (primary) Color.Black else TextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(shape)
            .background(if (primary) Accent else Surface1)
            .border(2.dp, if (focused) TextPrimary else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
