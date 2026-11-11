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

        if (!isStalkerPhpHls(uri)) {
            return delegate.createMediaSource(mediaItem)
        }

        return HlsMediaSource.Factory(dataSourceFactory)
            .setPlaylistParserFactory(RedirectSafeHlsParserFactory(uri))
            .createMediaSource(mediaItem)
    }

    /**
     * Strict Stalker Portal detection — "same to same" match only.
     *
     * Intercept হয় যদি এবং কেবল যদি **এই চারটাই একসাথে** সত্যি হয়:
     *   1. path ".php"-এ শেষ (case-insensitive)
     *   2. query-এ `mac` param + non-blank value
     *   3. query-এ `stream` param + non-blank value
     *   4. query-এ `extension` param + value exactly "m3u8"
     *
     * এছাড়া সব URLs — plain .m3u8, .mpd, .ts, .mp4, generic .php,
     * `.php?mac=X&stream=Y` (extension ছাড়া), `.php?foo=bar` — সব
     * pass-through, DefaultMediaSourceFactory-ই হ্যান্ডেল করবে।
     */
    private fun isStalkerPhpHls(uri: Uri): Boolean {
        val path = (uri.path ?: "").lowercase()
        if (!path.endsWith(".php")) return false

        val names = uri.queryParameterNames
        if (names.isEmpty()) return false

        var mac: String? = null
        var stream: String? = null
        var extensionValue: String? = null

        for (name in names) {
            val value = uri.getQueryParameter(name) ?: continue
            when {
                name.equals("mac", ignoreCase = true) && mac == null -> mac = value
                name.equals("stream", ignoreCase = true) && stream == null -> stream = value
                name.equals("extension", ignoreCase = true) && extensionValue == null -> extensionValue = value
            }
        }

        // null-safe access — এখানে সব params capture, now safe-cast
        val m = mac ?: return false
        val s = stream ?: return false
        val e = extensionValue ?: return false

        return m.isNotBlank() &&
            s.isNotBlank() &&
            e.trim().equals("m3u8", ignoreCase = true)
    }
}
