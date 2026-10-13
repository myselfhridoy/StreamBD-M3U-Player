package com.streambd.iptv.data.activation

import com.streambd.iptv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ActivationResponse(
    val code: String,
    val expiresAt: Long,
    val playlistUrl: String,
    val epgUrl: String,
)

@Singleton
class ActivationApi @Inject constructor(private val http: OkHttpClient) {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun activate(code: String, deviceId: String): ActivationResponse =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("code", code)
                .put("deviceId", deviceId)
                .toString()
            val request = Request.Builder()
                .url("${BuildConfig.ACTIVATION_API.trimEnd('/')}/activate")
                .post(payload.toRequestBody(jsonType))
                .header("Accept", "application/json")
                .build()

            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Server ${response.code}: ${body.take(200)}")
                }

                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) {
                    throw IllegalStateException(json.optString("error", "Invalid activation code"))
                }
                ActivationResponse(
                    code = code,
                    expiresAt = json.optLong("expiresAt", 0L),
                    playlistUrl = json.optString("playlistUrl", ""),
                    epgUrl = json.optString("epgUrl", ""),
                )
            }
        }
}