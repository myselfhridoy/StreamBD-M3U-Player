package com.streambd.iptv.player.cenc

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaCrypto
import java.util.UUID

/**
 * Fake DrmSessionManager — CDM ছাড়া device-এ DRM-labeled stream চালানোর জন্য।
 *
 * Manifest-এ ContentProtection থাকলেও ExoPlayer এটা accept করে। Session open হয়,
 * কিন্তু getMediaCrypto() null ফেরে — ফলে MediaCodec clear mode-এ চলে।
 * আসল sample-level decryption DataSource করে।
 */
@UnstableApi
class PassthroughDrmSessionManager : DrmSessionManager {

    private val session: DrmSession = PassthroughSession()

    override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) {}
    override fun prepare() {}
    override fun release() {}

    override fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
    ): DrmSession = session

    override fun getCryptoType(format: Format): Int = C.CRYPTO_TYPE_FRAMEWORK

    private class PassthroughSession : DrmSession {
        override fun getState(): Int = DrmSession.STATE_OPENED_WITH_KEYS
        override fun getError(): DrmSession.DrmSessionException? = null
        override fun getSchemeUuid(): UUID? = C.CLEARKEY_UUID
        override fun getMediaCrypto(): ExoMediaCrypto? = null
        override fun requiresSecureDecoderComponent(mimeType: String): Boolean = false
        override fun queryKeyStatus(): Map<String, String>? = null
        override fun acquire(eventDispatcher: DrmSessionEventListener.EventDispatcher?) {}
        override fun release(eventDispatcher: DrmSessionEventListener.EventDispatcher?) {}
        override fun getOfflineLicenseKeySetId(): ByteArray? = null
    }
}
