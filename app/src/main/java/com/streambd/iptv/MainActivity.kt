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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.streambd.iptv.ui.theme.*
import com.streambd.iptv.util.LaunchState
import dagger.hilt.android.AndroidEntryPoint
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // শুধু নতুন চালুতে (ঘোরানো/প্রসেস পুনরুদ্ধারে নয়)
        if (savedInstanceState == null) {
            launchState.bootLaunch = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true
        }
        // ভলিউম বাটন সবসময় মিডিয়া ভলিউম বদলাবে (সিস্টেম নিজেই ভলিউম UI দেখায়)
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
                Box(Modifier.fillMaxSize()) {
                    AppNav()
                    // লোডিং ব্যানারটি AppNav-এর ওপরে ডান কোণে ভেসে থাকবে
                    UpdatingBanner(autoRefresher)
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
        // অ্যাক্টিভিটি থামলে (স্ক্রিনসেভার/হোমে গেলে) ফ্ল্যাগ ক্লিয়ার করে দিন
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

// নতুন কম্পোনেন্ট: প্লেলিস্ট আপডেটিং ব্যানার (ডান কোণে)
@Composable
private fun UpdatingBanner(autoRefresher: AutoRefresher) {
    val isRefreshing by autoRefresher.isRefreshing.collectAsStateWithLifecycle()
    
    AnimatedVisibility(
        visible = isRefreshing,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        // সিস্টেম বার (নচ/স্ট্যাটাস বার) থেকে দূরে রাখতে safeDrawing প্যাডিং
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            contentAlignment = Alignment.TopEnd // উপরের ডান কোণে
        ) {
            Column(
                Modifier
                    .background(Panel.copy(alpha = 0.95f), RoundedCornerShape(8.dp))
                    .border(1.dp, Divider, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Playlist Updating...",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .width(140.dp)
                        .height(3.dp),
                    color = Accent,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
            }
        }
    }
}
