package com.streambd.iptv.player.cenc

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
internal class CencDecryptingDataSource(
    private val upstream: DataSource,
    private val keyRef: AtomicReference<ByteArray?>,
) : DataSource {

    private companion object {
        const val TAG = "CencDataSource"
        const val MAX_SEGMENT_BYTES = 64 * 1024 * 1024
    }

    private enum class Kind { MPD, ISO_BMFF_INIT, ISO_BMFF_MEDIA, OTHER }

    // প্রতিটি `open()` কলে এই ফিল্ডগুলো রিসেট হবে
    private var buffered: ByteArrayInputStream? = null
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // আগের `open()` থেকে কোনো রিসোর্স খোলা থাকলে তা বন্ধ করা হচ্ছে
        closeQuietly()

        this.uri = dataSpec.uri
        val url = dataSpec.uri.toString()

        // আপস্ট্রিম ডেটাসোর্সটি এই ব্লকে শুধুমাত্র একবার খোলা এবং বন্ধ হবে
        val raw = try {
            val length = upstream.open(dataSpec)
            readAll(upstream, length)
        } catch (e: IOException) {
            throw e
        } finally {
            upstream.close()
        }

        val kind = classify(raw)
        Log.d(TAG, "classify ${raw.size}B ${kind.name} → $url")

        val out = when (kind) {
            Kind.MPD -> {
                val text = String(raw, Charsets.UTF_8)
                val stripped = MpdStripper.stripContentProtection(text)
                Log.i(TAG, "MPD stripped: ${text.length}B → ${stripped.length}B")
                stripped.toByteArray(Charsets.UTF_8)
            }
            Kind.ISO_BMFF_INIT -> {
                try { InitSegmentTransformer.transform(raw) } catch (e: Exception) {
                    Log.e(TAG, "Init transform failed: ${e.message}", e); raw
                }
            }
            Kind.ISO_BMFF_MEDIA -> {
                val key = keyRef.get()
                if (key == null) {
                    Log.w(TAG, "Media segment but no CENC key — passthrough")
                    raw
                } else {
                    try { MediaSegmentTransformer.transform(raw, key) } catch (e: Exception) {
                        Log.e(TAG, "Media transform failed: ${e.message}", e); raw
                    }
                }
            }
            Kind.OTHER -> raw
        }

        buffered = ByteArrayInputStream(out)
        return out.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val b = buffered ?: return C.RESULT_END_OF_INPUT
        return b.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closeQuietly()
        try { upstream.close() } catch (_: Exception) {}
    }

    private fun closeQuietly() {
        try { buffered?.close() } catch (_: Exception) {}
        buffered = null
    }

    private fun readAll(source: DataSource, declaredLength: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(
            if (declaredLength in 1..MAX_SEGMENT_BYTES) declaredLength.toInt() else 32 * 1024
        )
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = source.read(buf, 0, buf.size)
            if (read == C.RESULT_END_OF_INPUT) break
            if (read < 0) break
            total += read
            if (total > MAX_SEGMENT_BYTES) throw IOException("Segment too large: $total bytes")
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun classify(data: ByteArray): Kind {
        if (data.size < 16) return Kind.OTHER

        // ISO BMFF বক্স চেক
        val boxType = runCatching { CencByteUtils.boxType(data, 0) }.getOrNull()
        if (boxType in setOf("ftyp", "styp", "moof", "moov", "sidx", "emsg", "free", "mdat")) {
            var pos = 0
            while (pos + 8 <= data.size) {
                val h = CencByteUtils.readBoxHeader(data, pos) ?: break
                when (h.type) {
                    "moov" -> return Kind.ISO_BMFF_INIT
                    "moof" -> return Kind.ISO_BMFF_MEDIA
                }
                pos += h.size
            }
            return Kind.ISO_BMFF_MEDIA
        }

        // XML/MPD চেক
        val head = runCatching {
            String(data, 0, minOf(data.size, 512), Charsets.UTF_8)
        }.getOrNull()
        if (head != null) {
            val trimmed = head.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            if (trimmed.startsWith("<?xml", ignoreCase = true) ||
                trimmed.startsWith("<MPD", ignoreCase = true) ||
                "<MPD" in head.take(200)
            ) {
                return Kind.MPD
            }
        }

        return Kind.OTHER
    }
}

/** MPD থেকে সমস্ত `<ContentProtection>` element সরায়। */
internal object MpdStripper {

    private val paired = Regex(
        """<ContentProtection\b[^>]*>.*?</ContentProtection>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val selfClosing = Regex(
        """<ContentProtection\b[^>]*/>""",
        RegexOption.IGNORE_CASE,
    )

    fun stripContentProtection(mpd: String): String =
        selfClosing.replace(paired.replace(mpd, ""), "")
}
