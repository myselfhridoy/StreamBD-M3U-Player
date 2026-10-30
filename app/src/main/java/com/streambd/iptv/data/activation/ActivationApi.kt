package com.streambd.iptv.data.activation

import android.os.Build
import android.util.Base64
import android.util.Log
import com.streambd.iptv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ActivationApi"

// নতুন: serverTime যুক্ত করা হলো
data class ActivationResponse(
    val code: String,
    val expiresAt: Long,
    val playlists: List<PlaylistData>,
    val serverTime: Long = 0L,
)

class ActivationRejectedException(
    val reason: String,
    val shouldClearLocalState: Boolean,
    val httpCode: Int,
) : Exception(reason)

class ActivationNetworkException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

@Singleton
class ActivationApi @Inject constructor(private val http: OkHttpClient) {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // 🔒 Base64 এনকোড করা URL ডিকোড করার ফাংশন
    private fun getBaseUrl(): String {
        return try {
            val decoded = Base64.decode(BuildConfig.ENCODED_API, Base64.DEFAULT)
            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode API URL", e)
            ""
        }
    }

    // activate এবং validate দুটোই এই কমন ফাংশনটি কল করবে
    suspend fun activate(code: String, deviceId: String) = call("activate", code, deviceId)
    suspend fun validate(code: String, deviceId: String) = call("validate", code, deviceId)

    private suspend fun call(path: String, code: String, deviceId: String): ActivationResponse =
        withContext(Dispatchers.IO) {
            // ডিভাইসের আসল নাম বের করা (Unknown Device ফিক্স)
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

            val payload = JSONObject()
                .put("code", code)
                .put("deviceId", deviceId)
                .put("deviceName", deviceName) // সার্ভারে ডিভাইসের নাম পাঠানো হচ্ছে
                .toString()

            val request = Request.Builder()
                // path ভেরিয়েবল ব্যবহার করা হলো (activate অথবা validate)
                .url("${getBaseUrl().trimEnd('/')}/$path")
                .post(payload.toRequestBody(jsonType))
                .header("Accept", "application/json")
                .build()

            val response = try {
                http.newCall(request).execute()
            } catch (e: UnknownHostException) {
                throw ActivationNetworkException("Cannot reach the activation server.", e)
            } catch (e: IOException) {
                throw ActivationNetworkException("Network error. Please try again.", e)
            }

            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                val serverError = runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }

                if (!resp.isSuccessful) {
                    Log.w(TAG, "activate failed: HTTP ${resp.code} body=$body")
                    if (resp.code >= 500) throw ActivationNetworkException("Server error (${resp.code}).")

                    val message = serverError ?: "Server rejected activation (${resp.code})"
                    val clear = when {
                        resp.code == 404 -> true
                        resp.code == 403 -> {
                            val m = message.lowercase()
                            when {
                                // "not activated" চেক যুক্ত করা হলো
                                "revoked" in m || "expired" in m || "suspended" in m || "not activated" in m -> true
                                else -> false
                            }
                        }
                        else -> false
                    }
                    throw ActivationRejectedException(message, clear, resp.code)
                }

                val json = try { JSONObject(body) } catch (e: Exception) {
                    throw ActivationNetworkException("Unexpected response from server.")
                }

                if (!json.optBoolean("ok", false)) {
                    throw ActivationRejectedException(json.optString("error", "Invalid activation code"), false, resp.code)
                }

                val playlists = mutableListOf<PlaylistData>()
                val playlistsArray = json.optJSONArray("playlists")
                if (playlistsArray != null) {
                    for (i in 0 until playlistsArray.length()) {
                        val obj = playlistsArray.getJSONObject(i)
                        playlists.add(
                            PlaylistData(
                                name = obj.optString("name", "Playlist"),
                                m3uUrl = obj.optString("m3u_url", ""),
                                epgUrl = obj.optString("epg_url", "")
                            )
                        )
                    }
                }

                // পুরনো অ্যাপের জন্য ফলব্যাক (যদি সার্ভার থেকে অ্যারে না আসে)
                if (playlists.isEmpty()) {
                    val fallbackUrl = json.optString("playlistUrl", "")
                    if (fallbackUrl.isNotBlank()) {
                        playlists.add(PlaylistData("Default", fallbackUrl, json.optString("epgUrl", "")))
                    }
                }

                ActivationResponse(
                    code = code,
                    expiresAt = json.optLong("expiresAt", 0L),
                    playlists = playlists,
                    serverTime = json.optLong("serverTime", 0L), // ★ নতুন: সার্ভারের সময় প্যার্স করা হলো
                )
            }
        }
}
