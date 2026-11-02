package com.streambd.iptv.data.ftp

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/** সার্ভারের ডিরেক্টরির একটি এন্ট্রি (ফোল্ডার বা ফাইল)। [url] পূর্ণ ও percent-encoded। */
data class FtpEntry(
    val name: String,
    val url: String,
    val isDir: Boolean,
    val sizeBytes: Long? = null,
    val modified: String? = null,
) {
    val ext: String get() = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
    val isVideo: Boolean get() = !isDir && ext in VIDEO_EXT
    /** ExoPlayer এই ফরম্যাট সাধারণত চালাতে পারে না। */
    val likelyUnsupported: Boolean get() = !isDir && ext in RISKY_EXT

    companion object {
        val VIDEO_EXT = setOf(
            "mp4", "mkv", "webm", "m4v", "mov", "ts", "m2ts", "mpg", "mpeg", "3gp",
            "avi", "wmv", "flv", "divx",
        )
        val RISKY_EXT = setOf("avi", "wmv", "flv", "divx")
    }
}

/**
 * h5ai (no-JS fallback টেবিল), Apache/nginx/lighttpd autoindex — সব একই কাজ করে:
 * পেজের লিংক থেকে ফোল্ডার ও ফাইল বের করা।
 */
object DirectoryParser {

    private val ROW_SPLIT = Regex("<tr[ >]", RegexOption.IGNORE_CASE)
    private val H5_ICON = Regex("""<img[^>]*\balt="([^"]*)"""")
    private val H5_LINK = Regex("""<td class="fb-n"><a href="([^"]*)">(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val H5_DATE = Regex("""<td class="fb-d">([^<]*)</td>""")
    private val H5_SIZE = Regex("""<td class="fb-s">([^<]*)</td>""")
    private val ANY_LINK = Regex(
        """<a\s[^>]*?href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TAGS = Regex("<[^>]*>")

    /** [pageUrl] হলো যে ডিরেক্টরির HTML এটি; লিংকগুলো এর সাপেক্ষে পূর্ণ URL হয়। */
    fun parse(html: String, pageUrl: String): List<FtpEntry> {
        val base = pageUrl.toHttpUrlOrNull() ?: return emptyList()
        val fromH5 = if ("fb-n" in html) parseH5ai(html, base) else emptyList()
        return if (fromH5.isNotEmpty() || "fb-n" in html) fromH5 else parseGeneric(html, base)
    }

    private fun parseH5ai(html: String, base: HttpUrl): List<FtpEntry> {
        val out = ArrayList<FtpEntry>()
        for (row in html.split(ROW_SPLIT)) {
            val link = H5_LINK.find(row) ?: continue
            val icon = H5_ICON.find(row)?.groupValues?.get(1).orEmpty()
            val href = unescape(link.groupValues[1])
            if (icon == "folder-parent" || href == ".." || href == "../") continue
            val url = base.resolve(href) ?: continue
            val isDir = icon.startsWith("folder") || href.endsWith("/")
            val name = unescape(stripTags(link.groupValues[2])).trim()
            if (name.isEmpty()) continue
            out += FtpEntry(
                name = name,
                url = normalize(url, isDir),
                isDir = isDir,
                sizeBytes = if (isDir) null else parseH5Size(H5_SIZE.find(row)?.groupValues?.get(1)),
                modified = H5_DATE.find(row)?.groupValues?.get(1)?.trim()?.ifEmpty { null },
            )
        }
        return out
    }

    private fun parseGeneric(html: String, base: HttpUrl): List<FtpEntry> {
        val out = LinkedHashMap<String, FtpEntry>()
        val basePath = base.encodedPath
        for (m in ANY_LINK.findAll(html)) {
            val href = unescape(m.groupValues[1]).trim()
            if (href.isEmpty() || href.startsWith("?") || href.startsWith("#") ||
                href.startsWith("mailto:", true) || href.startsWith("javascript:", true)
            ) continue
            val url = base.resolve(href) ?: continue
            if (url.host != base.host || url.port != base.port) continue
            val path = url.encodedPath
            // নিজের ভেতরের কিছু না হলে (parent, sort লিংক, অন্য জায়গা) বাদ
            if (!path.startsWith(basePath) || path == basePath) continue
            val isDir = path.endsWith("/")
            val shown = unescape(stripTags(m.groupValues[2])).trim().trimEnd('/')
            val name = shown.ifEmpty { decode(path.trimEnd('/').substringAfterLast('/')) }
            if (name.isEmpty() || name.equals("Parent Directory", true)) continue
            val key = normalize(url, isDir)
            out.getOrPut(key) { FtpEntry(name = name, url = key, isDir = isDir) }
        }
        return out.values.toList()
    }

    private fun normalize(url: HttpUrl, isDir: Boolean): String {
        val s = url.newBuilder().fragment(null).build().toString()
        return if (isDir && !s.endsWith("/")) "$s/" else s
    }

    /** h5ai-র আকার "2103435 KB" ধরনের। */
    private fun parseH5Size(raw: String?): Long? {
        val m = Regex("""^\s*([\d.]+)\s*([KMGT]?B?)\s*$""", RegexOption.IGNORE_CASE).find(raw.orEmpty()) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].uppercase()) {
            "K", "KB" -> 1L shl 10
            "M", "MB" -> 1L shl 20
            "G", "GB" -> 1L shl 30
            "T", "TB" -> 1L shl 40
            else -> 1L
        }
        return (n * mult).toLong()
    }

    private fun stripTags(s: String) = TAGS.replace(s, "")

    private fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun unescape(s: String): String {
        if ('&' !in s) return s
        return Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos|nbsp);""").replace(s) { m ->
            when (val g = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> {
                    val code = if (g.startsWith("#x")) g.substring(2).toIntOrNull(16) else g.substring(1).toIntOrNull()
                    if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
                }
            }
        }
    }
}
