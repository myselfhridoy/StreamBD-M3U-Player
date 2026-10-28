package com.streambd.iptv.data.repo

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

/** অ্যাপ সামনে থাকলে প্লেলিস্ট ও EPG নিজে নিজে হালনাগাদ রাখে। */
@Singleton
class AutoRefresher @Inject constructor(
    private val sync: PlaylistSync,
    private val activation: ActivationRepository,
) {
    private companion object {
        const val FIRST_DELAY_MS = 2_000L // অ্যাপ ওপেনের ২ সেকেন্ড পর (UI রেন্ডার হতে সময় দেওয়া)
        const val CHECK_EVERY_MS = 30L * 60 * 1000 // এরপর প্রতি ৩০ মিনিট পর
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    // নতুন: UI-তে লোডিং বার দেখানোর জন্য স্টেট
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Splash ও প্রথম চ্যানেল চালুর কাজ আগে শেষ হোক
            delay(FIRST_DELAY_MS)
            var isFirstRun = true
            
            while (isActive) {
                try {
                    refreshIfDue(isFirstRun)
                } catch (_: SyncException) {
                    // নেটওয়ার্ক নেই/সার্ভার ডাউন: পরের চেকে আবার চেষ্টা করবে
                }
                isFirstRun = false
                delay(CHECK_EVERY_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _isRefreshing.value = false // স্টেট রিসেট
    }

    private suspend fun refreshIfDue(isFirstRun: Boolean) {
        val active = activation.current() as? ActivationState.Active ?: return
        if (active.playlistUrl.isBlank()) return
        
        // প্রথমবার হলে ৬ ঘণ্টার শর্ত চেক না করে সরাসরি আপডেট করবে
        if (!isFirstRun && !sync.isPlaylistDue()) return

        try {
            // শুধু অ্যাপে নতুন ঢোকার সময় লোডিং দেখাবে
            if (isFirstRun) _isRefreshing.value = true
            
            val result = sync.syncAll(
                playlistUrl = active.playlistUrl,
                epgUrl = active.epgUrl,
                preferDiscovered = activation.isLocal(active),
            )
            
            if (result.epgUrl != active.epgUrl) {
                activation.updateEpgUrl(result.epgUrl)
            }
        } finally {
            // কাজ শেষ হলে লোডিং বার সরিয়ে নেবে
            if (isFirstRun) _isRefreshing.value = false
        }
    }
}
