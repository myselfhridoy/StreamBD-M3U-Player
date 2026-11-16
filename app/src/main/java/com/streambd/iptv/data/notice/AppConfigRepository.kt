package com.streambd.iptv.data.notice

import android.content.Context
import android.util.Base64
import android.util.Log
import com.streambd.iptv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class Announcement(
    val id: Long,
    val title: String,
    val message: String,
    /** info | warning | critical */
    val level: String,
    val dismissible: Boolean,
)

data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val url: String,
    /** true = ন্যূনতম ভার্সনের নিচে: অ্যাপ আটকে দেওয়া হবে। */
    val force: Boolean,
)

/** UI যা দেখাবে। সব null হলে কিছু নয়। */
data class NoticeState(
    val update: UpdateInfo? = null,
    val announcement: Announcement? = null,
)

/**
 * সার্ভারের `/config`: ন্যূনতম ভার্সন (বাধ্যতামূলক আপডেট), নতুন ভার্সনের নোটিশ, ঘোষণা।
 * সর্বশেষ কনফিগ ফোনে সংরক্ষিত থাকে, তাই অফলাইনেও বাধ্যতামূলক আপডেট কার্যকর থাকে।
 * সার্ভার ব্যর্থ বা অনুপস্থিত হলে কখনো ইউজারকে আটকায় না (নতুন তথ্য না এলে আগেরটাই থাকে)।
 */
@Singleton
class AppConfigRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val http: OkHttpClient,
) {
    private companion object {
        const val TAG = "AppConfig"
        const val PREFS = "streambd_notices"
        const val K_CFG = "cfg"
        const val K_DISMISSED_ANN = "dismissed_ann"
        const val K_DISMISSED_UPDATE = "dismissed_update"
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = "application/json; charset=utf-8".toMediaType()

    private val _state = MutableStateFlow(compute())
    val state: StateFlow<NoticeState> = _state.asStateFlow()

    private fun baseUrl(): String = try {
        String(Base64.decode(BuildConfig.ENCODED_API, Base64.DEFAULT), Charsets.UTF_8).trimEnd('/')
    } catch (e: Exception) {
        ""
    }

    /** সার্ভার থেকে নতুন কনফিগ আনে। সফল হলে true; ব্যর্থ হলে আগের মান অপরিবর্তিত। */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrl()
        if (base.isBlank()) return@withContext false
        try {
            val body = JSONObject().put("versionCode", BuildConfig.VERSION_CODE).toString()
            val req = Request.Builder().url("$base/config").post(body.toRequestBody(json)).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val o = JSONObject(text)
                if (!resp.isSuccessful || !o.optBoolean("ok", false)) return@withContext false
                prefs.edit().putString(K_CFG, text).apply()
            }
            _state.value = compute()
            true
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "config refresh failed: ${e.message}")
            false
        }
    }

    fun dismissAnnouncement(id: Long) {
        val set = prefs.getStringSet(K_DISMISSED_ANN, emptySet()).orEmpty().toMutableSet()
        set += id.toString()
        prefs.edit().putStringSet(K_DISMISSED_ANN, set).apply()
        _state.value = compute()
    }

    /** "পরে" চাপলে ওই latestVersionCode আর দেখানো হয় না (আরও নতুন এলে আবার দেখাবে)। */
    fun dismissUpdate() {
        val cfg = load() ?: return
        prefs.edit().putInt(K_DISMISSED_UPDATE, cfg.optInt("latestVersionCode", 0)).apply()
        _state.value = compute()
    }

    private fun load(): JSONObject? = try {
        prefs.getString(K_CFG, null)?.let { JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    private fun compute(): NoticeState {
        val cfg = load() ?: return NoticeState()
        val current = BuildConfig.VERSION_CODE
        val min = cfg.optInt("minVersionCode", 0)
        val latest = cfg.optInt("latestVersionCode", 0)

        val update = when {
            min > current -> UpdateInfo(
                cfg.optString("latestVersionName"), cfg.optString("updateNotes"), cfg.optString("updateUrl"), force = true,
            )
            latest > current && prefs.getInt(K_DISMISSED_UPDATE, 0) < latest -> UpdateInfo(
                cfg.optString("latestVersionName"), cfg.optString("updateNotes"), cfg.optString("updateUrl"), force = false,
            )
            else -> null
        }

        val dismissed = prefs.getStringSet(K_DISMISSED_ANN, emptySet()).orEmpty()
        var ann: Announcement? = null
        cfg.optJSONArray("announcements")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val item = Announcement(
                    id = a.optLong("id"), title = a.optString("title"), message = a.optString("message"),
                    level = a.optString("level", "info"), dismissible = a.optBoolean("dismissible", true),
                )
                if (item.dismissible && item.id.toString() in dismissed) continue
                ann = item
                break
            }
        }
        return NoticeState(update = update, announcement = ann)
    }
}
