package com.streambd.iptv

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.streambd.iptv.data.repo.AutoRefresher
import com.streambd.iptv.player.ChannelJumpController
import com.streambd.iptv.player.PlayerManager
import com.streambd.iptv.ui.nav.AppNav
import com.streambd.iptv.ui.theme.StreamBdTheme
import com.streambd.iptv.util.LaunchState
import dagger.hilt.android.AndroidEntryPoint
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
        setContent {
            StreamBdTheme { AppNav() }
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
