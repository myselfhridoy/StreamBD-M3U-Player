package com.streambd.iptv.data.repo

import android.util.Log
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.data.db.ProviderDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "AutoRefresher"

@Singleton
class AutoRefresher @Inject constructor(
    private val sync: PlaylistSync,
    private val activation: ActivationRepository,
    private val providerDao: ProviderDao, // নতুন: ইউজারের প্লেলিস্ট পাওয়ার জন্য
) {
    private companion object {
        const val FIRST_DELAY_MS = 2_000L
        const val CHECK_EVERY_MS = 15L * 60 * 1000
        const val ENTRY_MIN_AGE_MS = 5L * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var entryRefreshDone = false

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(FIRST_DELAY_MS)
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "auto refresh failed: ${e.message}", e)
                }
                delay(CHECK_EVERY_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _isRefreshing.value = false
    }

    private suspend fun tick() {
        try {
            activation.revalidateIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "revalidate failed: ${e.message}", e)
        }

        val active = activation.current() as? ActivationState.Active
        if (active == null) {
            Log.i(TAG, "activation cleared during revalidate — stopping refresh loop")
            stop()
            return
        }

        val atEntry = !entryRefreshDone
        entryRefreshDone = true

        val due = if (atEntry) {
            sync.isPlaylistDue(maxAgeMs = ENTRY_MIN_AGE_MS)
        } else {
            sync.isPlaylistDue()
        }
        if (!due) return

        try {
            if (atEntry) _isRefreshing.value = true

            // ১. সার্ভারের প্লেলিস্ট আপডেট (যদি থাকে)
            if (active.playlists.isNotEmpty()) {
                try {
                    val result = sync.syncAll(
                        playlists = active.playlists,
                        preferDiscovered = activation.isLocal(active),
                        isPremium = true
                    )
                    val firstEpg = active.playlists.firstOrNull()?.epgUrl ?: ""
                    if (result.epgUrl.isNotBlank() && result.epgUrl != firstEpg) {
                        activation.updateEpgUrl(result.epgUrl)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Server playlists sync failed: ${e.message}")
                }
            }

            // ২. ইউজারের নিজের প্লেলিস্টগুলো আপডেট
            try {
                val userProviders = providerDao.getAll()
                for (p in userProviders) {
                    try {
                        sync.syncPlaylist(p.url, providerId = p.id)
                    } catch (e: Exception) {
                        Log.w(TAG, "User playlist ${p.name} sync failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch user providers: ${e.message}")
            }

        } finally {
            if (atEntry) _isRefreshing.value = false
        }
    }
}
