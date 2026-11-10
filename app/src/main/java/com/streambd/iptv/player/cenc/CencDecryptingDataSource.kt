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

    private var buffered: ByteArrayInputStream? = null
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closeBuffered()
        this.uri = dataSpec.uri
        val url = dataSpec.uri.toString()

        // ---- Read the entire segment from upstream ----
        val raw = try {
            val length = upstream.open(dataSpec)
            try {
                readAll(upstream, length)
            } finally {
                try {
                    upstream.close()
                } catch (e: Throwable) {
                    Log.w(TAG, "upstream.close failed for $url", e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "upstream read failed for $url", e)
            throw if (e is IOException) e else IOException("upstream failed: ${e.message}", e)
        }

        // ---- Classify + transform ----
        val out = try {
            val kind = classify(raw)
            Log.d(TAG, "classify ${raw.size}B ${kind.name} → $url")
            transformSafe(kind, raw, url)
        } catch (e: Throwable) {
            Log.e(TAG, "transform failed for $url", e)
            raw  // fall through with raw bytes
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
        closeBuffered()
    }

    private fun closeBuffered() {
        try { buffered?.close() } catch (_: Throwable) {}
        buffered = null
    }

    private fun transformSafe(kind: Kind, raw: ByteArray, url: String): ByteArray = when (kind) {
        Kind.MPD -> {
            val text = String(raw, Charsets.UTF_8)
            val stripped = MpdStripper.stripContentProtection(text)
            Log.i(TAG, "MPD stripped: ${text.length}B → ${stripped.length}B")
            stripped.toByteArray(Charsets.UTF_8)
        }
        Kind.ISO_BMFF_INIT -> try {
            InitSegmentTransformer.transform(raw)
        } catch (e: Throwable) {
            Log.e(TAG, "Init transform failed for $url", e); raw
        }
        Kind.ISO_BMFF_MEDIA -> {
            val key = keyRef.get()
            if (key == null) {
                Log.w(TAG, "Media segment but no CENC key — passthrough")
                raw
            } else try {
                MediaSegmentTransformer.transform(raw, key)
            } catch (e: Throwable) {
                Log.e(TAG, "Media transform failed for $url", e); raw
            }
        }
        Kind.OTHER -> raw
    }

    private fun readAll(source: DataSource, declaredLength: Long): ByteArray {
        val initial = if (declaredLength in 1..MAX_SEGMENT_BYTES) declaredLength.toInt() else 32 * 1024
        val out = java.io.ByteArrayOutputStream(initial)
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = source.read(buf, 0, buf.size)
            if (read == C.RESULT_END_OF_INPUT) break
            if (read < 0) break
            total += read
            if (total > MAX_SEGMENT_BYTES) throw IOException("Segment too large: $total")
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun classify(data: ByteArray): Kind {
        if (data.size < 16) return Kind.OTHER

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

        val head = runCatching {
            String(data, 0, minOf(data.size, 512), Charsets.UTF_8)
        }.getOrNull()
        if (head != null) {
            val trimmed = head.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            if (trimmed.startsWith("<?xml", ignoreCase = true) ||
                trimmed.startsWith("<MPD", ignoreCase = true) ||
                "<MPD" in head.take(200)
            ) return Kind.MPD
        }
        return Kind.OTHER
    }
}

internal object MpdStripper {

    private val paired = Regex(
        """<ContentProtection\b[^>]*>.*?</ContentProtection>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val selfClosing = Regex(
        """<ContentProtection\b[^>]*/>""",
        RegexOption.IGNORE_CASE,
    )
    private val encCodecPrefix = Regex("""(codecs\s*=\s*["'])enc[vVaA]\.([^"']+)""")

    fun stripContentProtection(mpd: String): String {
        var r = paired.replace(mpd, "")
        r = selfClosing.replace(r, "")
        r = encCodecPrefix.replace(r) { m -> "${m.groupValues[1]}${m.groupValues[2]}" }
        return r
    }
}
