package com.streambd.iptv.util

/**
 * লগে URL লেখার আগে গোপন অংশ (Xtream ইউজারনেম/পাসওয়ার্ড পথে, টোকেন/কুয়েরি) বাদ দেয়:
 * `https://host/.../lastSegment` (শেষ অংশের এক্সটেনশন সহ, কুয়েরি ছাড়া)।
 */
object LogSafe {
    fun url(raw: String?): String {
        if (raw.isNullOrBlank()) return "-"
        val noQuery = raw.substringBefore('?').substringBefore('#').substringBefore('|')
        val scheme = noQuery.substringBefore("://", "")
        val rest = noQuery.substringAfter("://", noQuery)
        val host = rest.substringBefore('/').substringAfterLast('@')
        val last = rest.substringAfter('/', "").trimEnd('/').substringAfterLast('/')
        val ext = last.substringAfterLast('.', "").takeIf { it.length in 1..5 }
        return buildString {
            if (scheme.isNotEmpty()) append(scheme).append("://")
            append(host)
            if (ext != null) append("/.../*.").append(ext) else if (last.isNotEmpty()) append("/...")
        }
    }
}
