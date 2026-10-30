package com.streambd.iptv.data.repo

import android.content.Context
import android.util.Log
import com.streambd.iptv.data.epg.EpgRepository
import com.streambd.iptv.data.parser.PlaylistParserFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class SyncedPlaylist(val channels: Int, val epgUrls: List<String>)

data class SyncResult(
    val channels: Int,
    val programs: Int,
    val epgError: String?,
    val epgUrl: String = "",
)

@Singleton
class PlaylistSync @Inject constructor(
    @ApplicationContext context: Context,
    private val http: OkHttpClient,
    private val channels: ChannelRepository,
    private val epg: EpgRepository,
) {
    private companion object {
        const val TAG = "PlaylistSync"
        const val KEY_EPG_OK = "epg_last_success"
        const val KEY_EPG_TRY = "epg_last_attempt"
        const val EPG_MAX_AGE_MS = 12L * 60 * 60 * 1000
        const val EPG_RETRY_GAP_MS = 60L * 60 * 1000
        const val KEY_PLAYLIST_OK = "playlist_last_success"
        const val PLAYLIST_MAX_AGE_MS = 30L * 60 * 1000
        
        // নতুন: ইনফিনিট লুপ এড়াতে সর্বোচ্চ কত লেভেল পর্যন্ত নেস্টেড প্লেলিস্ট চেক করবে
        const val MAX_NESTED_DEPTH = 3
    }

    private val prefs = context.getSharedPreferences("streambd_sync", Context.MODE_PRIVATE)
    private val playlistLock = Mutex()

    suspend fun syncPlaylist(url: String): SyncedPlaylist = playlistLock.withLock {
        val result = fetchAndImport(url)
        prefs.edit().putLong(KEY_PLAYLIST_OK, System.currentTimeMillis()).apply()
        result
    }

    fun isPlaylistDue(
        now: Long = System.currentTimeMillis(),
        maxAgeMs: Long = PLAYLIST_MAX_AGE_MS,
    ): Boolean = now - prefs.getLong(KEY_PLAYLIST_OK, 0L) > maxAgeMs

    fun lastPlaylistUpdateTime(): Long = prefs.getLong(KEY_PLAYLIST_OK, 0L)

    /** 
     * নতুন: রিকার্সিভ ফাংশন যা মূল প্লেলিস্ট এবং নেস্টেড প্লেলিস্ট থেকে 
     * চ্যানেল এবং EPG লিংকগুলোকে একসাথে মার্জ করে।
     */
    private suspend fun fetchAndImport(url: String): SyncedPlaylist {
        val allChannels = mutableListOf<com.streambd.iptv.data.m3u.ParsedChannel>()
        val allEpgUrls = mutableSetOf<String>()

        suspend fun fetchRecursive(targetUrl: String, depth: Int) {
            if (depth > MAX_NESTED_DEPTH) return

            val body = try {
                download(targetUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // যদি রুট লেভেলে হয় তবে এক্সসেপশন থ্রো করবে, নেস্টেড লেভেলে হলে ইগনোর করবে
                if (depth == 0) throw SyncException(friendly(e), e)
                else {
                    Log.w(TAG, "Failed to download nested playlist $targetUrl: ${e.message}")
                    return
                }
            }

            if (body.isBlank()) return
            if (body.firstOrNull { !it.isWhitespace() } == '<') return

            val parsed = PlaylistParserFactory.parse(body)
            allEpgUrls.addAll(parsed.epgUrls)

            for (channel in parsed.channels) {
                if (channel.isNestedPlaylist) {
                    fetchRecursive(channel.streamUrl, depth + 1)
                } else {
                    allChannels.add(channel)
                }
            }
        }

        // ডাউনলোড শুরু
        fetchRecursive(url, 0)

        if (allChannels.isEmpty()) {
            throw SyncException("No channels found. Check that the link is a valid playlist.")
        }

        // ডেটাবেসে সেভ করা
        val count = try {
            channels.import(allChannels)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncException("Could not save the playlist on this device.", e)
        }

        if (count == 0) {
            throw SyncException("No channels found. Check that the link is a valid playlist.")
        }

        return SyncedPlaylist(count, allEpgUrls.toList())
    }

    fun chooseEpg(stored: String, discovered: List<String>, preferDiscovered: Boolean): String {
        val found = discovered.joinToString("\n")
        return when {
            found.isBlank() -> stored
            preferDiscovered || stored.isBlank() -> found
            else -> stored
        }
    }

    suspend fun syncEpg(urls: String): Int {
        prefs.edit().putLong(KEY_EPG_TRY, System.currentTimeMillis()).apply()
        val ids = channels.tvgIds()
        if (ids.isEmpty()) {
            throw SyncException(
                "Your playlist has no tvg-id values, so the guide can't be matched to channels.",
            )
        }

        val list = urls.split(Regex("\\s+")).filter { it.isNotBlank() }
        var total = 0
        var firstError: SyncException? = null
        for (url in list) {
            try {
                total += epg.importFromUrl(url, ids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = SyncException(friendly(e), e)
            }
        }

        if (total == 0) {
            throw firstError ?: SyncException(
                "The guide was downloaded, but no programmes matched your channels. " +
                    "Check that the guide covers these channels (matched by tvg-id or channel name).",
            )
        }
        prefs.edit().putLong(KEY_EPG_OK, System.currentTimeMillis()).apply()
        return total
    }

    suspend fun syncAll(playlistUrl: String, epgUrl: String, preferDiscovered: Boolean): SyncResult {
        val synced = syncPlaylist(playlistUrl)
        val effective = chooseEpg(epgUrl, synced.epgUrls, preferDiscovered)
        if (effective.isBlank()) return SyncResult(synced.channels, 0, null, "")
        return try {
            SyncResult(synced.channels, syncEpg(effective), null, effective)
        } catch (e: SyncException) {
            SyncResult(synced.channels, 0, e.message, effective)
        }
    }

    fun isEpgDue(now: Long = System.currentTimeMillis()): Boolean {
        val lastOk = prefs.getLong(KEY_EPG_OK, 0L)
        val lastTry = prefs.getLong(KEY_EPG_TRY, 0L)
        return now - lastOk > EPG_MAX_AGE_MS && now - lastTry > EPG_RETRY_GAP_MS
    }

    fun clearState() {
        prefs.edit().clear().apply()
    }

    private suspend fun download(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SyncException("Server returned ${response.code}.")
            response.body?.string().orEmpty()
        }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is UnknownHostException -> "Can't reach the server. Check your internet connection."
        is SocketTimeoutException -> "The server took too long to respond."
        is SSLException ->
            "Secure connection failed. Try an http:// link, or check the date and time on this device."
        is IOException -> "Network error. Please try again."
        is IllegalArgumentException -> "That link doesn't look valid."
        is XmlPullParserException -> "The guide file is not valid XMLTV."
        else -> e.message ?: "Something went wrong."
    }
}
