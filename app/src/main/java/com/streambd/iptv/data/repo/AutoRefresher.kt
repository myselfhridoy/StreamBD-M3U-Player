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

/**
 * অ্যাপ সামনে থাকলে প্লেলিস্ট ও EPG নিজে নিজে হালনাগাদ রাখে।
 *
 * **নতুন:** একই ১৫-মিনিটের tick-এ activation-ও revalidate করে। এটাই টিভিতে
 * দিনের পর দিন চলা সেশনে revoked/suspended/expired কোড ধরতে পারে — restart ছাড়াই।
 */
@Singleton
class AutoRefresher @Inject constructor(
    private val sync: PlaylistSync,
    private val activation: ActivationRepository,
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
        // ১. Activation revalidate — নিজেই intervalOn() দিয়ে throttled।
        //    Revoked/suspended/expired হলে ActivationRepository ভেতরেই store.clear() করে।
        try {
            activation.revalidateIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "revalidate failed: ${e.message}", e)
        }

        // ২. Revalidate-এ activation মুছে গেলে লুপ নিজেই থামিয়ে দেয়।
        //    MainActivity.onStart() এ start() আবার ডাকলে job?.isActive == false দেখে
        //    নতুন করে চালু হবে না — ইউজারকে re-activate করতে হবে। এটাই সঠিক।
        val active = activation.current() as? ActivationState.Active
        if (active == null) {
            Log.i(TAG, "activation cleared during revalidate — stopping refresh loop")
            stop()
            return
        }
        if (active.playlistUrl.isBlank()) return

        // ৩. Playlist + EPG sync (আগের মতোই)
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

            val result = sync.syncAll(
                playlistUrl = active.playlistUrl,
                epgUrl = active.epgUrl,
                preferDiscovered = activation.isLocal(active),
            )
            if (result.epgUrl != active.epgUrl) activation.updateEpgUrl(result.epgUrl)
        } finally {
            if (atEntry) _isRefreshing.value = false
        }
    }
}
