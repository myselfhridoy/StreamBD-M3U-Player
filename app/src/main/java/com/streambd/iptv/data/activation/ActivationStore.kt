package com.streambd.iptv.data.activation

import android.content.Context
import android.provider.Settings // নতুন ইমপোর্ট
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.activationDataStore: DataStore<Preferences> by preferencesDataStore("streambd_activation")

@Singleton
class ActivationStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    private object Keys {
        val CODE = stringPreferencesKey("code")
        val EXPIRES_AT = longPreferencesKey("expires_at")
        val PLAYLISTS_JSON = stringPreferencesKey("playlists_json")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val LAST_CHECK = longPreferencesKey("last_check")
    }

    private val prefs = ctx.activationDataStore

    val state: Flow<ActivationState> = prefs.data.map { values ->
        val code = values[Keys.CODE]
        if (code.isNullOrBlank()) {
            ActivationState.NotActivated
        } else {
            val playlists = mutableListOf<PlaylistData>()
            val jsonStr = values[Keys.PLAYLISTS_JSON]
            if (!jsonStr.isNullOrBlank()) {
                try {
                    val arr = JSONArray(jsonStr)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        playlists.add(PlaylistData(o.getString("name"), o.getString("m3uUrl"), o.getString("epgUrl")))
                    }
                } catch (e: Exception) {
                    // ignore corrupt data
                }
            }
            ActivationState.Active(
                code = code,
                expiresAt = values[Keys.EXPIRES_AT] ?: 0L,
                playlists = playlists
            )
        }
    }

    suspend fun current(): ActivationState = state.first()

    suspend fun saveActive(code: String, expiresAt: Long, playlists: List<PlaylistData>) {
        val arr = JSONArray()
        playlists.forEach {
            arr.put(JSONObject().apply {
                put("name", it.name)
                put("m3uUrl", it.m3uUrl)
                put("epgUrl", it.epgUrl)
            })
        }

        prefs.edit { values ->
            values[Keys.CODE] = code
            values[Keys.EXPIRES_AT] = expiresAt
            values[Keys.PLAYLISTS_JSON] = arr.toString()
            values[Keys.LAST_CHECK] = System.currentTimeMillis()
        }
    }

    suspend fun clear() {
        prefs.edit { values ->
            values.remove(Keys.CODE)
            values.remove(Keys.EXPIRES_AT)
            values.remove(Keys.PLAYLISTS_JSON)
            values.remove(Keys.LAST_CHECK)
        }
    }

    suspend fun lastCheck(): Long = prefs.data.first()[Keys.LAST_CHECK] ?: 0L

    suspend fun touchLastCheck() {
        prefs.edit { it[Keys.LAST_CHECK] = System.currentTimeMillis() }
    }

    // নতুন: ANDROID_ID ব্যবহার করে ডিভাইস আইডি স্থায়ী করা
    suspend fun deviceId(): String {
        val existing = prefs.data.first()[Keys.DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing

        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        val newId = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            androidId
        } else {
            // যদি কোনো কারণে Android ID না পাওয়া যায় বা বাগি আইডি হয়, তবে UUID ব্যবহার হবে
            UUID.randomUUID().toString()
        }

        prefs.edit { it[Keys.DEVICE_ID] = newId }
        return newId
    }
}
