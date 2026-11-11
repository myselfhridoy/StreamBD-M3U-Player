package com.streambd.iptv.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Dynamic HLS URLs (`.php?extension=m3u8`, `.jsp?extension=m3u8` ইত্যাদি)
 * detect করে explicit `HlsMediaSource.Factory` + `RedirectSafeHlsParserFactory`
 * দিয়ে route করে। বাকি সব URL default path-এ (DASH/ClearKey/progressive
 * অপরিবর্তিত)।
 */
@UnstableApi
class RedirectAwareMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
    drmProvider: DrmSessionManagerProvider,
) : MediaSource.Factory {

    private val delegate = DefaultMediaSourceFactory(dataSourceFactory)
        .setDrmSessionManagerProvider(drmProvider)

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
            ?: return delegate.createMediaSource(mediaItem)

        if (!isDynamicHls(uri)) {
            // Plain .m3u8, .mpd, .ts, .mp4 — default ExoPlayer behavior unchanged
            return delegate.createMediaSource(mediaItem)
        }

        // Stalker/dynamic HLS: force HlsMediaSource with redirect-safe parser
        return HlsMediaSource.Factory(dataSourceFactory)
            .setPlaylistParserFactory(RedirectSafeHlsParserFactory(uri))
            .createMediaSource(mediaItem)
    }

    /**
     * `extension=m3u8` / `extension=m3u` / `extension=hls` query param থাকলে
     * dynamic HLS ধরা হয়। Plain `.m3u8` URLs এই check-এ পড়ে না।
     */
    private fun isDynamicHls(uri: Uri): Boolean {
        val url = uri.toString().lowercase()
        return url.contains("extension=m3u8") ||
            url.contains("extension=m3u") ||
            url.contains("extension=hls")
    }
}
