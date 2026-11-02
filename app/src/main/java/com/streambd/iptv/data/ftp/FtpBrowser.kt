package com.streambd.iptv.data.ftp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FtpException(message: String, val retryable: Boolean = false) : Exception(message)

/** ডিরেক্টর তালিকা আনে (ফোল্ডার খোলার সময়ই, আগে থেকে ক্রল করে না) এবং মেমোরিতে ক্যাশ রাখে। */
@Singleton
class FtpBrowser @Inject constructor(baseClient: OkHttpClient) {

    private val client = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val cache = object : LinkedHashMap<String, List<FtpEntry>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FtpEntry>>?) = size > MAX_CACHED
    }

    /** ইউজারের লেখা ঠিকানা ঠিক করে: scheme না থাকলে http://, শেষে "/"। অবৈধ হলে null। */
    fun normalizeAddress(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        val url = s.toHttpUrlOrNull() ?: return null
        val out = url.newBuilder().fragment(null).build().toString()
        return if (out.endsWith("/")) out else "$out/"
    }

    fun forget(url: String) = synchronized(cache) { cache.remove(url) }

    suspend fun list(dirUrl: String, force: Boolean = false): List<FtpEntry> {
        if (!force) synchronized(cache) { cache[dirUrl] }?.let { return it }
        // ★ নেটওয়ার্ক পড়া ও পার্সিং অবশ্যই IO থ্রেডে (মেইন থ্রেডে হলে NetworkOnMainThreadException হয়)
        return withContext(Dispatchers.IO) {
            val html = fetchWithRetry(dirUrl)
            val entries = DirectoryParser.parse(html, dirUrl)
            if (entries.isEmpty() && !looksLikeListing(html)) {
                throw FtpException("This address doesn't look like a directory listing.")
            }
            synchronized(cache) { cache[dirUrl] = entries }
            entries
        }
    }

    /** অস্থায়ী ত্রুটিতে (টাইমআউট, সংযোগ ছিঁড়ে যাওয়া, ৫xx) নিজে থেকে ৩ বার চেষ্টা করে। */
    private suspend fun fetchWithRetry(url: String): String {
        var last: FtpException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return fetch(url)
            } catch (e: FtpException) {
                if (!e.retryable) throw e
                last = e
                if (attempt < MAX_ATTEMPTS - 1) delay(600L * (attempt + 1))
            }
        }
        throw last ?: FtpException("Failed to load.")
    }

    private fun looksLikeListing(html: String): Boolean =
        "fb-n" in html || "Index of" in html || "<a " in html.lowercase()

    private suspend fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "StreamBD/1.0").build()
        val call = client.newCall(request)
        try {
            call.await().use { r ->
                if (!r.isSuccessful) {
                    val retry = r.code >= 500 || r.code == 408 || r.code == 429
                    throw FtpException("Server returned HTTP ${r.code}.", retryable = retry)
                }
                val body = r.body ?: throw FtpException("Empty response from server.")
                if (body.contentLength() > MAX_PAGE_BYTES) throw FtpException("This folder page is too large.")
                return body.string() // এখানেও IOException হতে পারে, তাই try-র ভেতরে
            }
        } catch (e: FtpException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            throw FtpException("Server not found. Check the address.")
        } catch (e: ConnectException) {
            throw FtpException("Can't connect. This server may only work on its own ISP network.", retryable = true)
        } catch (e: SocketTimeoutException) {
            throw FtpException("The server took too long to respond.", retryable = true)
        } catch (e: IOException) {
            throw FtpException("Connection problem: ${e.message ?: e.javaClass.simpleName}", retryable = true)
        } catch (e: Exception) {
            // কোনো বার্তাহীন ত্রুটিতেও ধরন দেখাবে, যাতে কারণ বোঝা যায়
            throw FtpException("Failed to load (${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""})")
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // ততক্ষণে বাতিল হলে রেসপন্স বন্ধ করি, নইলে কানেকশন আটকে থাকে
                if (cont.isActive) cont.resume(response) else response.close()
            }
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_CACHED = 80
        const val MAX_PAGE_BYTES = 30L * 1024 * 1024
    }
}
