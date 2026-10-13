package com.streambd.iptv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.streambd.iptv.player.PlayerManager
import com.streambd.iptv.ui.nav.AppNav
import com.streambd.iptv.ui.theme.StreamBdTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playerManager: PlayerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StreamBdTheme { AppNav() }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { playerManager.adjustVolume(+1); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { playerManager.adjustVolume(-1); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
