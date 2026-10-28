package com.streambd.iptv.data.util

private val noise = Regex(
    """\([^)]*\)|\[[^\]]*]|\b(?:fhd|uhd|hd|sd|4k|hevc|1080p|720p|480p)\b""",
)

private fun keep(c: Char): Boolean {
    if (c.isLetterOrDigit()) return true
    // বাংলা/হিন্দি স্বরচিহ্ন (combining mark) মুছলে নাম নষ্ট হয়
    val type = Character.getType(c)
    return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
}

/** "Star Plus HD" ও "star-plus" দুটোই "starplus" হয়। খালি হলে মূল নামের অক্ষর-সংখ্যা রাখে। */
fun normalizeChannelName(name: String): String {
    val lower = name.lowercase()
    val cleaned = noise.replace(lower, " ")
    val out = StringBuilder(cleaned.length)
    for (c in cleaned) if (keep(c)) out.append(c)
    if (out.isEmpty()) for (c in lower) if (keep(c)) out.append(c)
    return out.toString()
}

/** tvg-id না থাকা চ্যানেল ও XMLTV display-name মেলানোর সাধারণ চাবি। */
fun epgNameKey(name: String): String = "name:" + normalizeChannelName(name)
