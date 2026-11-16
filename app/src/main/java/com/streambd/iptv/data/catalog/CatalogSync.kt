package com.streambd.iptv.data.catalog

import android.util.Base64
import android.util.Log
import com.streambd.iptv.BuildConfig
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** সার্ভার থেকে চ্যানেল ক্যাটালগ আনে। ভার্সন একই থাকলে সার্ভার ছোট উত্তর দেয়। */
@Singleton
class CatalogSync @Inject constructor(
    private val activation: ActivationRepository,
    private val store: CatalogStore,
    private val http: OkHttpClient,
) {
    private companion object { const val TAG = "CatalogSync" }
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun baseUrl(): String = try {
        String(Base64.decode(BuildConfig.ENCODED_API, Base64.DEFAULT), Charsets.UTF_8).trimEnd('/')
    } catch (e: Exception) { "" }

    /** ক্যাটালগ বদলালে true (তখন অফিসিয়াল প্লেলিস্ট নতুন করে সিঙ্ক করতে হবে)। ব্যর্থ হলে false, আগেরটা থাকে। */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        try {
            val active = activation.current() as? ActivationState.Active ?: return@withContext false
            if (active.code == ActivationRepository.LOCAL_CODE || active.code == "DEV") return@withContext false
            val base = baseUrl()
            if (base.isBlank()) return@withContext false
            val body = JSONObject()
                .put("code", active.code).put("deviceId", activation.deviceId()).put("version", store.version)
                .toString()
            val req = Request.Builder().url("$base/catalog").post(body.toRequestBody(json)).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext false
                val o = JSONObject(text)
                if (!o.optBoolean("ok", false) || o.optBoolean("unchanged", false)) return@withContext false
                store.save(text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "catalog refresh failed: ${e.message}")
            false
        }
    }
}
