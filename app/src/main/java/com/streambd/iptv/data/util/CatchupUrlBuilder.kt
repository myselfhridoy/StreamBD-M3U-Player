package com.streambd.iptv.data.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CatchupUrlBuilder {

    fun build(
        sourceTemplate: String,
        startMs: Long,
        endMs: Long,
    ): String {
        if (sourceTemplate.isBlank()) return ""

        var url = sourceTemplate
        val startSec = startMs / 1000
        val endSec = endMs / 1000
        val nowSec = System.currentTimeMillis() / 1000
        val durationSec = (endMs - startMs) / 1000
        val durationMin = durationSec / 60

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val startIso = isoFormat.format(Date(startMs))
        val endIso = isoFormat.format(Date(endMs))
        val nowIso = isoFormat.format(Date())

        url = url
            .replace("{utc}", startSec.toString())
            .replace("{start}", startSec.toString())
            .replace("{start_iso}", startIso)
            .replace("{utcend}", endSec.toString())
            .replace("{end}", endSec.toString())
            .replace("{end_iso}", endIso)
            .replace("{current_utc}", nowSec.toString())
            .replace("{timestamp}", nowSec.toString())
            .replace("{now_iso}", nowIso)
            .replace("{duration}", durationSec.toString())
            .replace("{durmin}", durationMin.toString())
            .replace("{offset}", (nowSec - startSec).toString())

        return url
    }
}
