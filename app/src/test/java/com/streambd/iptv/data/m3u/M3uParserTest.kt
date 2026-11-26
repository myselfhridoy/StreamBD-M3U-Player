package com.streambd.iptv.data.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private fun one(text: String) = M3uParser.parse(text).channels.single()

    @Test fun basicLiveChannel() {
        val c = one(
            """#EXTM3U
            #EXTINF:-1 tvg-id="a.bd" tvg-logo="http://l/a.png" group-title="News",Channel A
            http://h/a.m3u8""".trimIndent(),
        )
        assertEquals("Channel A", c.name)
        assertEquals("News", c.groupTitle)
        assertEquals("a.bd", c.tvgId)
        assertEquals("http://l/a.png", c.logoUrl)
        assertEquals(ChannelKind.LIVE, c.kind)
        assertEquals("http://h/a.m3u8", c.streamUrl)
    }

    @Test fun missingGroupFallsBackToUngrouped() {
        assertEquals("Ungrouped", one("#EXTM3U\n#EXTINF:-1,X\nhttp://h/x.m3u8").groupTitle)
    }

    @Test fun movieAndSeriesAreClassifiedFromUrlPath() {
        val p = M3uParser.parse(
            """#EXTM3U
            #EXTINF:-1 group-title="Movies",A Movie (2020)
            http://h/movie/u/p/1.mp4
            #EXTINF:-1 group-title="Series",Show S01E02
            http://h/series/u/p/5.mkv""".trimIndent(),
        )
        assertEquals(ChannelKind.MOVIE, p.channels[0].kind)
        assertEquals(ChannelKind.SERIES, p.channels[1].kind)
    }

    @Test fun lineWithoutUrlIsDropped() {
        val p = M3uParser.parse("#EXTM3U\n#EXTINF:-1,NoUrl\n#EXTINF:-1,Ok\nhttp://h/ok.m3u8")
        assertEquals(listOf("Ok"), p.channels.map { it.name })
    }

    @Test fun byteOrderMarkIsIgnored() {
        assertEquals(1, M3uParser.parse("﻿#EXTM3U\n#EXTINF:-1,A\nhttp://h/a.m3u8").channels.size)
    }

    @Test fun epgUrlFromHeader() {
        assertEquals(
            listOf("http://e.com/epg.xml.gz"),
            M3uParser.parse("#EXTM3U x-tvg-url=\"http://e.com/epg.xml.gz\"\n").epgUrls,
        )
        assertEquals(
            listOf("http://a/1.xml", "http://b/2.xml"),
            M3uParser.parse("#EXTM3U url-tvg=\"http://a/1.xml, http://b/2.xml\"\n").epgUrls,
        )
        assertTrue(M3uParser.parse("#EXTM3U\n").epgUrls.isEmpty())
    }

    @Test fun foldersFlag() {
        assertTrue(M3uParser.parse("#EXTM3U x-sb-folders=\"1\"\n").folders)
        assertFalse(M3uParser.parse("#EXTM3U\n").folders)
    }

    @Test fun pipeHeadersStayOnTheUrl() {
        val c = one("#EXTM3U\n#EXTINF:-1,P\nhttp://h/p.m3u8|Referer=http://r/&User-Agent=UA")
        assertEquals("http://h/p.m3u8|Referer=http://r/&User-Agent=UA", c.streamUrl)
    }

    @Test fun vlcOptionsBecomeHeaders() {
        val c = one(
            "#EXTM3U\n#EXTINF:-1,V\n#EXTVLCOPT:http-user-agent=MyUA\n#EXTVLCOPT:http-referrer=http://ref/\nhttp://h/v.m3u8",
        )
        assertTrue(c.streamUrl.startsWith("http://h/v.m3u8|"))
        assertTrue(c.streamUrl.contains("User-Agent=MyUA"))
        assertTrue(c.streamUrl.contains("Referer=http%3A%2F%2Fref%2F"))
    }

    @Test fun kodiClearKeyDrm() {
        val c = one(
            """#EXTM3U
            #EXTINF:-1,D
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=abcd:1234
            http://h/d.mpd""".trimIndent(),
        )
        assertEquals("clearkey", c.drmScheme)
        assertEquals("abcd:1234", c.drmLicense)
        assertEquals("http://h/d.mpd", c.streamUrl)
    }

    @Test fun webEntryIsWrapped() {
        val c = one(
            "#EXTM3U\n#EXTINF:-1 x-web=\"1\" x-web-hosts=\"p.net\" x-stream-referer=\"http://s/\",W\nhttps://site/page|Referer=http://site/",
        )
        assertTrue(WebEntry.isWeb(c.streamUrl))
        val plan = WebEntry.decode(c.streamUrl)!!
        assertEquals("https://site/page", plan.pageUrl)
        assertEquals("http://site/", plan.pageHeaders["Referer"])
        assertEquals("http://s/", plan.streamHeaders["Referer"])
        assertEquals(listOf("p.net"), plan.hosts)
    }

    @Test fun normalEntryIsNotWrapped() {
        assertFalse(WebEntry.isWeb(one("#EXTM3U\n#EXTINF:-1,N\nhttps://h/n.m3u8").streamUrl))
    }

    @Test fun webFlagOffMeansNormalEntry() {
        assertFalse(WebEntry.isWeb(one("#EXTM3U\n#EXTINF:-1 x-web=\"0\",N\nhttps://h/n.m3u8").streamUrl))
    }

    @Test fun emptyInputGivesNoChannels() {
        assertTrue(M3uParser.parse("").channels.isEmpty())
        assertNull(null)
    }
}
