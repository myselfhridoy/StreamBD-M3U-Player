package com.streambd.iptv.data.settings

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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

// ★ নতুন: সিক স্টেপ অপশন এবং ফরম্যাটার
val SEEK_STEP_OPTIONS_MS = listOf(5_000, 10_000, 15_000, 30_000, 60_000, 300_000, 600_000)

fun formatSeekStep(ms: Int): String =
    if (ms >= 60_000) "${ms / 60_000} min" else "${ms / 1_000} s"

/** পছন্দের ভাষার তালিকা (ISO 639-1 কোড, নাম)। */
val LANGUAGE_OPTIONS = listOf(
    "bn" to "Bengali", "en" to "English", "hi" to "Hindi", "ur" to "Urdu", "ar" to "Arabic",
    "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam", "kn" to "Kannada", "mr" to "Marathi",
    "pa" to "Punjabi", "ne" to "Nepali", "fa" to "Persian", "tr" to "Turkish", "id" to "Indonesian",
    "ms" to "Malay", "th" to "Thai", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean",
    "ru" to "Russian", "fr" to "French", "de" to "German", "es" to "Spanish", "pt" to "Portuguese",
    "it" to "Italian",
)

/** "" = Auto (স্ট্রিমের ডিফল্ট), "off" = বন্ধ (শুধু সাবটাইটেলে)। */
fun langLabel(code: String): String = when (code) {
    "" -> "Auto"
    "off" -> "Off"
    else -> LANGUAGE_OPTIONS.firstOrNull { it.first == code }?.second ?: code
}

data class AppSettings(
    val startAfterReboot: Boolean = false,
    val showEpgOnSelect: Boolean = true,
    val aspectMode: AspectMode = AspectMode.STRETCH,
    val epgTimeShiftMins: Int = 0,
    val bufferTimeMs: Int = 2_500,
    val seekStepMs: Int = 10_000, // ★ নতুন ফিল্ড
    val audioLang: String = "",     // পছন্দের অডিও ভাষা ("" = Auto)
    val subtitleLang: String = "",  // পছন্দের সাবটাইটেল ভাষা ("" = Auto, "off" = বন্ধ)
)

enum class TrackMode { NONE, AUTO, SPECIFIC }

data class TrackPrefs(
    val videoMode: TrackMode = TrackMode.AUTO,
    val videoKey: String = "",
    val audioMode: TrackMode = TrackMode.AUTO,
    val audioKey: String = "",
)

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    private object Keys {
        val START_AFTER_REBOOT = booleanPreferencesKey("start_after_reboot")
        val SHOW_EPG_ON_SELECT = booleanPreferencesKey("show_epg_on_select")
        val ASPECT_MODE = stringPreferencesKey("aspect_mode")
        val EPG_TIME_SHIFT_MINS = intPreferencesKey("epg_time_shift_mins")
        val LEGACY_EPG_TIME_SHIFT_HOURS = intPreferencesKey("epg_time_shift")
        val BUFFER_TIME_MS = intPreferencesKey("buffer_time_ms")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val VIDEO_MODE = stringPreferencesKey("track_video_mode")
        val VIDEO_KEY = stringPreferencesKey("track_video_key")
        val AUDIO_MODE = stringPreferencesKey("track_audio_mode")
        val AUDIO_KEY = stringPreferencesKey("track_audio_key")
        val LAST_LIVE_ID = longPreferencesKey("last_live_id")
        val SEEK_STEP_MS = intPreferencesKey("seek_step_ms") // ★ নতুন Key
        val PREF_AUDIO_LANG = stringPreferencesKey("pref_audio_lang")
        val PREF_SUB_LANG = stringPreferencesKey("pref_sub_lang")
    }

    private val prefs = ctx.settingsDataStore

    val flow: Flow<AppSettings> = prefs.data.map { p ->
        AppSettings(
            startAfterReboot = p[Keys.START_AFTER_REBOOT] ?: false,
            showEpgOnSelect = p[Keys.SHOW_EPG_ON_SELECT] ?: true,
            aspectMode = p[Keys.ASPECT_MODE]?.let {
                runCatching { AspectMode.valueOf(it) }.getOrNull()
            } ?: AspectMode.STRETCH,
            epgTimeShiftMins = p[Keys.EPG_TIME_SHIFT_MINS]
                ?: ((p[Keys.LEGACY_EPG_TIME_SHIFT_HOURS] ?: 0).coerceIn(-10, 10) * 60),
            bufferTimeMs = (p[Keys.BUFFER_TIME_MS] ?: 2_500).coerceIn(1_000, 10_000),
            seekStepMs = (p[Keys.SEEK_STEP_MS] ?: 10_000).takeIf { it in SEEK_STEP_OPTIONS_MS } ?: 10_000, // ★ নতুন
            audioLang = p[Keys.PREF_AUDIO_LANG].orEmpty(),
            subtitleLang = p[Keys.PREF_SUB_LANG].orEmpty(),
        )
    }

    val trackPrefs: Flow<TrackPrefs> = prefs.data.map { p ->
        TrackPrefs(
            videoMode = runCatching { TrackMode.valueOf(p[Keys.VIDEO_MODE] ?: "AUTO") }
                .getOrDefault(TrackMode.AUTO),
            videoKey = p[Keys.VIDEO_KEY].orEmpty(),
            audioMode = runCatching { TrackMode.valueOf(p[Keys.AUDIO_MODE] ?: "AUTO") }
                .getOrDefault(TrackMode.AUTO),
            audioKey = p[Keys.AUDIO_KEY].orEmpty(),
        )
    }

    suspend fun setStartAfterReboot(v: Boolean) = prefs.edit { it[Keys.START_AFTER_REBOOT] = v }
    suspend fun setShowEpgOnSelect(v: Boolean) = prefs.edit { it[Keys.SHOW_EPG_ON_SELECT] = v }
    suspend fun setAspectMode(m: AspectMode) = prefs.edit { it[Keys.ASPECT_MODE] = m.name }
    suspend fun setEpgTimeShiftMins(mins: Int) = prefs.edit {
        it[Keys.EPG_TIME_SHIFT_MINS] = mins.coerceIn(-600, 600)
        it.remove(Keys.LEGACY_EPG_TIME_SHIFT_HOURS)
    }
    suspend fun setBufferTime(ms: Int) = prefs.edit { it[Keys.BUFFER_TIME_MS] = ms }
    suspend fun setSeekStep(ms: Int) = prefs.edit { it[Keys.SEEK_STEP_MS] = ms } // ★ নতুন সেটার
    suspend fun setAudioLang(code: String) = prefs.edit { it[Keys.PREF_AUDIO_LANG] = code }
    suspend fun setSubtitleLang(code: String) = prefs.edit { it[Keys.PREF_SUB_LANG] = code }

    suspend fun setVideoTrack(mode: TrackMode, key: String) = prefs.edit {
        it[Keys.VIDEO_MODE] = mode.name
        it[Keys.VIDEO_KEY] = key
    }

    suspend fun setAudioTrack(mode: TrackMode, key: String) = prefs.edit {
        it[Keys.AUDIO_MODE] = mode.name
        it[Keys.AUDIO_KEY] = key
    }

    suspend fun setLastLiveChannel(id: Long) = prefs.edit { it[Keys.LAST_LIVE_ID] = id }
    suspend fun lastLiveChannel(): Long = prefs.data.first()[Keys.LAST_LIVE_ID] ?: -1L

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
