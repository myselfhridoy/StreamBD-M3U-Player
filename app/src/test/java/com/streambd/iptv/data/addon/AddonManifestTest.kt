package com.streambd.iptv.data.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonManifestTest {

    private fun manifest(extra: String = "") = AddonManifest.parse(
        """{"id":"author.site","name":"Site","version":"1.0.0",
            "domains":["fixed.com","*.cdn.net"],
            "domainSettings":["site","player"],
            "settings":[
              {"key":"site","label":"Site","type":"text","default":"https://site.example"},
              {"key":"player","label":"Player","type":"text","default":"player.example"}
            ] $extra}""",
    )

    @Test fun parsesBasics() {
        val m = manifest()
        assertEquals("author.site", m.id)
        assertEquals(listOf("fixed.com", "cdn.net"), m.domains)
        assertEquals(listOf("site", "player"), m.domainSettings)
        assertEquals(2, m.settings.size)
        assertFalse(m.web)
        assertTrue(m.catalog)
    }

    @Test fun defaultsAreUsedWhenNoValue() {
        val d = manifest().effectiveDomains(emptyMap())
        assertTrue("site.example" in d)
        assertTrue("player.example" in d)
        assertTrue("fixed.com" in d)
    }

    @Test fun blankValueFallsBackToDefault() {
        val d = manifest().effectiveDomains(mapOf("site" to "   "))
        assertTrue("site.example" in d)
    }

    @Test fun userValueAddsHost() {
        val m = manifest()
        val v = mapOf("site" to "https://new-site.example/path?x=1")
        assertTrue(m.allowsHost("new-site.example", v))
        assertTrue(m.allowsHost("www.new-site.example", v))
    }

    @Test fun multipleValuesCanBeSeparated() {
        val m = manifest()
        val v = mapOf("site" to "a.com, b.com;c.com d.com\ne.com")
        for (h in listOf("a.com", "b.com", "c.com", "d.com", "e.com")) assertTrue(h, m.allowsHost(h, v))
    }

    @Test fun otherHostsAreBlocked() {
        val m = manifest()
        assertFalse(m.allowsHost("evil.com", emptyMap()))
        assertFalse(m.allowsHost("notfixed.com", emptyMap()))
        assertTrue(m.allowsHost("sub.fixed.com", emptyMap()))
    }

    @Test fun withoutDomainSettingsOnlyDomainsCount() {
        val m = AddonManifest.parse("""{"id":"author.plain","name":"P","domains":["a.com"]}""")
        assertEquals(listOf("a.com"), m.effectiveDomains(mapOf("site" to "x.com")))
        assertFalse(m.allowsHost("x.com", mapOf("site" to "x.com")))
    }

    @Test fun wildcardAllowsEverything() {
        val m = AddonManifest.parse("""{"id":"author.any","name":"A","domains":["*"]}""")
        assertTrue(m.allowsHost("anything.example"))
    }

    @Test fun invalidManifestsAreRejected() {
        for (bad in listOf("", "not json", """{"id":"X","name":"n"}""", """{"id":"author.ok","name":""}""", """{"id":"author.ok","name":"n","apiVersion":99}""")) {
            val failed = try { AddonManifest.parse(bad); false } catch (e: AddonException) { true }
            assertTrue("should reject: $bad", failed)
        }
    }
}
