package com.streambd.iptv.player.cenc

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager

/**
 * Fake DrmSessionManager — software CENC decryptor-এর জন্য।
 *
 * DataSource লেয়ারে samples আগেই clear হয়ে আসে, তাই renderer-এর কোনো
 * DRM session দরকার নেই। acquireSession() = null আর CRYPTO_TYPE_NONE
 * ExoPlayer-কে বলে "এই ফরম্যাট plain, সরাসরি ডিকোড করো"।
 */
@UnstableApi
class PassthroughDrmSessionManager : DrmSessionManager {

    override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) {}
    override fun prepare() {}
    override fun release() {}

    override fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
    ): DrmSession? = null

    override fun getCryptoType(format: Format): Int = C.CRYPTO_TYPE_NONE
}
