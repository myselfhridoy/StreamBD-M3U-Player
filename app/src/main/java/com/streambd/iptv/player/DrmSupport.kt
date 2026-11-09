package com.streambd.iptv.player

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
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
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
                val base = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(drm.multiSession)
                    // শুধু অংশ এনক্রিপ্টেড স্ট্রিমে (যেমন ক্লিয়ার অডিও + এনক্রিপ্টেড ভিডিও) কী আসার আগেই ক্লিয়ার অংশ চলে
                    .setPlayClearSamplesWithoutKeys(true)
                    .build(LocalMediaDrmCallback(json))
                // ম্যানিফেস্টে ClearKey PSSH না থাকলেও (শুধু Widevine/PlayReady/mp4protection) চলার জন্য
                // আমাদের কী-র KID থেকে বানানো PSSH ইনজেক্ট করি।
                val kids = ClearKeys.kidsOf(json)
                return if (kids.isEmpty()) base
                else ClearKeyInjectingDrmSessionManager(base, ClearKeys.syntheticDrmInitData(kids))
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

    /** লাইসেন্স JSON-এর `keys[].kid` থেকে ১৬ বাইটের KID তালিকা। */
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

    /** ISO/IEC 23001-7 v1 PSSH box (শুধু KID তালিকা, ডেটা নেই)। */
    fun buildPsshBox(systemUuid: UUID, kids: List<ByteArray>): ByteArray {
        val size = 36 + 16 * kids.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(size)
        buf.put("pssh".toByteArray(Charsets.US_ASCII))
        buf.putInt(0x01000000) // version 1, flags 0
        buf.putLong(systemUuid.mostSignificantBits)
        buf.putLong(systemUuid.leastSignificantBits)
        buf.putInt(kids.size)
        kids.forEach { buf.put(it) }
        buf.putInt(0) // data size
        return buf.array()
    }

    /** ClearKey ও Common PSSH দুটোই (সব KID একসাথে, একাধিক হলে প্রতিটার আলাদাও)। */
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

/**
 * প্রতিটি ফরম্যাটের drmInitData-তে আমাদের তৈরি ClearKey/Common PSSH সবার আগে বসায়, যাতে ম্যানিফেস্টে
 * ClearKey ঘোষণা না থাকলেও DefaultDrmSessionManager ব্যবহারযোগ্য scheme data পায়।
 * ম্যানিফেস্টের নিজস্ব ডেটা (খালি placeholder বাদে) পরে যুক্ত থাকে।
 */
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
                // ডেটাহীন placeholder (শুধু default_KID) বাদ
                if ((s.matches(C.COMMON_PSSH_UUID) || s.matches(C.CLEARKEY_UUID)) && s.data == null) continue
                merged += s
            }
        }
        return format.buildUpon()
            .setDrmInitData(DrmInitData(original?.schemeType ?: C.CENC_TYPE_cenc, merged))
            .build()
    }
}

