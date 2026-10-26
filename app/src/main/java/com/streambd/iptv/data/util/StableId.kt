package com.streambd.iptv.data.util

private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
private const val FNV_PRIME = 1099511628211L

/**
 * কোনো key থেকে স্থিতিশীল, ধনাত্মক ৬৪-বিট id বানায় (FNV-1a)।
 * একই key সবসময় একই id দেয়, ফলে re-import-এ favorite/lock মেলানো যায়।
 */
fun stableId(key: String): Long {
    var h = FNV_OFFSET
    for (b in key.toByteArray(Charsets.UTF_8)) {
        h = h xor (b.toLong() and 0xFF)
        h *= FNV_PRIME
    }
    val id = h and Long.MAX_VALUE
    return if (id == 0L) 1L else id
}
