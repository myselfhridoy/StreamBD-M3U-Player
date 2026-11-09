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
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Upstream DataSource-কে wrap করে CENC-encrypted fMP4 segment গুলো decrypt করে।
 * Plain manifest/text ফাইল হলে pass-through।
 */
@UnstableApi
internal class CencDecryptingDataSource(
    private val upstream: DataSource,
    private val keyRef: AtomicReference<ByteArray?>,
) : DataSource {

    private companion object {
        const val TAG = "CencDataSource"
        const val MAX_SEGMENT_BYTES = 64 * 1024 * 1024
    }

    private var dataSpec: DataSpec? = null
    private var buffered: ByteArrayInputStream? = null
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.uri = dataSpec.uri

        val contentType = dataSpec.httpMethod.let { null }
        val url = dataSpec.uri.toString()

        // MPD, m3u8, text ফাইল — pass-through
        if (url.endsWith(".mpd", true) || url.endsWith(".m3u8", true) ||
            url.endsWith(".xml", true) || url.endsWith(".ism", true)) {
            return upstream.open(dataSpec)
        }

        // Full segment read
        val length = upstream.open(dataSpec)
        val bytes = readAll(upstream, length)
        upstream.close()

        val key = keyRef.get()
        if (key == null) {
            Log.w(TAG, "No CENC key — passing through ${bytes.size} bytes from $url")
            buffered = ByteArrayInputStream(bytes)
            return bytes.size.toLong()
        }

        val transformed = try {
            when {
                // Init segment — moov box থাকে
                looksLikeInitSegment(bytes) -> InitSegmentTransformer.transform(bytes)
                // Media segment — moof + mdat
                looksLikeMediaSegment(bytes) -> MediaSegmentTransformer.transform(bytes, key)
                else -> bytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transform failed for $url: ${e.message}", e)
            bytes
        }

        buffered = ByteArrayInputStream(transformed)
        return transformed.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val b = buffered ?: return upstream.read(buffer, offset, length)
        return b.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        buffered?.close()
        buffered = null
        try { upstream.close() } catch (_: Exception) {}
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
            total += read
            if (total > MAX_SEGMENT_BYTES) throw IOException("Segment too large: $total bytes")
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun looksLikeInitSegment(b: ByteArray): Boolean {
        var pos = 0
        while (pos + 8 <= b.size) {
            val h = CencByteUtils.readBoxHeader(b, pos) ?: return false
            if (h.type == "moov") return true
            if (h.type == "moof") return false
            pos += h.size
        }
        return false
    }

    private fun looksLikeMediaSegment(b: ByteArray): Boolean {
        var pos = 0
        while (pos + 8 <= b.size) {
            val h = CencByteUtils.readBoxHeader(b, pos) ?: return false
            if (h.type == "moof") return true
            pos += h.size
        }
        return false
    }
}
