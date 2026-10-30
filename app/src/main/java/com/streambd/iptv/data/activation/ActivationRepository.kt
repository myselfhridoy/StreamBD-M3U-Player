package com.streambd.iptv.data.activation

import android.util.Log
import com.streambd.iptv.BuildConfig
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "ActivationRepo"

@Singleton
class ActivationRepository @Inject constructor(
    private val store: ActivationStore,
    private val api: ActivationApi,
) {
    companion object {
        const val LOCAL_CODE = "LOCAL"
        private const val LEGACY_LOCAL_CODE = "DEV"

        private const val REVALIDATE_INTERVAL_PREMIUM_MS = 6L * 60 * 60 * 1000
        private const val REVALIDATE_INTERVAL_TRIAL_MS   = 1L * 60 * 60 * 1000
        private const val TRIAL_THRESHOLD_DAYS           = 7L
    }

    val state: Flow<ActivationState> = store.state

    suspend fun current(): ActivationState = store.current()
    suspend fun deviceId(): String = store.deviceId()
    suspend fun clear() = store.clear()

    private fun isLocal(code: String): Boolean =
        code == LOCAL_CODE || code == LEGACY_LOCAL_CODE

    fun isLocal(active: ActivationState.Active): Boolean = isLocal(active.code)

    suspend fun activate(code: String): Result<ActivationState.Active> {
        if (!BuildConfig.ACTIVATION_ENABLED) {
            return Result.failure(IllegalStateException("Activation server is not available yet"))
        }
        return try {
            val response = api.activate(code, store.deviceId())
            store.saveActive(
                code = response.code,
                expiresAt = response.expiresAt,
                playlists = response.playlists, // নতুন: লিস্ট সেভ
            )
            Result.success(
                ActivationState.Active(
                    response.code,
                    response.expiresAt,
                    response.playlists,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ActivationRejectedException) {
            Log.w(TAG, "activate rejected: ${e.reason} (HTTP ${e.httpCode})")
            // নতুন: সার্ভার থেকে যদি বলা হয় লোকাল স্টেট ক্লিয়ার করতে, তবে ক্লিয়ার করা হবে
            if (e.shouldClearLocalState) {
                store.clear()
            }
            Result.failure(IllegalStateException(e.reason, e))
        } catch (e: ActivationNetworkException) {
            Log.w(TAG, "activate network error: ${e.message}", e)
            Result.failure(IllegalStateException(e.message ?: "Network error", e))
        } catch (e: Exception) {
            Log.e(TAG, "activate unexpected", e)
            Result.failure(e)
        }
    }

    /** Server ছাড়া সরাসরি playlist URL দিয়ে সেটআপ। মেয়াদ কখনও শেষ হয় না (expiresAt = 0). */
    suspend fun activateLocally(playlistUrl: String, epgUrl: String): ActivationState.Active {
        val p = PlaylistData("Local Playlist", playlistUrl, epgUrl)
        store.saveActive(LOCAL_CODE, 0L, listOf(p))
        return ActivationState.Active(LOCAL_CODE, 0L, listOf(p))
    }

    /** প্লেলিস্ট থেকে পাওয়া EPG লিংক সংরক্ষণ। এখানে প্রথম প্লেলিস্টের EPG আপডেট করা হচ্ছে। */
    suspend fun updateEpgUrl(epgUrl: String) {
        val active = store.current() as? ActivationState.Active ?: return
        if (active.playlists.isEmpty()) return
        if (active.playlists[0].epgUrl == epgUrl) return
        
        val updatedList = active.playlists.mapIndexed { index, p ->
            if (index == 0) p.copy(epgUrl = epgUrl) else p
        }
        store.saveActive(active.code, active.expiresAt, updatedList)
    }

    private fun intervalFor(active: ActivationState.Active): Long {
        if (active.expiresAt <= 0L) return REVALIDATE_INTERVAL_PREMIUM_MS
        val daysLeft = (active.expiresAt - System.currentTimeMillis()) / (24L * 60 * 60 * 1000)
        return if (daysLeft <= TRIAL_THRESHOLD_DAYS) {
            REVALIDATE_INTERVAL_TRIAL_MS
        } else {
            REVALIDATE_INTERVAL_PREMIUM_MS
        }
    }

    suspend fun revalidateIfNeeded() {
        if (!BuildConfig.ACTIVATION_ENABLED) return
        val active = store.current() as? ActivationState.Active ?: return
        if (isLocal(active.code)) return
        if (System.currentTimeMillis() - store.lastCheck() < intervalFor(active)) return

        store.touchLastCheck()
        try {
            val response = api.activate(active.code, store.deviceId())
            store.saveActive(
                response.code,
                response.expiresAt,
                response.playlists, // নতুন: লিস্ট সেভ
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ActivationRejectedException) {
            if (e.shouldClearLocalState) {
                Log.w(TAG, "revalidate: CLEARING activation — ${e.reason}")
                store.clear()
            } else {
                Log.w(TAG, "revalidate rejected (state kept): ${e.reason}")
            }
        } catch (e: ActivationNetworkException) {
            Log.w(TAG, "revalidate network error (state kept): ${e.message}", e)
        } catch (e: Exception) {
            Log.w(TAG, "revalidate unexpected (state kept)", e)
        }
    }

    fun isExpired(active: ActivationState.Active): Boolean {
        if (isLocal(active.code)) return false
        return active.expiresAt > 0L && active.expiresAt < System.currentTimeMillis()
    }
}
