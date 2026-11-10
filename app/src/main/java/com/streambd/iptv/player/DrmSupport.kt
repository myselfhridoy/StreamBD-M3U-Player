package com.streambd.iptv.player

import android.media.MediaDrm
import android.os.Looper
import android.util.Base64
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import com.streambd.iptv.player.cenc.PassthroughDrmSessionManager
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
class AppDrmProvider(licenseHttpFactory: DataSource.Factory) : DrmSessionManagerProvider {

    private val clearKeyJson = ConcurrentHashMap<String, ByteArray>()

    private val fallback = DefaultDrmSessionManagerProvider().apply {
        setDrmHttpDataSourceFactory(licenseHttpFactory)
    }

    private var passthroughManager: PassthroughDrmSessionManager? = null

    fun setClearKeys(mediaId: String, json: ByteArray) {
        clearKeyJson[mediaId] = json
    }

    /** CENC fallback হলে এই manager return করব। */
    fun usePassthrough(): PassthroughDrmSessionManager {
        return passthroughManager ?: PassthroughDrmSessionManager().also { passthroughManager = it }
    }

    override fun get(mediaItem: MediaItem): DrmSessionManager {
        val drm = mediaItem.localConfiguration?.drmConfiguration
            ?: return DrmSessionManager.DRM_UNSUPPORTED

        if (drm.scheme == C.CLEARKEY_UUID) {
            val json = clearKeyJson[mediaItem.mediaId]
            if (json != null) {
                // CDM support চেক
                val supported = runCatching {
                    MediaDrm.isCryptoSchemeSupported(C.CLEARKEY_UUID)
                }.getOrDefault(false)

                if (!supported) {
                    // CDM নেই → Passthrough manager (DataSource decrypt করবে)
                    return usePassthrough()
                }

                // Normal CDM path
                val base = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(drm.multiSession)
                    .setPlayClearSamplesWithoutKeys(true)
                    .build(LocalMediaDrmCallback(json))
                val kids = ClearKeys.kidsOf(json)
                return if (kids.isEmpty()) base
                else ClearKeyInjectingDrmSessionManager(base, ClearKeys.syntheticDrmInitData(kids))
            }
        }
        return fallback.get(mediaItem)
    }
}

object ClearKeys {

    fun toLicenseJson(license: String): ByteArray? {
        val text = license.trim()
        if (text.isEmpty()) return null

        if (text.startsWith("{")) {
            return runCatching {
                require(JSONObject(text).optJSONArray("keys")?.length() ?: 0 > 0)
                text.toByteArray()
            }.getOrNull()
        }

        val keys = JSONArray()
        for (pair in text.split(',', ';', '\n')) {
            val p = pair.trim()
            if (p.isEmpty()) continue
            val parts = p.split(':')
            if (parts.size != 2) return null
            val kid = toBase64Url(parts[0]) ?: return null
            val key = toBase64Url(parts[1]) ?: return null
            keys.put(JSONObject().put("kty", "oct").put("kid", kid).put("k", key))
        }
        if (keys.length() == 0) return null
        return JSONObject().put("keys", keys).put("type", "temporary").toString().toByteArray()
    }

    fun kidsOf(json: ByteArray): List<ByteArray> = runCatching {
        val arr = JSONObject(String(json, Charsets.UTF_8)).getJSONArray("keys")
        buildList {
            for (i in 0 until arr.length()) {
                val kid = arr.getJSONObject(i).optString("kid")
                if (kid.isEmpty()) continue
                val bytes = Base64.decode(kid, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                if (bytes.size == 16) add(bytes)
            }
        }
    }.getOrDefault(emptyList())

    /**
     * ClearKey JSON থেকে key bytes। CENC decryption-এ ব্যবহার হবে।
     */
    fun parseKeyMap(json: ByteArray): ByteArray? = runCatching {
        val arr = JSONObject(String(json, Charsets.UTF_8)).getJSONArray("keys")
        if (arr.length() == 0) return null
        // প্রথম key-টাই ব্যবহার (single-key stream)
        val obj = arr.getJSONObject(0)
        val k = obj.optString("k")
        if (k.isEmpty()) return null
        Base64.decode(k, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }.getOrNull()

    /**
     * ClearKey license URL থেকে key bytes বের করে, যেখানে kid+key query
     * parameter হিসেবে থাকে — যেমন:
     *   https://example.com/clearkey/results.php?keyid=<hex>&key=<hex>
     *
     * এটা CDM-এর মতো network round-trip করে না; URL-এই key থাকে, তাই
     * সরাসরি parse করি। TX3 Mini-র software decryptor এই bytes ব্যবহার করে।
     *
     * @return 16-byte AES key, অথবা null যদি URL-এ keyid/key না থাকে।
     */
    fun parseKeyMapFromLicenseUrl(licenseUrl: String): ByteArray? {
        if (licenseUrl.isBlank()) return null
        return runCatching {
            val uri = android.net.Uri.parse(licenseUrl)
            val keyHex = uri.getQueryParameter("key")
                ?: uri.getQueryParameter("k")
                ?: uri.getQueryParameter("content_key")
                ?: return null
            hexToBytes16(keyHex) ?: return null
        }.getOrNull()
    }

    /**
     * URL থেকে পাওয়া kid+key hex দিয়ে Android-compatible ClearKey JSON
     * বানায় (drmProvider.setClearKeys()-এ ব্যবহারের জন্য)।
     */
    fun buildLicenseJsonFromUrl(licenseUrl: String): ByteArray? {
        if (licenseUrl.isBlank()) return null
        return runCatching {
            val uri = android.net.Uri.parse(licenseUrl)
            val kidHex = uri.getQueryParameter("keyid")
                ?: uri.getQueryParameter("kid")
                ?: uri.getQueryParameter("key_id")
                ?: return null
            val keyHex = uri.getQueryParameter("key")
                ?: uri.getQueryParameter("k")
                ?: uri.getQueryParameter("content_key")
                ?: return null
            val kidBytes = hexToBytes16(kidHex) ?: return null
            val keyBytes = hexToBytes16(keyHex) ?: return null
            val kidB64 = Base64.encodeToString(
                kidBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            val keyB64 = Base64.encodeToString(
                keyBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            JSONObject()
                .put(
                    "keys",
                    JSONArray().put(
                        JSONObject()
                            .put("kty", "oct")
                            .put("kid", kidB64)
                            .put("k", keyB64),
                    ),
                )
                .put("type", "temporary")
                .toString()
                .toByteArray()
        }.getOrNull()
    }

    private fun hexToBytes16(hex: String): ByteArray? {
        val clean = hex.trim()
            .replace("urn:uuid:", "")
            .replace("-", "")
            .replace(" ", "")
            .lowercase()
        if (clean.length != 32) return null
        if (!clean.all { Character.digit(it, 16) >= 0 }) return null
        return ByteArray(16) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun buildPsshBox(systemUuid: UUID, kids: List<ByteArray>): ByteArray {
        val size = 36 + 16 * kids.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size)
        buf.put("pssh".toByteArray(Charsets.US_ASCII))
        buf.putInt(0x01000000)
        buf.putLong(systemUuid.mostSignificantBits)
        buf.putLong(systemUuid.leastSignificantBits)
        buf.putInt(kids.size)
        kids.forEach { buf.put(it) }
        buf.putInt(0)
        return buf.array()
    }

    fun syntheticDrmInitData(kids: List<ByteArray>): DrmInitData {
        val list = ArrayList<DrmInitData.SchemeData>()
        list += DrmInitData.SchemeData(C.CLEARKEY_UUID, "video/mp4", buildPsshBox(C.CLEARKEY_UUID, kids))
        list += DrmInitData.SchemeData(C.COMMON_PSSH_UUID, "video/mp4", buildPsshBox(C.COMMON_PSSH_UUID, kids))
        if (kids.size > 1) {
            for (kid in kids) {
                list += DrmInitData.SchemeData(C.CLEARKEY_UUID, "video/mp4", buildPsshBox(C.CLEARKEY_UUID, listOf(kid)))
                list += DrmInitData.SchemeData(C.COMMON_PSSH_UUID, "video/mp4", buildPsshBox(C.COMMON_PSSH_UUID, listOf(kid)))
            }
        }
        return DrmInitData(list)
    }

    private fun toBase64Url(value: String): String? {
        val v = value.trim()
        if (v.length == 32 && v.all { Character.digit(it, 16) >= 0 }) {
            val bytes = ByteArray(16) { i -> v.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
        if (Regex("[A-Za-z0-9+/_-]{22}={0,2}").matches(v)) {
            return v.trimEnd('=').replace('+', '-').replace('/', '_')
        }
        return null
    }
}

@UnstableApi
private class ClearKeyInjectingDrmSessionManager(
    private val delegate: DrmSessionManager,
    private val synthetic: DrmInitData,
) : DrmSessionManager {

    override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) = delegate.setPlayer(playbackLooper, playerId)
    override fun prepare() = delegate.prepare()
    override fun release() = delegate.release()

    override fun preacquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
    ): DrmSessionManager.DrmSessionReference = delegate.preacquireSession(eventDispatcher, patch(format))

    override fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
    ): DrmSession? = delegate.acquireSession(eventDispatcher, patch(format))

    override fun getCryptoType(format: Format): Int {
        val type = delegate.getCryptoType(patch(format))
        return if (type == C.CRYPTO_TYPE_UNSUPPORTED) C.CRYPTO_TYPE_FRAMEWORK else type
    }

    private fun patch(format: Format): Format {
        val original = format.drmInitData
        val merged = ArrayList<DrmInitData.SchemeData>()
        for (i in 0 until synthetic.schemeDataCount) merged += synthetic.get(i)
        if (original != null) {
            for (i in 0 until original.schemeDataCount) {
                val s = original.get(i)
                if ((s.matches(C.COMMON_PSSH_UUID) || s.matches(C.CLEARKEY_UUID)) && s.data == null) continue
                merged += s
            }
        }
        return format.buildUpon()
            .setDrmInitData(DrmInitData(original?.schemeType ?: C.CENC_TYPE_cenc, merged))
            .build()
    }
}
