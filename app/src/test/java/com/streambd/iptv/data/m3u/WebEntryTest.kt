package com.streambd.iptv.data.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebEntryTest {

    @Test fun xWebTruthyValues() {
        for (v in listOf("1", "true", "YES", "on", " 1 ")) {
            assertTrue(v, WebEntry.fromAttrs(mapOf("x-web" to v)) != null)
        }
        for (v in listOf("0", "false", "no", "", "maybe")) {
            assertNull(v, WebEntry.fromAttrs(mapOf("x-web" to v)))
        }
        assertNull(WebEntry.fromAttrs(emptyMap()))
    }

    @Test fun headerNamesAreNormalised() {
        val s = WebEntry.fromAttrs(
            mapOf(
                "x-web" to "1",
                "x-web-referer" to "https://a/",
                "x-web-user-agent" to "UA",
                "x-stream-referrer" to "https://b/",
                "x-stream-origin" to "https://b",
            ),
        )!!
        assertEquals("https://a/", s.pageHeaders["Referer"])
        assertEquals("UA", s.pageHeaders["User-Agent"])
        assertEquals("https://b/", s.streamHeaders["Referer"])
        assertEquals("https://b", s.streamHeaders["Origin"])
    }

    @Test fun hostsAndTimeoutAreNotHeaders() {
        val s = WebEntry.fromAttrs(mapOf("x-web" to "1", "x-web-hosts" to "a.com, b.net", "x-web-timeout" to "120"))!!
        assertEquals(listOf("a.com", "b.net"), s.hosts)
        assertEquals(120, s.timeoutSec)
        assertTrue(s.pageHeaders.isEmpty())
    }

    @Test fun timeoutIsClampedAndDefaulted() {
        assertEquals(WebEntry.DEFAULT_TIMEOUT_SEC, WebEntry.fromAttrs(mapOf("x-web" to "1"))!!.timeoutSec)
        assertEquals(20, WebEntry.fromAttrs(mapOf("x-web" to "1", "x-web-timeout" to "1"))!!.timeoutSec)
        assertEquals(600, WebEntry.fromAttrs(mapOf("x-web" to "1", "x-web-timeout" to "99999"))!!.timeoutSec)
        assertEquals(WebEntry.DEFAULT_TIMEOUT_SEC, WebEntry.fromAttrs(mapOf("x-web" to "1", "x-web-timeout" to "abc"))!!.timeoutSec)
    }

    @Test fun wrapThenDecodeRoundTrip() {
        val spec = WebEntry.fromAttrs(
            mapOf("x-web" to "1", "x-web-hosts" to "p.net", "x-stream-referer" to "https://s/?a=1&b=2"),
        )!!
        val wrapped = WebEntry.wrap("https://site.com/watch.php?id=61&x=y|Referer=https://site.com/", spec)
        assertTrue(WebEntry.isWeb(wrapped))
        val plan = WebEntry.decode(wrapped)!!
        assertEquals("https://site.com/watch.php?id=61&x=y", plan.pageUrl)
        assertEquals("https://site.com/", plan.pageHeaders["Referer"])
        assertEquals("https://s/?a=1&b=2", plan.streamHeaders["Referer"])
        assertEquals(listOf("p.net"), plan.hosts)
    }

    @Test fun explicitAttributeOverridesPipeHeader() {
        val spec = WebEntry.fromAttrs(mapOf("x-web" to "1", "x-web-referer" to "https://attr/"))!!
        val plan = WebEntry.decode(WebEntry.wrap("https://site/p|Referer=https://pipe/", spec))!!
        assertEquals("https://attr/", plan.pageHeaders["Referer"])
        assertEquals(1, plan.pageHeaders.size)
    }

    @Test fun decodeRejectsBrokenInput() {
        assertNull(WebEntry.decode("https://not-web/"))
        assertNull(WebEntry.decode("webplay://p"))
        assertNull(WebEntry.decode("webplay://p?h=A:B"))
    }

    @Test fun isWebIsCaseInsensitive() {
        assertTrue(WebEntry.isWeb("WEBPLAY://p?u=x"))
        assertFalse(WebEntry.isWeb("https://x"))
    }

    @Test fun mergeHeadersIsCaseInsensitive() {
        val m = WebEntry.mergeHeaders(mapOf("referer" to "a", "X" to "1"), mapOf("Referer" to "b"))
        assertEquals("b", m.entries.single { it.key.equals("referer", true) }.value)
        assertEquals("1", m["X"])
        assertEquals(2, m.size)
    }
}
