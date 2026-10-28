package com.streambd.iptv.data.epg

import android.util.Xml
import com.streambd.iptv.data.db.ProgramEntity
import com.streambd.iptv.data.util.epgNameKey
import com.streambd.iptv.data.util.normalizeChannelName
import com.streambd.iptv.data.util.stableId
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

object XmltvParser {

    /**
     * প্রতিটা প্রোগ্রাম XMLTV channel id-র নামে এবং (থাকলে) সেই চ্যানেলের প্রতিটা display-name-এর
     * "name:…" চাবির নামে সেভ হয়, যেগুলো [accept] মানে।
     *
     * @param accept প্রোগ্রাম অবজেক্ট বানানোর আগেই বাদ দেওয়ার ফিল্টার (channel, start, end)।
     */
    fun parse(
        input: InputStream,
        accept: ((channel: String, start: Long, end: Long) -> Boolean)? = null,
        onProgram: (ProgramEntity) -> Unit,
    ) {
        openMaybeGzip(input).use { stream -> parseXml(stream, accept, onProgram) }
    }

    /** প্রথম ২ বাইট (1f 8b) দেখে gzip চেনা। এক্সটেনশন/header-এর ওপর নির্ভর করে না। */
    private fun openMaybeGzip(input: InputStream): InputStream {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
    }

    private fun targetsFor(
        channel: String?,
        start: Long,
        stop: Long,
        accept: ((String, Long, Long) -> Boolean)?,
        keysByChannel: Map<String, List<String>>,
    ): List<String> {
        if (channel.isNullOrBlank() || stop <= start) return emptyList()
        val out = ArrayList<String>(2)
        if (accept == null || accept(channel, start, stop)) out += channel
        keysByChannel[channel]?.forEach { key ->
            if (key !in out && (accept == null || accept(key, start, stop))) out += key
        }
        return out
    }

    private fun parseXml(
        input: InputStream,
        accept: ((String, Long, Long) -> Boolean)?,
        onProgram: (ProgramEntity) -> Unit,
    ) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val dates = DateParser()

        // <channel id="x"><display-name>A</display-name><display-name>B</display-name></channel>
        // সব <channel> থাকে <programme>-এর আগে (XMLTV নিয়ম), তাই এখানে আগে থেকেই ম্যাপ তৈরি।
        val keysByChannel = HashMap<String, MutableList<String>>()
        var channelId: String? = null
        var nameBuf: StringBuilder? = null

        var inProgramme = false
        var start = 0L
        var stop = 0L
        var targets: List<String> = emptyList()
        var title: StringBuilder? = null
        var desc: StringBuilder? = null
        var poster: String? = null
        var capturing: StringBuilder? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> channelId = parser.getAttributeValue(null, "id")
                    "display-name" -> if (channelId != null && !inProgramme) {
                        nameBuf = StringBuilder().also { capturing = it }
                    }
                    "programme" -> {
                        start = dates.parse(parser.getAttributeValue(null, "start"))
                        stop = dates.parse(parser.getAttributeValue(null, "stop"))
                        title = null
                        desc = null
                        poster = null
                        capturing = null
                        targets = targetsFor(
                            parser.getAttributeValue(null, "channel"),
                            start, stop, accept, keysByChannel,
                        )
                        inProgramme = targets.isNotEmpty()
                    }
                    // একাধিক ভাষার title/desc থাকলে শুধু প্রথমটা
                    "title" -> if (inProgramme && title == null) {
                        title = StringBuilder().also { capturing = it }
                    }
                    "desc" -> if (inProgramme && desc == null) {
                        desc = StringBuilder().also { capturing = it }
                    }
                    "icon" -> if (inProgramme && poster == null) {
                        poster = parser.getAttributeValue(null, "src")
                    }
                }

                XmlPullParser.TEXT -> capturing?.append(parser.text)

                XmlPullParser.END_TAG -> when (parser.name) {
                    "display-name" -> {
                        val id = channelId
                        val raw = nameBuf?.toString()?.trim().orEmpty()
                        if (id != null && raw.isNotEmpty() && normalizeChannelName(raw).isNotEmpty()) {
                            val list = keysByChannel.getOrPut(id) { ArrayList(2) }
                            val key = epgNameKey(raw)
                            if (key !in list) list += key
                        }
                        nameBuf = null
                        capturing = null
                    }
                    "channel" -> channelId = null
                    "title", "desc" -> capturing = null
                    "programme" -> {
                        if (inProgramme) {
                            val titleStr = title?.toString().orEmpty().trim()
                                .ifBlank { "No information available" }
                            val descStr = desc?.toString().orEmpty().trim()
                            for (target in targets) {
                                onProgram(
                                    ProgramEntity(
                                        id = stableId("$target|$start"),
                                        channelTvgId = target,
                                        start = start,
                                        end = stop,
                                        title = titleStr,
                                        desc = descStr,
                                        poster = poster,
                                    ),
                                )
                            }
                        }
                        inProgramme = false
                        targets = emptyList()
                        capturing = null
                    }
                }
            }
            event = parser.next()
        }
    }

    /**
     * XMLTV তারিখ: "20261027030000 +0600"। একটা Calendar পুনর্ব্যবহার হয়।
     * জোন না থাকলে UTC ধরা হয় (XMLTV নিয়ম)।
     */
    private class DateParser {
        private val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US)
        private val zoneRegex = Regex("""^([+-])(\d{2}):?(\d{2})""")

        fun parse(value: String?): Long {
            if (value.isNullOrBlank()) return 0L
            val v = value.trim()

            var i = 0
            while (i < v.length && v[i] in '0'..'9') i++
            if (i != 12 && i != 14) return 0L

            val year = v.substring(0, 4).toInt()
            val month = v.substring(4, 6).toInt()
            val day = v.substring(6, 8).toInt()
            val hour = v.substring(8, 10).toInt()
            val minute = v.substring(10, 12).toInt()
            val second = if (i == 14) v.substring(12, 14).toInt() else 0
            if (month !in 1..12 || day !in 1..31 || hour !in 0..23 ||
                minute !in 0..59 || second !in 0..59
            ) return 0L

            var offsetMs = 0L
            zoneRegex.find(v.substring(i).trim())?.let { m ->
                val sign = if (m.groupValues[1] == "-") -1 else 1
                val h = m.groupValues[2].toInt()
                val min = m.groupValues[3].toInt()
                offsetMs = sign * (h * 60L + min) * 60_000L
            }

            cal.clear()
            cal.set(year, month - 1, day, hour, minute, second)
            return cal.timeInMillis - offsetMs
        }
    }
}
