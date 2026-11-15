package com.streambd.iptv.data.addon

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** JS `html` ব্রিজ (Jsoup): CSS selector দিয়ে HTML/XML থেকে উপাদান বের করা। */
internal object AddonHtml {
    private const val MAX_ELEMENTS = 5000

    fun handle(op: String, src: String, selector: String, optsJson: String): String = try {
        val opts = if (optsJson.isBlank()) JSONObject() else JSONObject(optsJson)
        val base = opts.optString("baseUrl", "")
        val doc = if (opts.optBoolean("xml", false)) Jsoup.parse(src, base, Parser.xmlParser()) else Jsoup.parse(src, base)
        when (op) {
            "text" -> doc.text()
            else -> {
                val out = JSONArray()
                for ((i, e) in doc.select(selector).withIndex()) {
                    if (i >= MAX_ELEMENTS) break
                    val attrs = JSONObject()
                    for (a in e.attributes()) attrs.put(a.key, a.value)
                    if (base.isNotEmpty()) {
                        for (k in listOf("href", "src", "data-src")) {
                            if (e.hasAttr(k)) attrs.put("abs:$k", e.absUrl(k))
                        }
                    }
                    out.put(
                        JSONObject()
                            .put("tag", e.tagName())
                            .put("text", e.text())
                            .put("html", e.html())
                            .put("outer", e.outerHtml())
                            .put("attrs", attrs),
                    )
                }
                out.toString()
            }
        }
    } catch (e: Exception) {
        AddonCrypto.ERR + (e.message ?: e.javaClass.simpleName)
    }
}
