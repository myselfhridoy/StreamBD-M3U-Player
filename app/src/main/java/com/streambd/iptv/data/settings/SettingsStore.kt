package com.streambd.iptv.data.settings

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streambd.iptv.player.AspectMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("streambd_settings")

data class AppSettings(
    val startAfterReboot: Boolean = false,
    val showEpgOnSelect: Boolean = true,
    val aspectMode: AspectMode = AspectMode.STRETCH,
    val epgTimeShiftHours: Int = 0,
    val bufferTimeMs: Int = 10_000,
)

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    private object Keys {
        val START_AFTER_REBOOT = booleanPreferencesKey("start_after_reboot")
        val SHOW_EPG_ON_SELECT = booleanPreferencesKey("show_epg_on_select")
        val ASPECT_MODE = stringPreferencesKey("aspect_mode")
        val EPG_TIME_SHIFT = intPreferencesKey("epg_time_shift")
        val BUFFER_TIME_MS = intPreferencesKey("buffer_time_ms")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
    }

    private val prefs = ctx.settingsDataStore

    val flow: Flow<AppSettings> = prefs.data.map { p ->
        AppSettings(
            startAfterReboot = p[Keys.START_AFTER_REBOOT] ?: false,
            showEpgOnSelect = p[Keys.SHOW_EPG_ON_SELECT] ?: true,
            aspectMode = p[Keys.ASPECT_MODE]?.let {
                runCatching { AspectMode.valueOf(it) }.getOrNull()
            } ?: AspectMode.STRETCH,
            epgTimeShiftHours = p[Keys.EPG_TIME_SHIFT] ?: 0,
            bufferTimeMs = p[Keys.BUFFER_TIME_MS] ?: 10_000,
        )
    }

    suspend fun setStartAfterReboot(v: Boolean) = prefs.edit { it[Keys.START_AFTER_REBOOT] = v }
    suspend fun setShowEpgOnSelect(v: Boolean) = prefs.edit { it[Keys.SHOW_EPG_ON_SELECT] = v }
    suspend fun setAspectMode(m: AspectMode) = prefs.edit { it[Keys.ASPECT_MODE] = m.name }
    suspend fun setEpgTimeShift(h: Int) = prefs.edit { it[Keys.EPG_TIME_SHIFT] = h }
    suspend fun setBufferTime(ms: Int) = prefs.edit { it[Keys.BUFFER_TIME_MS] = ms }

    suspend fun setPin(pin: String) {
        val salt = randomSalt()
        prefs.edit {
            it[Keys.PIN_SALT] = salt
            it[Keys.PIN_HASH] = hashPin(pin, salt)
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val p = prefs.data.first()
        val salt = p[Keys.PIN_SALT] ?: return pin == "0000"
        val hash = p[Keys.PIN_HASH] ?: return pin == "0000"
        return hashPin(pin, salt) == hash
    }

    suspend fun clearPin() = prefs.edit {
        it.remove(Keys.PIN_HASH)
        it.remove(Keys.PIN_SALT)
    }

    suspend fun clearAll() = prefs.edit { it.clear() }

    private fun randomSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt.toByteArray())
        md.update(pin.toByteArray())
        return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
    }
}
