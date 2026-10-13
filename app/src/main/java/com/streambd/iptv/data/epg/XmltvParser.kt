package com.streambd.iptv.data.epg

import android.util.Xml
import com.streambd.iptv.data.db.ProgramEntity
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object XmltvParser {

    fun parse(input: InputStream, onProgram: (ProgramEntity) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var desc: String? = null
        var poster: String? = null
        var inTitle = false
        var inDesc = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        channel = parser.getAttributeValue(null, "channel")
                        start = parseDate(parser.getAttributeValue(null, "start"))
                        stop = parseDate(parser.getAttributeValue(null, "stop"))
                        title = null
                        desc = null
                        poster = null
                    }
                    "title" -> inTitle = true
                    "desc" -> inDesc = true
                    "icon" -> poster = parser.getAttributeValue(null, "src") ?: poster
                }

                XmlPullParser.TEXT -> {
                    if (inTitle) title = (title ?: "") + parser.text
                    if (inDesc) desc = (desc ?: "") + parser.text
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> inTitle = false
                    "desc" -> inDesc = false
                    "programme" -> {
                        val channelId = channel
                        if (!channelId.isNullOrBlank() && stop > start) {
                            val key = "$channelId|$start"
                            val id = (key.hashCode().toLong() and 0x7FFFFFFFL) * 1000L + start % 1000
                            onProgram(
                                ProgramEntity(
                                    id = id,
                                    channelTvgId = channelId,
                                    start = start,
                                    end = stop,
                                    title = title.orEmpty().trim().ifBlank { "No information available" },
                                    desc = desc.orEmpty().trim(),
                                    poster = poster,
                                ),
                            )
                        }
                        channel = null
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val parts = value.trim().split(Regex("\\s+"), limit = 2)
        val timestamp = parts[0].takeWhile(Char::isDigit)
        val pattern = when (timestamp.length) {
            12 -> "yyyyMMddHHmm"
            14 -> "yyyyMMddHHmmss"
            else -> return 0L
        }
        val zone = parts.getOrNull(1)?.takeIf { it.matches(Regex("[+-]\\d{4}")) }
        val formatter = SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone(zone?.let { "GMT$it" } ?: "UTC")
        }
        return runCatching { formatter.parse(timestamp)?.time ?: 0L }.getOrDefault(0L)
    }
}