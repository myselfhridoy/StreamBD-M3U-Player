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
    val playlistUrl: String,
    val epgUrl: String,
)

/**
 * Server said "no" — the activation is rejected.
 *
 * @param reason user-facing message from server (e.g. "Code has been revoked")
 * @param shouldClearLocalState true if this is a terminal state (revoked/expired/suspended).
 *                              False for recoverable rejections like "Device limit reached"
 *                              where destroying local state would only make things worse.
 * @param httpCode original HTTP status for logging
 */
class ActivationRejectedException(
    val reason: String,
    val shouldClearLocalState: Boolean,
    val httpCode: Int,
) : Exception(reason)

/** Network or 5xx — transient. Caller should keep existing local state and retry. */
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
                throw ActivationNetworkException(
                    "Cannot reach the activation server. Check your internet connection.", e
                )
            } catch (e: IOException) {
                throw ActivationNetworkException("Network error. Please try again.", e)
            }

            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                val serverError = runCatching { JSONObject(body).optString("error") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }

                if (!resp.isSuccessful) {
                    Log.w(TAG, "activate failed: HTTP ${resp.code} body=$body")

                    // 5xx = server-side problem → transient, retry
                    if (resp.code >= 500) {
                        throw ActivationNetworkException(
                            "Server error (${resp.code}). Please try again later."
                        )
                    }

                    // 4xx = server said "no". Decide if we should wipe local state.
                    val message = serverError ?: "Server rejected activation (${resp.code})"
                    val clear = when {
                        resp.code == 404 -> true          // "Invalid activation code" — gone
                        resp.code == 403 -> {
                            val m = message.lowercase()
                            when {
                                "revoked"      in m -> true   // admin revoked
                                "expired"      in m -> true   // time ran out
                                "suspended"    in m -> true   // user account disabled
                                "device limit" in m -> false  // recoverable: admin can reset
                                else -> false                 // unknown 403 → be safe
                            }
                        }
                        else -> false                     // other 4xx → be safe
                    }
                    throw ActivationRejectedException(message, clear, resp.code)
                }

                val json = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    Log.e(TAG, "activate: bad JSON body=$body", e)
                    throw ActivationNetworkException("Unexpected response from server.")
                }
                if (!json.optBoolean("ok", false)) {
                    // 200 OK but ok=false — treat as rejection, keep local state
                    val message = json.optString("error", "Invalid activation code")
                    throw ActivationRejectedException(message, false, resp.code)
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
