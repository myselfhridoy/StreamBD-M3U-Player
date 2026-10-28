package com.streambd.iptv.data.repo

import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** অ্যাপ সামনে থাকলে প্লেলিস্ট ও EPG নিজে নিজে হালনাগাদ রাখে। */
@Singleton
class AutoRefresher @Inject constructor(
    private val sync: PlaylistSync,
    private val activation: ActivationRepository,
) {
    private companion object {
        const val FIRST_DELAY_MS = 20_000L
        const val CHECK_EVERY_MS = 30L * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Splash ও প্রথম চ্যানেল চালুর কাজ আগে শেষ হোক
            delay(FIRST_DELAY_MS)
            while (isActive) {
                try {
                    refreshIfDue()
                } catch (_: SyncException) {
                    // নেটওয়ার্ক নেই/সার্ভার ডাউন: পরের চেকে আবার
                }
                delay(CHECK_EVERY_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun refreshIfDue() {
        val active = activation.current() as? ActivationState.Active ?: return
        if (active.playlistUrl.isBlank() || !sync.isPlaylistDue()) return

        val result = sync.syncAll(
            playlistUrl = active.playlistUrl,
            epgUrl = active.epgUrl,
            preferDiscovered = activation.isLocal(active),
        )
        if (result.epgUrl != active.epgUrl) activation.updateEpgUrl(result.epgUrl)
    }
}
