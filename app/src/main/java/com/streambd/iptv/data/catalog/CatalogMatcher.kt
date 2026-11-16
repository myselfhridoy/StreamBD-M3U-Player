package com.streambd.iptv.data.catalog

import com.streambd.iptv.data.m3u.ChannelKind
import com.streambd.iptv.data.m3u.ParsedChannel
import java.util.Locale

data class CatalogChannel(
    val id: Long,
    val name: String,
    val number: Int,
    val group: String,
    val logo: String,
    val tvgId: String,
    val aliases: List<String>,
    val event: Boolean,
)

/** [groups]: সার্ভারের ক্রম অনুযায়ী গ্রুপের নাম। */
data class CatalogData(val version: Long, val groups: List<String>, val channels: List<CatalogChannel>)

/**
 * সার্ভারের চ্যানেল ক্যাটালগ দিয়ে প্লেলিস্টের চ্যানেল চেনা।
 * নিয়ম Worker (index.js) এর buildMatcher এর সাথে হুবহু এক; একটা বদলালে অন্যটাও বদলান।
 *
 * মিলের ক্রম: ১) ইভেন্ট রুলের হুবহু নাম  ২) স্বাভাবিক করা নাম/অ্যালিয়াস (HD সহ)
 * ৩) HD/SD বাদ দিয়ে মিল (একাধিক হলে ইনকামিং নামের মান দেখে বাছাই, তবু অস্পষ্ট হলে মিল নয়)।
 */
class CatalogMatcher(val data: CatalogData) {
    class Match(val channel: CatalogChannel, val event: Boolean)

    companion object {
        const val OTHERS = "Others"
        private val PAREN = Regex("""\([^)]*\)|\[[^\]]*\]""")
        private val QUAL = setOf("hd", "fhd", "sd", "uhd", "4k")
        private val HDISH = setOf("hd", "fhd", "uhd", "4k")

        private fun isWordChar(ch: Char): Boolean {
            if (ch.isLetter()) return true
            return when (Character.getType(ch)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
                Character.DECIMAL_DIGIT_NUMBER.toInt() -> true
                else -> false
            }
        }

        fun norm(s: String): String {
            val t = PAREN.replace(s.lowercase(Locale.ROOT), " ")
            val sb = StringBuilder(t.length)
            var gap = false
            for (ch in t) {
                if (isWordChar(ch)) {
                    if (gap && sb.isNotEmpty()) sb.append(' ')
                    gap = false
                    sb.append(ch)
                } else gap = true
            }
            return sb.toString()
        }

        private fun tokens(n: String) = n.split(' ').filter { it.isNotEmpty() }
        private fun strip(n: String) = tokens(n).filter { it !in QUAL }.joinToString(" ")
        private fun hasHd(n: String) = tokens(n).any { it in HDISH }
        private fun hasQual(n: String) = tokens(n).any { it in QUAL }
    }

    private val exact = HashMap<String, CatalogChannel>()
    private val loose = HashMap<String, LinkedHashSet<CatalogChannel>>()
    private val events = HashMap<String, CatalogChannel>()
    private val rank = HashMap<String, Int>()

    init {
        data.groups.forEachIndexed { i, g -> rank.putIfAbsent(g, i) }
        val normal = data.channels.filter { !it.event }
            .sortedWith(compareBy<CatalogChannel>({ if (it.number == 0) 99999 else it.number }, { it.id }))
        for (c in normal) { // ১ম পাস: নামের কী আগে
            val k = norm(c.name)
            if (k.isNotEmpty()) exact.putIfAbsent(k, c)
        }
        for (c in normal) { // ২য় পাস: অ্যালিয়াস
            val keys = (listOf(c.name) + c.aliases).map { norm(it) }.filter { it.isNotEmpty() }
            for (k in keys) {
                exact.putIfAbsent(k, c)
                val s = strip(k)
                if (s.isNotEmpty()) loose.getOrPut(s) { LinkedHashSet() }.add(c)
            }
        }
        for (c in data.channels.filter { it.event }) {
            for (n in listOf(c.name) + c.aliases) {
                val k = n.trim().lowercase(Locale.ROOT)
                if (k.isNotEmpty()) events.putIfAbsent(k, c)
            }
        }
    }

    fun match(name: String): Match? {
        events[name.trim().lowercase(Locale.ROOT)]?.let { return Match(it, true) }
        val k = norm(name)
        if (k.isEmpty()) return null
        exact[k]?.let { return Match(it, false) }
        val set = loose[strip(k)] ?: return null
        if (set.isEmpty()) return null
        var cands = set.toList()
        if (cands.size > 1) {
            val wantHd = hasHd(k)
            cands = cands.filter { c ->
                val cn = norm(c.name)
                if (wantHd) hasHd(cn) else !hasQual(cn)
            }
        }
        return if (cands.size == 1) Match(cands[0], false) else null
    }

    /** ফোল্ডারের ক্রম: ক্যাটালগে না থাকলে null (শেষে যায়)। */
    fun groupRank(name: String): Int? = rank[name]

    /** ইমপোর্টের আগে চ্যানেল রূপান্তর: মিললে ক্যাটালগের নাম/লোগো/গ্রুপ/নম্বর, নইলে "Others"। শুধু Live। */
    fun apply(p: ParsedChannel, epgKey: (String) -> String): ParsedChannel {
        if (p.kind != ChannelKind.LIVE || p.isNestedPlaylist) return p
        val m = match(p.name) ?: return p.copy(groupTitle = OTHERS)
        val c = m.channel
        return if (m.event) {
            p.copy(
                groupTitle = c.group,
                logoUrl = p.logoUrl?.takeIf { it.isNotBlank() } ?: c.logo.ifBlank { null },
            )
        } else {
            p.copy(
                name = c.name,
                logoUrl = c.logo.ifBlank { null } ?: p.logoUrl,
                groupTitle = c.group,
                // EPG মিল আগের মতো থাকে: ক্যাটালগে tvg-id না থাকলে মূল নাম থেকেই কী তৈরি
                tvgId = c.tvgId.ifBlank { null } ?: p.tvgId?.takeIf { it.isNotBlank() } ?: epgKey(p.name),
                chno = if (c.number > 0) c.number else p.chno,
            )
        }
    }
}
