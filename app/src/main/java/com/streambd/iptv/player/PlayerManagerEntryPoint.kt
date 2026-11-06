package com.streambd.iptv.player

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Composable (যেমন FullscreenPlayer) থেকে সরাসরি PlayerManager পেতে, যাতে প্রতিটি স্ক্রিনের কল বদলাতে না হয়। */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlayerManagerEntryPoint {
    fun playerManager(): PlayerManager
}
