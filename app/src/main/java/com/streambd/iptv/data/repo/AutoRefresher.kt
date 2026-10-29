package com.streambd.iptv.data.repo

import android.util.Log
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
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

/** অ্যাপ সামনে থাকলে প্লেলিস্ট ও EPG নিজে নিজে হালনাগাদ রাখে। */
@Singleton
class AutoRefresher @Inject constructor(
    private val sync: PlaylistSync,
    private val activation: ActivationRepository,
) {
    private companion object {
        const val FIRST_DELAY_MS = 2_000L
        const val CHECK_EVERY_MS = 15L * 60 * 1000 // পরিবর্তন: ১৫ মিনিট পর পর চেক করবে
        // অ্যাপে ঢোকার সময়ের জোর-আপডেট: এর মধ্যে আপডেট হয়ে থাকলে (যেমন Splash-এ) আর নয়
        const val ENTRY_MIN_AGE_MS = 5L * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** এই প্রসেসে "অ্যাপে ঢোকার আপডেট" একবার হয়ে গেছে কিনা। ফোরগ্রাউন্ডে ফিরলে আবার নয়। */
    @Volatile private var entryRefreshDone = false

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(FIRST_DELAY_MS)
            while (isActive) {
                try {
                    refreshIfDue()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // নেটওয়ার্ক নেই/সার্ভার ডাউন/অন্য যেকোনো ত্রুটি: অ্যাপ ক্র্যাশ নয়, পরের চেকে আবার
                    Log.w(TAG, "auto refresh failed: ${e.message}")
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

    private suspend fun refreshIfDue() {
        val active = activation.current() as? ActivationState.Active ?: return
        if (active.playlistUrl.isBlank()) return

        val atEntry = !entryRefreshDone
        entryRefreshDone = true

        // অ্যাপে ঢোকার সময় ৫ মিনিটের বয়স চেক করবে (Splash-এ আপডেট হয়ে থাকলে বাদ),
        // এরপর থেকে ৩০ মিনিটের (PLAYLIST_MAX_AGE_MS) বয়স চেক করবে
        val due = if (atEntry) sync.isPlaylistDue(maxAgeMs = ENTRY_MIN_AGE_MS) else sync.isPlaylistDue()
        if (!due) return

        try {
            // ব্যানার শুধু অ্যাপে ঢোকার আপডেটে
            if (atEntry) _isRefreshing.value = true

            val result = sync.syncAll(
                playlistUrl = active.playlistUrl,
                epgUrl = active.epgUrl,
                preferDiscovered = activation.isLocal(active),
            )
            // টাইপো ফিক্স: updateEpGUrl -> updateEpgUrl
            if (result.epgUrl != active.epgUrl) activation.updateEpgUrl(result.epgUrl)
        } finally {
            if (atEntry) _isRefreshing.value = false
        }
    }
}
