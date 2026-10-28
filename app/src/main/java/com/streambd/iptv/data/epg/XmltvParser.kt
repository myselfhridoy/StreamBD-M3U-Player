package com.streambd.iptv.data.epg

import android.util.Xml
import com.streambd.iptv.data.db.ProgramEntity
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
     * @param accept প্রোগ্রাম অবজেক্ট বানানোর আগেই বাদ দেওয়ার ফিল্টার (channel, start, end)।
     *               মেমোরি ও সময় বাঁচায়।
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

    private fun parseXml(
        input: InputStream,
        accept: ((String, Long, Long) -> Boolean)?,
        onProgram: (ProgramEntity) -> Unit,
    ) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val dates = DateParser()

        // XMLTV এর <channel> এবং <display-name> ম্যাপ করার জন্য ভেরিয়েবল
        val channelToDisplayName = HashMap<String, String>()
        var inChannel = false
        var currentChannelId: String? = null
        var currentDisplayName: StringBuilder? = null
        var capturingDisplayName = false

        var inProgramme = false
        var skipOriginal = false
        var skipMapped = false
        var channel: String? = null
        var mappedChannel: String? = null // name:displayName
        var start = 0L
        var stop = 0L
        var title: StringBuilder? = null
        var desc: StringBuilder? = null
        var poster: String? = null
        var capturing: StringBuilder? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        inChannel = true
                        currentChannelId = parser.getAttributeValue(null, "id")
                        currentDisplayName = null
                    }
                    "display-name" -> {
                        if (inChannel && currentChannelId != null && currentDisplayName == null) {
                            currentDisplayName = StringBuilder()
                            capturingDisplayName = true
                        }
                    }
                    "programme" -> {
                        inProgramme = true
                        channel = parser.getAttributeValue(null, "channel")
                        start = dates.parse(parser.getAttributeValue(null, "start"))
                        stop = dates.parse(parser.getAttributeValue(null, "stop"))
                        title = null
                        desc = null
                        poster = null
                        capturing = null

                        val ch = channel
                        // ১. ওরিজিনাল চ্যানেল আইডির (tvg-id) জন্য ফিল্টার
                        skipOriginal = ch.isNullOrBlank() || stop <= start ||
                                (accept != null && !accept(ch, start, stop))

                        // ২. XMLTV এর display-name থেকে তৈরি করা আইডির (name:XXX) জন্য ফিল্টার
                        val displayName = channelToDisplayName[ch]
                        mappedChannel = if (!displayName.isNullOrBlank()) "name:$displayName" else null
                        val mappedCh = mappedChannel
                        skipMapped = mappedCh.isNullOrBlank() || stop <= start ||
                                (accept != null && !accept(mappedCh, start, stop))

                        // যদি দুইভাবেই ম্যাচ না করে, তবে প্রসেস করার দরকার নেই
                        if (skipOriginal && skipMapped) {
                            inProgramme = false 
                        }
                    }
                    "title" -> if (inProgramme && !(skipOriginal && skipMapped) && title == null) {
                        title = StringBuilder().also { capturing = it }
                    }
                    "desc" -> if (inProgramme && !(skipOriginal && skipMapped) && desc == null) {
                        desc = StringBuilder().also { capturing = it }
                    }
                    "icon" -> if (inProgramme && !(skipOriginal && skipMapped) && poster == null) {
                        poster = parser.getAttributeValue(null, "src")
                    }
                }

                XmlPullParser.TEXT -> {
                    if (capturingDisplayName) currentDisplayName?.append(parser.text)
                    if (capturing != null) capturing?.append(parser.text)
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "display-name" -> {
                        capturingDisplayName = false
                    }
                    "channel" -> {
                        if (inChannel && currentChannelId != null && currentDisplayName != null) {
                            val name = currentDisplayName.toString().trim()
                            if (name.isNotEmpty()) {
                                channelToDisplayName[currentChannelId!!] = name
                            }
                        }
                        inChannel = false
                        currentChannelId = null
                        currentDisplayName = null
                    }
                    "title", "desc" -> capturing = null
                    "programme" -> {
                        if (inProgramme) {
                            val titleStr = title?.toString().orEmpty().trim().ifBlank { "No information available" }
                            val descStr = desc?.toString().orEmpty().trim()
                            val ch = channel
                            val mappedCh = mappedChannel

                            // ১. ওরিজিনাল tvg-id এর জন্য সেভ করা
                            if (!skipOriginal && ch != null) {
                                onProgram(
                                    ProgramEntity(
                                        id = stableId("$ch|$start"),
                                        channelTvgId = ch,
                                        start = start,
                                        end = stop,
                                        title = titleStr,
                                        desc = descStr,
                                        poster = poster,
                                    )
                                )
                            }

                            // ২. ডিসপ্লে-নেম ম্যাপড আইডির (name:XXX) জন্য সেভ করা
                            if (!skipMapped && mappedCh != null) {
                                onProgram(
                                    ProgramEntity(
                                        id = stableId("$mappedCh|$start"),
                                        channelTvgId = mappedCh,
                                        start = start,
                                        end = stop,
                                        title = titleStr,
                                        desc = descStr,
                                        poster = poster,
                                    )
                                )
                            }
                        }
                        inProgramme = false
                        channel = null
                        mappedChannel = null
                        capturing = null
                    }
                }
            }
            event = parser.next()
        }
    }

    /**
     * XMLTV তারিখ: "20261027030000 +0600"। প্রতিবার SimpleDateFormat বানানো ধীর,
     * তাই একটা Calendar পুনর্ব্যবহার করা হয়। জোন না থাকলে UTC ধরা হয় (XMLTV নিয়ম)।
     * এক parse() কলে এক instance, তাই থ্রেড-সেফটি নিয়ে ভাবতে হয় না।
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
