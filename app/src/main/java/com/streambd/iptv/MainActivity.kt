package com.streambd.iptv

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.streambd.iptv.data.repo.AutoRefresher
import com.streambd.iptv.player.ChannelJumpController
import com.streambd.iptv.player.PlayerManager
import com.streambd.iptv.ui.nav.AppNav
import com.streambd.iptv.ui.player.VolumeOverlay
import com.streambd.iptv.ui.theme.*
import com.streambd.iptv.util.LaunchState
import com.streambd.iptv.util.VolumeController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FROM_BOOT = "com.streambd.iptv.FROM_BOOT"
    }

    @Inject lateinit var playerManager: PlayerManager
    @Inject lateinit var jumpController: ChannelJumpController
    @Inject lateinit var launchState: LaunchState
    @Inject lateinit var autoRefresher: AutoRefresher
    @Inject lateinit var volumeController: VolumeController // নতুন ইনজেক্ট

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // শুধু নতুন চালুতে (ঘোরানো/প্রসেস পুনরুদ্ধারে নয়)
        if (savedInstanceState == null) {
            launchState.bootLaunch = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true
        }
        
        volumeControlStream = AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        hideSystemBars()

        // কিছু চলছে বা লোড হচ্ছে (আবার চেষ্টা চলাও ধরে) থাকলে স্ক্রিন জেগে থাকবে।
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerManager.state
                    .map { it.isPlaying || it.isBuffering }
                    .distinctUntilChanged()
                    .collect { active ->
                        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
            }
        }

        setContent {
            StreamBdTheme {
                val volumeState by volumeController.state.collectAsStateWithLifecycle()

                // ভলিউম বারটি দেখানোর ২ সেকেন্ড পর অটো-হাইড হয়ে যাবে
                LaunchedEffect(volumeState.isVisible, volumeState.level) {
                    if (volumeState.isVisible) {
                        delay(2_000L)
                        volumeController.hideOverlay()
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    AppNav()
                    
                    // প্লেলিস্ট আপডেটিং ব্যানার
                    UpdatingBanner(autoRefresher)
                    
                    // কাস্টম ভলিউম ওভারলে
                    AnimatedVisibility(
                        visible = volumeState.isVisible,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        VolumeOverlay(level = volumeState.level)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FROM_BOOT, false)) launchState.bootLaunch = true
    }

    override fun onStart() {
        super.onStart()
        autoRefresher.start()
    }

    override fun onStop() {
        super.onStop()
        playerManager.pause()
        autoRefresher.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ডায়ালগ/কিবোর্ড বন্ধ হয়ে ফোকাস ফিরলে বার আবার লুকানো
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            // নতুন: ভলিউম আপ/ডাউন বাটন কাস্টম কন্ট্রোলারের কাছে পাঠানো হচ্ছে
            KeyEvent.KEYCODE_VOLUME_UP -> {
                volumeController.increaseVolume()
                true // true রিটার্ন করায় সিস্টেম আর নিজের ভলিউম প্যানেল দেখাবে না
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volumeController.decreaseVolume()
                true
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (!jumpController.isEnabled) return super.onKeyDown(keyCode, event)
                jumpController.onDigit('0' + (keyCode - KeyEvent.KEYCODE_0))
                true
            }
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                if (!jumpController.isEnabled) return super.onKeyDown(keyCode, event)
                jumpController.onDigit('0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0))
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

// প্লেলিস্ট আপডেটিং ব্যানার (ব্যাকগ্রাউন্ড ছাড়া)
@Composable
private fun UpdatingBanner(autoRefresher: AutoRefresher) {
    val isRefreshing by autoRefresher.isRefreshing.collectAsStateWithLifecycle()
    
    AnimatedVisibility(
        visible = isRefreshing,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    "Playlist Updating...",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color.Black,
                            offset = Offset(1f, 1f),
                            blurRadius = 4f
                        )
                    )
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .width(140.dp)
                        .height(3.dp),
                    color = Accent,
                    trackColor = Color.White.copy(alpha = 0.3f)
                )
            }
        }
    }
}
