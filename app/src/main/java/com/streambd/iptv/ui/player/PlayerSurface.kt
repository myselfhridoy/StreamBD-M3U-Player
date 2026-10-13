package com.streambd.iptv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streambd.iptv.player.AspectMode

/** Plain video surface, no built-in controls. */
@Composable
fun PlayerSurface(
    player: ExoPlayer,
    aspectMode: AspectMode,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                this.player = player
            }
        },
        update = { view ->
            view.resizeMode = when (aspectMode) {
                AspectMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.STRETCH  -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectMode.ZOOM     -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            if (view.player !== player) view.player = player
        },
        modifier = modifier,
    )
}
