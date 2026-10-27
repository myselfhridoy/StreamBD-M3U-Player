package com.streambd.iptv.data.activation

import com.streambd.iptv.BuildConfig
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class ActivationRepository @Inject constructor(
    private val store: ActivationStore,
    private val api: ActivationApi,
) {
    companion object {
        const val LOCAL_CODE = "LOCAL"
        private const val LEGACY_LOCAL_CODE = "DEV"
        private const val REVALIDATE_INTERVAL_MS = 24L * 60 * 60 * 1000
    }

    val state: Flow<ActivationState> = store.state

    suspend fun current(): ActivationState = store.current()
    suspend fun deviceId(): String = store.deviceId()
    suspend fun clear() = store.clear()

    private fun isLocal(code: String): Boolean =
        code == LOCAL_CODE || code == LEGACY_LOCAL_CODE

    // --- নতুন যোগ করা ফাংশন ১ ---
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
        } catch (e: UnknownHostException) {
            Result.failure(IllegalStateException("Cannot reach the activation server. Check your internet connection."))
        } catch (e: IOException) {
            Result.failure(IllegalStateException("Network error. Please try again."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Server ছাড়া সরাসরি playlist URL দিয়ে সেটআপ। মেয়াদ কখনও শেষ হয় না (expiresAt = 0). */
    suspend fun activateLocally(playlistUrl: String, epgUrl: String): ActivationState.Active {
        store.saveActive(LOCAL_CODE, 0L, playlistUrl, epgUrl)
        return ActivationState.Active(LOCAL_CODE, 0L, playlistUrl, epgUrl)
    }

    // --- নতুন যোগ করা ফাংশন ২ ---
    /** প্লেলিস্ট থেকে পাওয়া EPG লিংক সংরক্ষণ। বাকি তথ্য অপরিবর্তিত। */
    suspend fun updateEpgUrl(epgUrl: String) {
        val active = store.current() as? ActivationState.Active ?: return
        if (active.epgUrl == epgUrl) return
        store.saveActive(active.code, active.expiresAt, active.playlistUrl, epgUrl)
    }

    suspend fun revalidateIfNeeded() {
        if (!BuildConfig.ACTIVATION_ENABLED) return
        val active = store.current() as? ActivationState.Active ?: return
        if (isLocal(active.code)) return
        if (System.currentTimeMillis() - store.lastCheck() < REVALIDATE_INTERVAL_MS) return

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
        } catch (_: Exception) {
            // অফলাইন/সার্ভার ডাউন হলে পুরনো activation চালু থাকবে
        }
    }

    fun isExpired(active: ActivationState.Active): Boolean {
        if (isLocal(active.code)) return false
        return active.expiresAt > 0L && active.expiresAt < System.currentTimeMillis()
    }
}
