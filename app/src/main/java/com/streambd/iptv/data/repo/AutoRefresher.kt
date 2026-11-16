package com.streambd.iptv.data.repo

import android.util.Log
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.addon.AddonCatalogService
import com.streambd.iptv.data.addon.AddonRepository
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.data.db.ProviderDao
import com.streambd.iptv.data.xtream.XtreamRepository
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
    private val providerDao: ProviderDao,
    private val xtream: XtreamRepository,
    private val addons: AddonRepository,
    private val catalog: AddonCatalogService,
) {
    private companion object {
        const val FIRST_DELAY_MS = 2_000L
        const val CHECK_EVERY_MS = 15L * 60 * 1000
        const val ENTRY_MIN_AGE_MS = 5L * 60 * 1000
        const val CATALOG_EVERY_MS = 6L * 60 * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var entryRefreshDone = false
    private var lastCatalogAt = 0L

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        // প্রতিবার অ্যাপে ঢোকার (onStart) সময় "entry refresh" আবার হবে (৫ মিনিটের বেশি পুরনো হলে)
        entryRefreshDone = false
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

        // অফিশিয়াল Add-on ক্যাটালগ: কিল-সুইচ + অটো-আপডেট (৬ ঘণ্টায় একবার; অ্যাপে ঢোকার সময়ও)
        val nowMs = System.currentTimeMillis()
        if (atEntry || nowMs - lastCatalogAt > CATALOG_EVERY_MS) {
            lastCatalogAt = nowMs
            try {
                val n = catalog.syncInBackground()
                if (n > 0) Log.i(TAG, "add-ons auto-updated: $n")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "add-on catalog sync skipped: ${e.message}")
            }
        }

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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Server playlists sync failed: ${e.message}")
                }
            }

            // ২. ইউজারের নিজের প্লেলিস্টগুলো আপডেট
            try {
                val userProviders = providerDao.getAll().filter { it.type != "FTP" } // FTP সার্ভার M3U নয়, সিঙ্কে বাদ
                var epgDue = sync.isEpgDue()
                for (p in userProviders) {
                    try {
                        when (p.type.uppercase()) {
                            "XTREAM" -> {
                                // EPG প্রতি ১৫ মিনিটে নয়; isEpgDue() অনুযায়ী
                                xtream.sync(p, syncEpg = epgDue)
                                if (epgDue) epgDue = sync.isEpgDue()
                            }

                            // Add-on: নিজের manifest.refreshMinutes অনুযায়ী (টোকেন প্লে-টাইমে resolveStream() দিয়ে আসে)
                            "ADDON" -> addons.refreshIfDue(p.id)

                            else -> {
                                val synced = sync.syncPlaylist(p.url, providerId = p.id)
                                if (epgDue && synced.epgUrls.isNotEmpty()) {
                                    try {
                                        sync.syncEpg(synced.epgUrls.joinToString("\n"))
                                        epgDue = sync.isEpgDue()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.w(TAG, "EPG sync failed for ${p.name}: ${e.message}")
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "User playlist ${p.name} sync failed: ${e.message}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch user providers: ${e.message}")
            }

        } finally {
            if (atEntry) _isRefreshing.value = false
        }
    }
}