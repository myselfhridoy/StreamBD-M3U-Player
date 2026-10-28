package com.streambd.iptv.util

import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class VolumeState(
    val level: Float = 0.5f, // 0.0 থেকে 1.0
    val isVisible: Boolean = false
)

@Singleton
class VolumeController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(VolumeState())
    val state: StateFlow<VolumeState> = _state.asStateFlow()

    init {
        updateState(show = false)
    }

    fun increaseVolume() = adjustVolume(AudioManager.ADJUST_RAISE)
    fun decreaseVolume() = adjustVolume(AudioManager.ADJUST_LOWER)

    private fun adjustVolume(direction: Int) {
        // flag 0 দেওয়া হয়েছে, যাতে সিস্টেমের ডিফল্ট ভলিউম UI না আসে
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        updateState(show = true)
    }

    fun hideOverlay() {
        _state.update { it.copy(isVisible = false) }
    }

    private fun updateState(show: Boolean) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val fraction = if (maxVol > 0) currentVol.toFloat() / maxVol.toFloat() else 0f

        _state.update { 
            it.copy(level = fraction, isVisible = show) 
        }
    }
}
