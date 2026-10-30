package com.streambd.iptv.player

import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * ClearKey (কী সরাসরি দেওয়া) হলে নেটওয়ার্কে লাইসেন্স না চেয়ে [LocalMediaDrmCallback] দেয়।
 * বাকি স্কিম (Widevine, PlayReady, URL-ভিত্তিক ClearKey) ExoPlayer-এর ডিফল্ট পথে যায়।
 */
@UnstableApi
class AppDrmProvider(licenseHttpFactory: DataSource.Factory) : DrmSessionManagerProvider {

    private val clearKeyJson = ConcurrentHashMap<String, ByteArray>()

    private val fallback = DefaultDrmSessionManagerProvider().apply {
        setDrmHttpDataSourceFactory(licenseHttpFactory)
    }

    fun setClearKeys(mediaId: String, json: ByteArray) {
        clearKeyJson[mediaId] = json
    }

    override fun get(mediaItem: MediaItem): DrmSessionManager {
        val drm = mediaItem.localConfiguration?.drmConfiguration
            ?: return DrmSessionManager.DRM_UNSUPPORTED

        if (drm.scheme == C.CLEARKEY_UUID) {
            val json = clearKeyJson[mediaItem.mediaId]
            if (json != null) {
                return DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(drm.multiSession)
                    .build(LocalMediaDrmCallback(json))
            }
        }
        return fallback.get(mediaItem)
    }
}

object ClearKeys {

    /**
     * লাইসেন্স স্ট্রিং থেকে ClearKey JSON (লাইসেন্স-রেসপন্স) বানায়। না পারলে null।
     * সমর্থিত: "kid:key", "kid1:key1,kid2:key2" (hex বা base64url), অথবা তৈরি {"keys":[...]} JSON।
     */
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

    /** ১৬ বাইটের hex (৩২ অক্ষর) বা ইতোমধ্যে base64/base64url (২২ অক্ষর) → base64url (প্যাডিং ছাড়া)। */
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
