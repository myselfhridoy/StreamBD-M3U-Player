package com.streambd.iptv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.streambd.iptv.player.ChannelJumpController
import com.streambd.iptv.player.PlayerManager
import com.streambd.iptv.ui.nav.AppNav
import com.streambd.iptv.ui.theme.StreamBdTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playerManager: PlayerManager
    @Inject lateinit var jumpController: ChannelJumpController

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
