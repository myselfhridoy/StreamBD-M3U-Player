package com.streambd.iptv.data.activation

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

data class ActivationResponse(
    val code: String,
    val expiresAt: Long,
    val playlists: List<PlaylistData>,
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
                                "revoked" in m || "expired" in m || "suspended" in m -> true
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
                    playlists = playlists
                )
            }
        }
}
