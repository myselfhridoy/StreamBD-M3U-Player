package com.streambd.iptv.data.repo

import android.content.Context
import com.streambd.iptv.data.epg.EpgRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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

/** ইউজারকে সরাসরি দেখানোর মতো বার্তাসহ ব্যর্থতা। */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class SyncResult(
    val channels: Int,
    val programs: Int,
    /** EPG ব্যর্থ হলে বার্তা। প্লেলিস্ট সফল হলেও EPG ব্যর্থ হতে পারে। */
    val epgError: String?,
)

/**
 * প্লেলিস্ট ও EPG ডাউনলোড + ইমপোর্টের এক জায়গা। Splash, Activation ও Settings সবাই এটা ব্যবহার করে।
 */
@Singleton
class PlaylistSync @Inject constructor(
    @ApplicationContext context: Context,
    private val http: OkHttpClient,
    private val channels: ChannelRepository,
    private val epg: EpgRepository,
) {
    private companion object {
        const val KEY_EPG_OK = "epg_last_success"
        const val KEY_EPG_TRY = "epg_last_attempt"
        const val EPG_MAX_AGE_MS = 12L * 60 * 60 * 1000
        const val EPG_RETRY_GAP_MS = 60L * 60 * 1000
    }

    private val prefs = context.getSharedPreferences("streambd_sync", Context.MODE_PRIVATE)

    /** প্লেলিস্ট নামিয়ে যাচাই করে ইমপোর্ট করে। সফল হলে চ্যানেল সংখ্যা। */
    suspend fun syncPlaylist(url: String): Int {
        val body = try {
            download(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: Exception) {
            throw SyncException(friendly(e), e)
        }

        if (body.isBlank()) throw SyncException("The playlist is empty.")
        if (body.firstOrNull { !it.isWhitespace() } == '<') {
            throw SyncException("This link returned a web page, not an M3U playlist.")
        }

        val count = try {
            channels.import(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncException("Could not save the playlist on this device.", e)
        }
        if (count == 0) {
            throw SyncException("No channels found. Check that the link is an M3U playlist.")
        }
        return count
    }

    /** EPG নামিয়ে ইমপোর্ট করে। সফল হলে প্রোগ্রাম সংখ্যা। */
    suspend fun syncEpg(url: String): Int {
        prefs.edit().putLong(KEY_EPG_TRY, System.currentTimeMillis()).apply()

        val ids = channels.tvgIds()
        if (ids.isEmpty()) {
            throw SyncException(
                "Your playlist has no tvg-id values, so the guide can't be matched to channels.",
            )
        }

        val count = try {
            epg.importFromUrl(url, ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncException(friendly(e), e)
        }
        if (count == 0) {
            throw SyncException(
                "The guide was downloaded, but no programmes matched your channels. " +
                    "Check that the playlist's tvg-id values match the guide.",
            )
        }
        prefs.edit().putLong(KEY_EPG_OK, System.currentTimeMillis()).apply()
        return count
    }

    /** Settings-এর "Refresh": প্লেলিস্ট ব্যর্থ হলে exception, EPG ব্যর্থ হলে ফলাফলে বার্তা। */
    suspend fun syncAll(playlistUrl: String, epgUrl: String): SyncResult {
        val channelCount = syncPlaylist(playlistUrl)
        if (epgUrl.isBlank()) return SyncResult(channelCount, 0, null)
        return try {
            SyncResult(channelCount, syncEpg(epgUrl), null)
        } catch (e: SyncException) {
            SyncResult(channelCount, 0, e.message)
        }
    }

    /**
     * Splash-এ EPG দরকার কিনা: শেষ সফল ডাউনলোড ১২ ঘণ্টার বেশি পুরনো (বা কখনও হয়নি)
     * এবং শেষ চেষ্টা ১ ঘণ্টার বেশি আগের।
     */
    fun isEpgDue(now: Long = System.currentTimeMillis()): Boolean {
        val lastOk = prefs.getLong(KEY_EPG_OK, 0L)
        val lastTry = prefs.getLong(KEY_EPG_TRY, 0L)
        return now - lastOk > EPG_MAX_AGE_MS && now - lastTry > EPG_RETRY_GAP_MS
    }

    /** Reset Application-এর সময় ডাকতে হবে। */
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
