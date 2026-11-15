package com.streambd.iptv.data.addon

import com.streambd.iptv.data.db.AddonDao
import org.json.JSONObject
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/** `resolveStream()` এর ফলাফল: প্লেয়ারকে যা দিয়ে চালাতে হবে। */
data class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val drmScheme: String?,
    val drmLicense: String?,
)

/**
 * চ্যানেলের URL `addon-resolve://<addonId>/<ref>` হলে প্লে করার মুহূর্তে সংশ্লিষ্ট Add-on-এর
 * `resolveStream()` চালিয়ে আসল লিংক বের করে। (ChannelRepository ইত্যাদির সাথে সম্পর্কহীন, তাই
 * PlayerManager-এ নিরাপদে ইনজেক্ট করা যায়।)
 */
@Singleton
class AddonResolver @Inject constructor(
    private val addons: AddonDao,
    private val engine: AddonEngine,
) {
    companion object {
        const val SCHEME = "addon-resolve://"

        fun isResolvable(url: String) = url.startsWith(SCHEME, ignoreCase = true)
    }

    /** [url] = `addon-resolve://<addonId>/<urlencoded ref>` (হেডার-সাফিক্স ছাড়া)। */
    suspend fun resolve(url: String, name: String, tvgId: String?, group: String?): ResolvedStream {
        val rest = url.substring(SCHEME.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) throw AddonException("Invalid add-on link.")
        val addonId = rest.substring(0, slash)
        val ref = runCatching { URLDecoder.decode(rest.substring(slash + 1), "UTF-8") }.getOrDefault(rest.substring(slash + 1))

        val addon = addons.getByAddonId(addonId) ?: throw AddonException("Add-on '$addonId' is not installed.")
        if (!addon.enabled) throw AddonException("Add-on '${addon.name}' is turned off.")
        val manifest = AddonManifest.parse(addon.manifest)

        val info = JSONObject().put("ref", ref).put("url", ref).put("name", name)
            .put("tvgId", tvgId ?: "").put("group", group ?: "").toString()

        val storageIn = toMap(addon.storage)
        val r = engine.resolve(addon.script, manifest, toMap(addon.settings), storageIn, info)

        // resolveStream() টোকেন ইত্যাদি storage-এ রাখতে পারে: বদলালে সেভ
        if (r.storage != storageIn) {
            val o = JSONObject()
            for ((k, v) in r.storage) o.put(k, v)
            runCatching { addons.setStorage(addon.id, o.toString()) }
        }

        val body = r.m3u
        if (r.error != null || body == null) throw AddonException(r.error ?: "The add-on returned no link.")

        val o = JSONObject(body)
        val stream = o.optString("url").trim()
        if (stream.isEmpty()) throw AddonException("The add-on returned no link.")

        val h = LinkedHashMap<String, String>()
        o.optJSONObject("headers")?.let { jo -> for (k in jo.keys()) h[k] = jo.optString(k) }
        if (o.has("userAgent")) h["User-Agent"] = o.optString("userAgent")
        if (o.has("referrer")) h["Referer"] = o.optString("referrer")
        if (o.has("origin")) h["Origin"] = o.optString("origin")

        return ResolvedStream(
            url = stream,
            headers = h,
            drmScheme = o.optString("drmScheme").takeIf { it.isNotBlank() },
            drmLicense = o.optString("drmLicense").takeIf { it.isNotBlank() },
        )
    }

    private fun toMap(json: String): Map<String, String> = try {
        val o = JSONObject(json)
        val out = HashMap<String, String>()
        for (k in o.keys()) out[k] = o.optString(k)
        out
    } catch (e: Exception) {
        emptyMap()
    }
}
