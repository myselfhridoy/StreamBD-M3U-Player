package com.streambd.iptv.data.repo

import android.content.Context
import android.util.Log
import com.streambd.iptv.data.activation.PlaylistData
import com.streambd.iptv.data.epg.EpgRepository
import com.streambd.iptv.data.m3u.ParsedChannel
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
        const val MAX_NESTED_DEPTH = 3
    }

    private val prefs = context.getSharedPreferences("streambd_sync", Context.MODE_PRIVATE)
    private val playlistLock = Mutex()

    /** 
     * ডাউনলোড ও পার্স করার হেল্পার ফাংশন (ডেটাবেসে সেভ করে না, শুধু মেমোরিতে দেয়)
     */
    private suspend fun fetchPlaylistData(rootUrl: String): Pair<List<ParsedChannel>, List<String>> {
        val allChannels = mutableListOf<ParsedChannel>()
        val allEpgUrls = mutableSetOf<String>()

        suspend fun fetchRecursive(targetUrl: String, depth: Int) {
            if (depth > MAX_NESTED_DEPTH) return
            val body = try {
                download(targetUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (depth == 0) throw SyncException(friendly(e), e)
                else { Log.w(TAG, "Failed nested playlist $targetUrl: ${e.message}"); return }
            }

            if (body.isBlank() || body.firstOrNull { !it.isWhitespace() } == '<') return
            val parsed = PlaylistParserFactory.parse(body)
            allEpgUrls.addAll(parsed.epgUrls)
            for (channel in parsed.channels) {
                if (channel.isNestedPlaylist) fetchRecursive(channel.streamUrl, depth + 1)
                else allChannels.add(channel)
            }
        }
        fetchRecursive(rootUrl, 0)
        return Pair(allChannels, allEpgUrls.toList())
    }

    /** 
     * একক প্লেলিস্ট সিঙ্ক (যদি কখনো আলাদাভাবে ডাকার দরকার হয়)
     */
    suspend fun syncPlaylist(url: String): SyncedPlaylist = playlistLock.withLock {
        val (channelsList, epgs) = fetchPlaylistData(url)
        if (channelsList.isEmpty()) throw SyncException("No channels found.")
        val count = channels.import(channelsList)
        prefs.edit().putLong(KEY_PLAYLIST_OK, System.currentTimeMillis()).apply()
        SyncedPlaylist(count, epgs)
    }

    /** 
     * নতুন: একসাথে একাধিক প্লেলিস্ট সিঙ্ক 
     */
    suspend fun syncAll(playlists: List<PlaylistData>, preferDiscovered: Boolean): SyncResult = playlistLock.withLock {
        if (playlists.isEmpty()) throw SyncException("No playlists to sync.")

        val allChannels = mutableListOf<ParsedChannel>()
        val allDiscoveredEpgs = mutableSetOf<String>()
        val allExplicitEpgs = mutableSetOf<String>()

        // ১. সব প্লেলিস্ট ডাউনলোড ও পার্স করা (ডেট demandbase clear হবে না)
        for (p in playlists) {
            try {
                val (chList, epgs) = fetchPlaylistData(p.m3uUrl)
                allChannels.addAll(chList)
                allDiscoveredEpgs.addAll(epgs)
                if (p.epgUrl.isNotBlank()) allExplicitEpgs.add(p.epgUrl)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync playlist ${p.name}: ${e.message}")
                // যদি সব প্লেলিস্ট ফেইল করে, তবে নিচে allChannels.isEmpty() চেক হবে
            }
        }

        if (allChannels.isEmpty()) {
            throw SyncException("No channels found in any of the assigned playlists.")
        }

        // ২. সব চ্যানেল একবারে ডেটাবেসে সেভ করা
        val count = try {
            channels.import(allChannels)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncException("Could not save the playlists on this device.", e)
        }

        prefs.edit().putLong(KEY_PLAYLIST_OK, System.currentTimeMillis()).apply()

        // ৩. EPG সিঙ্ক করা
        val finalEpgs = if (preferDiscovered || allExplicitEpgs.isEmpty()) {
            allExplicitEpgs + allDiscoveredEpgs
        } else {
            allExplicitEpgs
        }
        val effectiveEpgStr = finalEpgs.joinToString("\n")
        
        if (effectiveEpgStr.isBlank()) {
            return SyncResult(count, 0, null, "")
        }

        return try {
            SyncResult(count, syncEpg(effectiveEpgStr), null, effectiveEpgStr)
        } catch (e: SyncException) {
            SyncResult(count, 0, e.message, effectiveEpgStr)
        }
    }

    fun isPlaylistDue(now: Long = System.currentTimeMillis(), maxAgeMs: Long = PLAYLIST_MAX_AGE_MS): Boolean =
        now - prefs.getLong(KEY_PLAYLIST_OK, 0L) > maxAgeMs

    fun lastPlaylistUpdateTime(): Long = prefs.getLong(KEY_PLAYLIST_OK, 0L)

    suspend fun syncEpg(urls: String): Int {
        prefs.edit().putLong(KEY_EPG_TRY, System.currentTimeMillis()).apply()
        val ids = channels.tvgIds()
        if (ids.isEmpty()) throw SyncException("Your playlist has no tvg-id values.")

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

        if (total == 0) throw firstError ?: SyncException("The guide was downloaded, but no programmes matched.")
        prefs.edit().putLong(KEY_EPG_OK, System.currentTimeMillis()).apply()
        return total
    }

    fun isEpgDue(now: Long = System.currentTimeMillis()): Boolean {
        val lastOk = prefs.getLong(KEY_EPG_OK, 0L)
        val lastTry = prefs.getLong(KEY_EPG_TRY, 0L)
        return now - lastOk > EPG_MAX_AGE_MS && now - lastTry > EPG_RETRY_GAP_MS
    }

    fun clearState() { prefs.edit().clear().apply() }

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
        is SSLException -> "Secure connection failed. Try an http:// link, or check the date and time on this device."
        is IOException -> "Network error. Please try again."
        is IllegalArgumentException -> "That link doesn't look valid."
        is XmlPullParserException -> "The guide file is not valid XMLTV."
        else -> e.message ?: "Something went wrong."
    }
}
