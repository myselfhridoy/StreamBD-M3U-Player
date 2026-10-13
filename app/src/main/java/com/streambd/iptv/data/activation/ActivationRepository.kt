package com.streambd.iptv.data.activation

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivationRepository @Inject constructor(
    private val store: ActivationStore,
    private val api: ActivationApi,
) {
    val state: Flow<ActivationState> = store.state

    suspend fun current(): ActivationState = store.current()
    suspend fun deviceId(): String = store.deviceId()
    suspend fun clear() = store.clear()

    suspend fun activate(code: String): Result<ActivationState.Active> = runCatching {
        val response = api.activate(code, store.deviceId())
        store.saveActive(
            code = response.code,
            expiresAt = response.expiresAt,
            playlistUrl = response.playlistUrl,
            epgUrl = response.epgUrl,
        )
        ActivationState.Active(
            response.code,
            response.expiresAt,
            response.playlistUrl,
            response.epgUrl,
        )
    }

    suspend fun activateLocally(playlistUrl: String, epgUrl: String): ActivationState.Active {
        val expiresAt = System.currentTimeMillis() + 100L * 365 * 24 * 60 * 60 * 1000
        store.saveActive("DEV", expiresAt, playlistUrl, epgUrl)
        return ActivationState.Active("DEV", expiresAt, playlistUrl, epgUrl)
    }

    suspend fun revalidateIfNeeded() {
        val active = store.current() as? ActivationState.Active ?: return
        if (active.code == "DEV") return
        if (System.currentTimeMillis() - store.lastCheck() < 24L * 60 * 60 * 1000) return

        store.touchLastCheck()
        runCatching {
            val response = api.activate(active.code, store.deviceId())
            store.saveActive(
                response.code,
                response.expiresAt,
                response.playlistUrl,
                response.epgUrl,
            )
        }
    }

    fun isExpired(active: ActivationState.Active): Boolean =
        active.expiresAt > 0L && active.expiresAt < System.currentTimeMillis()
}