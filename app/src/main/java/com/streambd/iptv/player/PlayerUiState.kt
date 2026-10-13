package com.streambd.iptv.player

import com.streambd.iptv.data.db.ChannelEntity

data class PlayerUiState(
    val current: ChannelEntity? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val isFullscreen: Boolean = false,
    val aspectMode: AspectMode = AspectMode.STRETCH,
    val volumeOverlayVisible: Boolean = false,
    val volumeLevel: Float = 1f,
    val error: String? = null,
    val tracks: TrackInfo = TrackInfo.empty(),
)
