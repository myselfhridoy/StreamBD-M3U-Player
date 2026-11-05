package com.streambd.iptv.player

import com.streambd.iptv.data.db.ChannelEntity

data class PlayerUiState(
    val current: ChannelEntity? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    // ★ নতুন: সিক স্টেপ (ডিফল্ট ১০ সেকেন্ড)
    val seekStepMs: Long = 10_000L,
    val isFullscreen: Boolean = false,
    val aspectMode: AspectMode = AspectMode.STRETCH,
    /** এখন ভলিউম সিস্টেম সামলায়। এই দুটো শুধু পুরনো UI কোডের জন্য রাখা, সবসময় ডিফল্ট থাকে। */
    val volumeOverlayVisible: Boolean = false,
    val volumeLevel: Float = 1f,
    val error: String? = null,
    val tracks: TrackInfo = TrackInfo.empty(),
    /** ডিকোড হওয়া ভিডিওর আসল সাইজ। ভিডিও শুরুর আগে 0। */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** পিক্সেল অনুপাত ধরে প্রদর্শন অনুপাত (যেমন 1.777 = 16:9)। অজানা হলে 0। */
    val videoAspect: Float = 0f,
    // ★ স্ট্রিম তথ্য ওভারলের জন্য
    /** যে ডিকোডার ভিডিও চালাচ্ছে (যেমন "OMX.amlogic.avc.decoder.awesome")। */
    val videoDecoder: String? = null,
    /** যে ডিকোডার অডিও চালাচ্ছে। নামে "ffmpeg" থাকলে FFmpeg ব্যবহার হচ্ছে। */
    val audioDecoder: String? = null,
    /** এই স্ট্রিম শুরুর পর মোট বাদ পড়া ফ্রেম। */
    val droppedFrames: Int = 0,
    /** DRM থাকলে তার ধরন ও ডিভাইস সাপোর্ট। */
    val drmInfo: String? = null,
)
