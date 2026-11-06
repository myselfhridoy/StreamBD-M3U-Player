package com.streambd.iptv.data.xtream

import com.streambd.iptv.data.db.ProviderDao
import com.streambd.iptv.data.db.ProviderEntity
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.repo.PlaylistSync
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Xtream sync-এর একক entry point।
 *
 * দায়িত্ব:
 *  - credentials validate করা
 *  - live + movie catalog নামানো
 *  - ChannelRepository-তে import করা
 *  - provider-এ EPG URL সেভ করা
 *  - EPG ডাউনলোড (ঐচ্ছিক)
 *
 * Series v2-তে lazy loading দিয়ে আসবে; নিচে `syncEpg`-এর মতো আলাদা method যোগ করা যাবে।
 */
data class XtreamSyncSummary(
    val channels: Int,
    val live: Int,
    val movies: Int,
    val programs: Int,
    val epgError: String?,
    val epgUrl: String,
    /** true হলে TV guide ব্যাকগ্রাউন্ডে নামছে; চ্যানেল আগেই ব্যবহারযোগ্য। */
    val epgPending: Boolean = false,
) {
    fun message(): String = buildString {
        append("Added $channels channels")
        if (live > 0 || movies > 0) append(" ($live live, $movies movies)")
        when {
            epgError != null -> append(". TV guide: $epgError")
            programs > 0 -> append(", $programs guide entries")
            epgPending -> append(". TV guide is loading in the background")
        }
        append(".")
    }
}

@Singleton
class XtreamRepository @Inject constructor(
    private val api: XtreamApi,
    private val providers: ProviderDao,
    private val channels: ChannelRepository,
    private val playlists: PlaylistSync,
) {

    private val syncLock = Mutex()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** EPG বড় হতে পারে; ব্যবহারকারীকে আটকে না রেখে ব্যাকগ্রাউন্ডে নামায়। ব্যর্থ হলে পরের রিফ্রেশে আবার চেষ্টা হবে। */
    private fun syncEpgInBackground(epgUrl: String) {
        if (epgUrl.isBlank()) return
        bgScope.launch {
            try {
                playlists.syncEpg(epgUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("XtreamRepository", "background EPG failed: ${e.message}")
            }
        }
    }

    /** নতুন Xtream provider যোগ করে; auth fail করলে ডেটাবেসে কিছুই থাকে না। */
    suspend fun add(
        name: String,
        url: String,
        username: String,
        password: String,
        userAgent: String? = null,
    ): XtreamSyncSummary {
        val credentials = credentials(url, username, password, userAgent)

        // Insert করার আগেই যাচাই, যাতে ভুল পাসওয়ার্ডে ভাঙা provider না থাকে
        val auth = api.authenticate(credentials)

        val entity = ProviderEntity(
            name = name.trim(),
            url = credentials.baseUrl,
            type = "XTREAM",
            username = credentials.username,
            password = credentials.password,
            userAgent = credentials.userAgent,
        )
        val id = providers.insert(entity)
        return try {
            sync(entity.copy(id = id), syncEpg = false, knownAuth = auth).let {
                syncEpgInBackground(it.epgUrl)
                it.copy(epgPending = it.epgUrl.isNotBlank())
            }
        } catch (e: Exception) {
            providers.deleteById(id)
            channels.clearProviderData(id)
            throw e
        }
    }

    /**
     * Provider আপডেট। শুধু নাম বদলালে নেটওয়ার্ক ছোঁয় না; credentials/URL বদলালে
     * আগে auth যাচাই করে তারপর sync করে।
     *
     * @return নতুন sync হলে summary; শুধু নাম বদলালে null।
     */
    suspend fun update(
        existing: ProviderEntity,
        name: String,
        url: String,
        username: String,
        password: String,
        userAgent: String? = null,
    ): XtreamSyncSummary? {
        val credentials = credentials(url, username, password, userAgent)

        val credentialsChanged =
            existing.url != credentials.baseUrl ||
                existing.username != credentials.username ||
                existing.password != credentials.password ||
                existing.userAgent != credentials.userAgent

        // শুধু নাম বদলালে অফলাইনেও কাজ করবে; credentials বদলালে আগে যাচাই
        val auth = if (credentialsChanged) api.authenticate(credentials) else null

        val updated = existing.copy(
            name = name.trim(),
            url = credentials.baseUrl,
            username = credentials.username,
            password = credentials.password,
            userAgent = credentials.userAgent,
        )
        providers.update(updated)

        return if (credentialsChanged) {
            sync(updated, syncEpg = false, knownAuth = auth).let {
                syncEpgInBackground(it.epgUrl)
                it.copy(epgPending = it.epgUrl.isNotBlank())
            }
        } else null
    }

    /**
     * @param syncEpg false হলে শুধু channel import + EPG URL সেভ; EPG download caller করবে
     *                (Settings-এর "Refresh Playlist" একবারে সব provider-এর EPG নামায়)।
     */
    suspend fun sync(
        provider: ProviderEntity,
        syncEpg: Boolean = true,
        knownAuth: XtreamAuth? = null,
    ): XtreamSyncSummary = syncLock.withLock {
        val credentials = credentials(
            provider.url,
            provider.username.orEmpty(),
            provider.password.orEmpty(),
            provider.userAgent,
        )

        val catalog = api.loadCatalog(credentials, knownAuth)
        val count = channels.import(
            catalog.channels,
            isPremium = false,
            providerId = provider.id,
        )
        if (count <= 0) {
            throw XtreamException("No channels could be saved from this Xtream account.")
        }

        // EPG download fail করলেও URL সেভ থাকবে, যাতে পরে retry হয়
        providers.setEpgUrl(provider.id, catalog.epgUrl)

        var programs = 0
        var epgError: String? = null
        if (syncEpg && catalog.epgUrl.isNotBlank()) {
            try {
                programs = playlists.syncEpg(catalog.epgUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                epgError = e.message ?: "TV guide download failed."
            }
        }

        XtreamSyncSummary(
            channels = count,
            live = catalog.liveCount,
            movies = catalog.movieCount,
            programs = programs,
            epgError = epgError,
            epgUrl = catalog.epgUrl,
        )
    }

    /** Add/Edit dialog-এ "Test" বাটনের জন্য (ভবিষ্যতে UI-তে যোগ করা যায়)। */
    suspend fun test(
        url: String,
        username: String,
        password: String,
        userAgent: String? = null,
    ): XtreamAuth {
        val credentials = credentials(url, username, password, userAgent)
        return api.authenticate(credentials)
    }

    private fun credentials(
        url: String,
        username: String,
        password: String,
        userAgent: String?,
    ): XtreamCredentials {
        val base = XtreamApi.normalizeBaseUrl(url)
            ?: throw XtreamException("Enter a valid Xtream server address.")
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            throw XtreamException("Username and password are required.")
        }
        return XtreamCredentials(
            baseUrl = base,
            username = user,
            password = password,
            userAgent = userAgent?.trim()?.takeIf { it.isNotBlank() },
        )
    }
}
