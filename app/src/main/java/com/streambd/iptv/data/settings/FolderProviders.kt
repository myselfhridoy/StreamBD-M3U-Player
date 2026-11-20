package com.streambd.iptv.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * কোন প্লেলিস্ট (provider) Live TV-তে `›`-ফোল্ডার হয়ে দেখাবে। প্লেলিস্টের প্রথম লাইনে
 * `#EXTM3U x-sb-folders="1"` থাকলে সিঙ্কের সময় এখানে নথিভুক্ত হয়; না থাকলে সরে যায়।
 * (Add-on ও অফিসিয়াল সোর্স সবসময় ফোল্ডার-সক্ষম, তাদের এখানে রাখা লাগে না।)
 */
@Singleton
class FolderProviders @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("streambd_folders", Context.MODE_PRIVATE)
    private val _ids = MutableStateFlow(load())
    val ids: StateFlow<Set<Long>> = _ids.asStateFlow()

    private fun load(): Set<Long> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun set(providerId: Long, on: Boolean) {
        if (providerId <= 0L) return
        val cur = _ids.value
        val next = if (on) cur + providerId else cur - providerId
        if (next == cur) return
        prefs.edit().putStringSet(KEY, next.map { it.toString() }.toSet()).apply()
        _ids.value = next
    }

    private companion object { const val KEY = "ids" }
}
