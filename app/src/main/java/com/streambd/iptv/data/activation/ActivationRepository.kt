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

        // Shorter interval near expiry so trial abuse is caught quickly.
        private const val REVALIDATE_INTERVAL_PREMIUM_MS = 6L * 60 * 60 * 1000  // 6h
        private const val REVALIDATE_INTERVAL_TRIAL_MS   = 1L * 60 * 60 * 1000  // 1h
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
                playlistUrl = response.playlistUrl,
                epgUrl = response.epgUrl,
            )
            Result.success(
                ActivationState.Active(
                    response.code,
                    response.expiresAt,
                    response.playlistUrl,
                    response.epgUrl,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ActivationRejectedException) {
            // Server said "no" — surface the message directly to the user.
            Log.w(TAG, "activate rejected: ${e.reason} (HTTP ${e.httpCode})")
            Result.failure(IllegalStateException(e.reason, e))
        } catch (e: ActivationNetworkException) {
            // Offline or 5xx — generic network message.
            Log.w(TAG, "activate network error: ${e.message}", e)
            Result.failure(IllegalStateException(e.message ?: "Network error", e))
        } catch (e: Exception) {
            Log.e(TAG, "activate unexpected", e)
            Result.failure(e)
        }
    }

    /** Server ছাড়া সরাসরি playlist URL দিয়ে সেটআপ। মেয়াদ কখনও শেষ হয় না (expiresAt = 0). */
    suspend fun activateLocally(playlistUrl: String, epgUrl: String): ActivationState.Active {
        store.saveActive(LOCAL_CODE, 0L, playlistUrl, epgUrl)
        return ActivationState.Active(LOCAL_CODE, 0L, playlistUrl, epgUrl)
    }

    /** প্লেলিস্ট থেকে পাওয়া EPG লিংক সংরক্ষণ। বাকি তথ্য অপরিবর্তিত। */
    suspend fun updateEpgUrl(epgUrl: String) {
        val active = store.current() as? ActivationState.Active ?: return
        if (active.epgUrl == epgUrl) return
        store.saveActive(active.code, active.expiresAt, active.playlistUrl, epgUrl)
    }

    /**
     * Server থেকে এখন-কত-সময়-বাকি সেটার উপর ভিত্তি করে revalidation interval।
     * ৭ দিন বা কম বাকি থাকলে দ্রুত (১ ঘণ্টা), নাহলে ধীরে (৬ ঘণ্টা)।
     */
    private fun intervalFor(active: ActivationState.Active): Long {
        if (active.expiresAt <= 0L) return REVALIDATE_INTERVAL_PREMIUM_MS
        val daysLeft = (active.expiresAt - System.currentTimeMillis()) / (24L * 60 * 60 * 1000)
        return if (daysLeft <= TRIAL_THRESHOLD_DAYS) {
            REVALIDATE_INTERVAL_TRIAL_MS
        } else {
            REVALIDATE_INTERVAL_PREMIUM_MS
        }
    }

    /**
     * Ping the server if enough time has passed. This is the *only* place that
     * decides whether local activation state survives.
     *
     *   Success                       → refresh stored values (playlist URL may change)
     *   Rejected + terminal (403)     → clear local state → forces re-activation
     *   Rejected + recoverable        → keep state (device limit, unknown)
     *   Network error / 5xx           → keep state, retry next cycle
     */
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
                response.playlistUrl,
                response.epgUrl,
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
