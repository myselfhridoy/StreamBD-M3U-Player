package com.streambd.iptv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogSafeTest {

    @Test fun xtreamCredentialsNeverAppear() {
        val s = LogSafe.url("http://host.com:8080/series/myuser/mypass/123.mp4")
        assertFalse(s.contains("myuser"))
        assertFalse(s.contains("mypass"))
        assertEquals("http://host.com:8080/.../*.mp4", s)
    }

    @Test fun queryAndTokenAreRemoved() {
        val s = LogSafe.url("https://a.b/c/d.m3u8?token=SECRET&x=1#frag")
        assertFalse(s.contains("SECRET"))
        assertEquals("https://a.b/.../*.m3u8", s)
    }

    @Test fun userInfoIsRemoved() {
        assertEquals("http://h/...", LogSafe.url("http://user:pass@h/x"))
    }

    @Test fun pipeHeadersAreRemoved() {
        assertFalse(LogSafe.url("http://h/p.m3u8|Referer=http://secret/").contains("secret"))
    }

    @Test fun nullAndBlank() {
        assertEquals("-", LogSafe.url(null))
        assertEquals("-", LogSafe.url("  "))
    }

    @Test fun hostOnlyIsKept() {
        assertEquals("https://x.com", LogSafe.url("https://x.com"))
    }
}
