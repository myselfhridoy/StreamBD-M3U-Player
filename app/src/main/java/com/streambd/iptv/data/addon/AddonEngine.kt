package com.streambd.iptv.data.addon

import android.content.Context
import android.util.Base64
import android.util.Log
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import com.streambd.iptv.Brand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Add-on চালানোর ফলাফল। */
data class AddonRunResult(
    val m3u: String?,
    val error: String?,
    val log: List<String>,
    val storage: Map<String, String>,
)

/**
 * Add-on JS চালানোর ইঞ্জিন (QuickJS)। প্রতিটা রান আলাদা QuickJS ইনস্ট্যান্স, আলাদা কুকি জার,
 * আলাদা নেটওয়ার্ক পারমিশন (manifest.domains)। ফাইল/অ্যাপের ডেটায় কোনো অ্যাক্সেস নেই।
 */
@Singleton
class AddonEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private companion object {
        const val TAG = "AddonEngine"
        const val RUN_TIMEOUT_MS = 180_000L
        const val MAX_BODY_BYTES = 20 * 1024 * 1024
        const val MAX_STORAGE_CHARS = 256_000
        const val MAX_LOG_LINES = 150
        const val MAX_SCRIPT_CHARS = 1_000_000
    }

    private val gate = Semaphore(3)

    private val polyfill: String by lazy {
        context.assets.open("addon/polyfill.js").bufferedReader().use { it.readText() }
    }

    /** ইনস্টলের আগে: স্ক্রিপ্ট লোড করে `addon` অবজেক্ট পড়ে। নেটওয়ার্ক বন্ধ থাকে। */
    suspend fun describe(script: String): AddonManifest {
        val r = execute(script, null, emptyMap(), emptyMap(), describeOnly = true)
        if (r.error != null) throw AddonException(r.error)
        return AddonManifest.parse(r.m3u.orEmpty())
    }

    /** `generatePlaylist()` চালায়; ফলাফল M3U টেক্সট। */
    suspend fun run(
        script: String,
        manifest: AddonManifest,
        settings: Map<String, String>,
        storage: Map<String, String>,
    ): AddonRunResult = execute(script, manifest, settings, storage, describeOnly = false)

    private suspend fun execute(
        script: String,
        manifest: AddonManifest?,
        settings: Map<String, String>,
        storageIn: Map<String, String>,
        describeOnly: Boolean,
    ): AddonRunResult = withContext(Dispatchers.IO) {
        if (script.length > MAX_SCRIPT_CHARS) {
            return@withContext AddonRunResult(null, "Script is too large.", emptyList(), storageIn)
        }
        gate.withPermit {
            val logLines = ArrayList<String>()
            fun log(line: String) {
                synchronized(logLines) {
                    if (logLines.size < MAX_LOG_LINES) logLines += line.replace('\n', ' ').take(400)
                }
            }

            val store = HashMap(storageIn)
            val jar = MemoryCookieJar()
            val client = buildClient(manifest, jar)

            var sent: String? = null
            var failure: String? = null

            val finished = withTimeoutOrNull(RUN_TIMEOUT_MS) {
                try {
                    quickJs {
                        memoryLimit = 64L * 1024L * 1024L

                        function("nativeLog") { args ->
                            val level = args.getOrNull(0)?.toString() ?: "I"
                            val msg = args.getOrNull(1)?.toString() ?: ""
                            log("[$level] $msg")
                            null
                        }
                        function("nativeSend") { args ->
                            if (sent == null) sent = args.getOrNull(0)?.toString() ?: ""
                            null
                        }
                        function("nativeFail") { args ->
                            if (failure == null) failure = args.getOrNull(0)?.toString() ?: "Unknown error"
                            null
                        }
                        function("nativeCrypto") { args -> AddonCrypto.handle(args.getOrNull(0)?.toString() ?: "{}") }
                        function("nativeHtml") { args ->
                            AddonHtml.handle(
                                args.getOrNull(0)?.toString() ?: "select",
                                args.getOrNull(1)?.toString() ?: "",
                                args.getOrNull(2)?.toString() ?: "",
                                args.getOrNull(3)?.toString() ?: "{}",
                            )
                        }
                        function("nativeCookieGet") { args ->
                            val u = args.getOrNull(0)?.toString()?.toHttpUrlOrNull()
                            if (u == null) "" else jar.loadForRequest(u).joinToString("; ") { "${it.name}=${it.value}" }
                        }
                        function("nativeCookieSet") { args ->
                            val u = args.getOrNull(0)?.toString()?.toHttpUrlOrNull()
                            val c = args.getOrNull(1)?.toString()
                            if (u != null && c != null) Cookie.parse(u, c)?.let { jar.saveFromResponse(u, listOf(it)) }
                            null
                        }
                        function("nativeStorageGet") { args -> store[args.getOrNull(0)?.toString() ?: ""] }
                        function("nativeStorageSet") { args ->
                            val k = args.getOrNull(0)?.toString() ?: ""
                            val v = args.getOrNull(1)?.toString() ?: ""
                            val total = store.entries.sumOf { (if (it.key == k) 0 else it.key.length + it.value.length) } + k.length + v.length
                            if (total > MAX_STORAGE_CHARS) {
                                AddonCrypto.ERR + "Storage limit exceeded"
                            } else {
                                store[k] = v
                                null
                            }
                        }
                        function("nativeStorageRemove") { args ->
                            store.remove(args.getOrNull(0)?.toString() ?: "")
                            null
                        }
                        function("nativeSettings") { _ ->
                            val o = JSONObject()
                            manifest?.settings?.forEach { o.put(it.key, it.default) }
                            for ((k, v) in settings) o.put(k, v)
                            o.toString()
                        }
                        asyncFunction("nativeSleep") { args ->
                            val ms = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                            kotlinx.coroutines.delay(ms.coerceIn(0L, 60_000L))
                            null
                        }
                        asyncFunction("nativeFetch") { args ->
                            val url = args.getOrNull(0)?.toString() ?: return@asyncFunction errJson("Missing URL")
                            val opts = args.getOrNull(1)?.toString() ?: "{}"
                            if (describeOnly) return@asyncFunction errJson("Network is not available while installing")
                            try {
                                doFetch(url, JSONObject(opts), client)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                errJson(e.message ?: e.javaClass.simpleName)
                            }
                        }

                        evaluate<Any?>(polyfill + "\n;void 0;")
                        evaluate<Any?>(script + "\n;void 0;")

                        if (describeOnly) {
                            sent = evaluate<String>("typeof addon === 'undefined' ? '' : JSON.stringify(addon)")
                        } else {
                            evaluate<Any?>(
                                "(async function () { try { nativeSend(await __runAddon()); } " +
                                    "catch (e) { nativeFail(String(e && e.message ? e.message : e)); } })();\n;void 0;",
                            )
                        }
                        Unit
                    }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (failure == null) failure = e.message ?: e.javaClass.simpleName
                    true
                }
            }

            val error = when {
                finished == null -> "Timed out after ${RUN_TIMEOUT_MS / 1000}s."
                failure != null -> failure
                sent == null -> "The add-on returned nothing."
                else -> null
            }
            if (error != null) Log.w(TAG, "add-on run failed: $error")
            AddonRunResult(if (error == null) sent else null, error, synchronized(logLines) { logLines.toList() }, store)
        }
    }

    // ─────────────────────── নেটওয়ার্ক ───────────────────────

    private fun errJson(msg: String): String = JSONObject().put("error", msg).toString()

    private fun buildClient(manifest: AddonManifest?, jar: CookieJar): OkHttpClient {
        val domains = manifest?.domains.orEmpty()
        val any = domains.any { it == "*" }
        val allowLocal = manifest?.allowLocal == true

        fun hostAllowed(host: String): Boolean {
            if (any) return true
            val h = host.lowercase()
            return domains.any { h == it || h.endsWith(".$it") }
        }

        val dns = Dns { host ->
            if (!hostAllowed(host)) throw UnknownHostException("Blocked by add-on permissions: $host")
            val list = Dns.SYSTEM.lookup(host)
            if (!allowLocal && list.any { isPrivate(it) }) throw UnknownHostException("Blocked private address for $host")
            list
        }

        // IP-literal হোস্টে Dns চলে না, তাই প্রতিটা হপে (রিডাইরেক্টসহ) এখানেও যাচাই
        val guard = Interceptor { chain ->
            val host = chain.request().url.host
            if (!hostAllowed(host)) throw IOException("Blocked by add-on permissions: $host")
            if (!allowLocal && (host.contains(':') || host.all { it.isDigit() || it == '.' })) {
                val addr = runCatching { InetAddress.getByName(host) }.getOrNull()
                if (addr != null && isPrivate(addr)) throw IOException("Blocked private address: $host")
            }
            chain.proceed(chain.request())
        }

        return http.newBuilder()
            .cookieJar(jar)
            .dns(dns)
            .addNetworkInterceptor(guard)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun isPrivate(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        if (b.size == 4) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if (b0 == 100 && b1 in 64..127) return true // CGNAT
            if (b0 == 0) return true
        }
        if (a is Inet6Address && b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) return true // fc00::/7
        return false
    }

    private suspend fun doFetch(urlStr: String, o: JSONObject, base: OkHttpClient): String {
        val url: HttpUrl = urlStr.toHttpUrlOrNull() ?: return errJson("Invalid URL: $urlStr")
        val method = o.optString("method", "GET").uppercase()
        val rb = Request.Builder().url(url)

        var hasUa = false
        var contentType = "application/x-www-form-urlencoded"
        o.optJSONObject("headers")?.let { h ->
            for (k in h.keys()) {
                val v = h.optString(k)
                rb.header(k, v)
                if (k.equals("User-Agent", true)) hasUa = true
                if (k.equals("Content-Type", true)) contentType = v
            }
        }
        if (!hasUa) rb.header("User-Agent", Brand.USER_AGENT)

        val bodyB64 = o.optString("bodyB64", "")
        val bodyStr = if (o.isNull("body")) null else o.optString("body")
        val mt = contentType.toMediaTypeOrNull()
        val needsBody = method == "POST" || method == "PUT" || method == "PATCH"
        val body = when {
            bodyB64.isNotEmpty() -> Base64.decode(bodyB64, Base64.DEFAULT).toRequestBody(mt)
            bodyStr != null -> bodyStr.toRequestBody(mt)
            needsBody -> ByteArray(0).toRequestBody(mt)
            else -> null
        }
        rb.method(method, if (method == "GET" || method == "HEAD") null else body)

        var c = base
        val manual = o.optString("redirect") == "manual"
        val timeout = o.optInt("timeout", 0)
        if (manual || timeout > 0) {
            val nb = c.newBuilder()
            if (manual) nb.followRedirects(false).followSslRedirects(false)
            if (timeout > 0) nb.readTimeout(timeout.coerceAtMost(120_000).toLong(), TimeUnit.MILLISECONDS)
            c = nb.build()
        }

        val binary = o.optBoolean("binary", false)
        return c.newCall(rb.build()).awaitAddon().use { resp ->
            val hdrs = JSONObject()
            for (name in resp.headers.names()) hdrs.put(name.lowercase(), resp.headers.values(name).joinToString(", "))
            val bytes = resp.body?.byteStream()?.use { readLimited(it) } ?: ByteArray(0)
            val out = JSONObject()
                .put("ok", resp.isSuccessful)
                .put("status", resp.code)
                .put("url", resp.request.url.toString())
                .put("headers", hdrs)
            if (binary) {
                out.put("bodyB64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            } else {
                val cs = resp.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                out.put("body", String(bytes, cs))
            }
            out.toString()
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BODY_BYTES) throw IOException("Response too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private suspend fun Call.awaitAddon(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resumeWith(Result.success(response))
            }

            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWith(Result.failure(e))
            }
        })
    }

    private class MemoryCookieJar : CookieJar {
        private val store = ArrayList<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) {
                for (c in cookies) {
                    store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                    store.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
            val now = System.currentTimeMillis()
            store.removeAll { it.expiresAt < now }
            store.filter { it.matches(url) }
        }
    }
}
