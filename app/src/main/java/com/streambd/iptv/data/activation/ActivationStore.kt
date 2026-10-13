package com.streambd.iptv.data.activation

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.activationDataStore: DataStore<Preferences> by
    preferencesDataStore("streambd_activation")

@Singleton
class ActivationStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    private object Keys {
        val CODE = stringPreferencesKey("code")
        val EXPIRES_AT = longPreferencesKey("expires_at")
        val PLAYLIST_URL = stringPreferencesKey("playlist_url")
        val EPG_URL = stringPreferencesKey("epg_url")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val LAST_CHECK = longPreferencesKey("last_check")
    }

    private val prefs = ctx.activationDataStore

    val state: Flow<ActivationState> = prefs.data.map { values ->
        val code = values[Keys.CODE]
        if (code.isNullOrBlank()) {
            ActivationState.NotActivated
        } else {
            ActivationState.Active(
                code = code,
                expiresAt = values[Keys.EXPIRES_AT] ?: 0L,
                playlistUrl = values[Keys.PLAYLIST_URL].orEmpty(),
                epgUrl = values[Keys.EPG_URL].orEmpty(),
            )
        }
    }

    suspend fun current(): ActivationState = state.first()

    suspend fun saveActive(code: String, expiresAt: Long, playlistUrl: String, epgUrl: String) {
        prefs.edit { values ->
            values[Keys.CODE] = code
            values[Keys.EXPIRES_AT] = expiresAt
            values[Keys.PLAYLIST_URL] = playlistUrl
            values[Keys.EPG_URL] = epgUrl
            values[Keys.LAST_CHECK] = System.currentTimeMillis()
        }
    }

    suspend fun clear() {
        prefs.edit { values ->
            values.remove(Keys.CODE)
            values.remove(Keys.EXPIRES_AT)
            values.remove(Keys.PLAYLIST_URL)
            values.remove(Keys.EPG_URL)
            values.remove(Keys.LAST_CHECK)
        }
    }

    suspend fun lastCheck(): Long = prefs.data.first()[Keys.LAST_CHECK] ?: 0L

    suspend fun touchLastCheck() {
        prefs.edit { it[Keys.LAST_CHECK] = System.currentTimeMillis() }
    }

    suspend fun deviceId(): String {
        val existing = prefs.data.first()[Keys.DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing

        val newId = UUID.randomUUID().toString()
        prefs.edit { it[Keys.DEVICE_ID] = newId }
        return newId
    }
}