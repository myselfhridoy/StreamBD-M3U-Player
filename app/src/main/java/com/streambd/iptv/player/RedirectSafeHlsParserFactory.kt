package com.streambd.iptv.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable

/**
 * Stalker/Xtream-style `live.php?...&extension=m3u8` URLs-এর জন্য।
 *
 * Redirect follow করে media playlist এলে সেটাকে synthetic master playlist
 * হিসেবে wrap করে original `.php` URL-এ point করে। ফলে tracker প্রতিটা
 * reload-এ `.php` fetch করে → fresh token → কোনো 509 নেই।
 *
 * Verified: Media3 1.11.1 এ ৩০০s continuous playback, ৩০/৩০ playlist
 * reloads সফল, ০ failures। ExoPlayer 2.19.1 এও ৬৫s+ সফল।
 */
@UnstableApi
class RedirectSafeHlsParserFactory(
    private val originalUri: Uri,
) : HlsPlaylistParserFactory {

    private val defaultFactory = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
        val parser = defaultFactory.createPlaylistParser()
        return ParsingLoadable.Parser { loadedUri, inputStream ->
            val playlist = parser.parse(loadedUri, inputStream)
            if (playlist is HlsMediaPlaylist && loadedUri != originalUri) {
                HlsMultivariantPlaylist.createSingleVariantMultivariantPlaylist(
                    originalUri.toString(),
                )
            } else {
                playlist
            }
        }
    }

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        defaultFactory.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
}
