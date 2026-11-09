package com.streambd.iptv.player.cenc

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Init segment (moov) থেকে stsd-এর ভেতরের `sinf` box সরিয়ে দেয় এবং
 * `encv`/`enca`-কে আসল format-এ (`avc1`/`mp4a`/`hvc1`/...) rename করে।
 *
 * এর ফলে ExoPlayer FragmentedMp4Extractor এই track-কে plain হিসেবে দেখে এবং
 * MediaCodec clear mode-এ চলে। sample-level ডিক্রিপশন DataSource করে।
 */
internal object InitSegmentTransformer {

    private const val TAG = "CencInit"
    private val SAMPLE_ENTRY_PREFIX = mapOf(
        "avc1" to 78, "avc3" to 78, "hvc1" to 78, "hev1" to 78,
        "encv" to 78, "mp4v" to 78, "av01" to 78, "vp08" to 78, "vp09" to 78,
        "mp4a" to 28, "enca" to 28, "ac-3" to 28, "ec-3" to 28, "ac-4" to 28,
        "dtsc" to 28, "dtsh" to 28, "dtsl" to 28, "dtse" to 28,
        "Opus" to 28, "fLaC" to 28, "alac" to 28,
    )

    fun transform(data: ByteArray): ByteArray {
        var pos = 0
        while (pos + 8 <= data.size) {
            val header = CencByteUtils.readBoxHeader(data, pos) ?: break
            if (header.type == "moov") {
                val moovPayload = data.copyOfRange(header.payloadStart, header.payloadEnd)
                val newMoov = transformMoov(moovPayload)
                if (newMoov.contentEquals(moovPayload)) {
                    Log.d(TAG, "Init: moov unchanged (no encryption)")
                    return data
                }
                val out = ByteArrayOutputStream(data.size)
                out.write(data, 0, header.start)
                writeBox(out, "moov", newMoov)
                out.write(data, header.payloadEnd, data.size - header.payloadEnd)
                Log.i(TAG, "Init: moov rewritten, size ${moovPayload.size} → ${newMoov.size}")
                return out.toByteArray()
            }
            pos += header.size
        }
        return data
    }

    private fun transformMoov(moovPayload: ByteArray): ByteArray {
        var changed = false
        val out = ByteArrayOutputStream(moovPayload.size)
        var pos = 0
        while (pos + 8 <= moovPayload.size) {
            val header = CencByteUtils.readBoxHeader(moovPayload, pos) ?: break
            if (header.type == "trak") {
                val trakPayload = moovPayload.copyOfRange(header.payloadStart, header.payloadEnd)
                val newTrak = transformTrak(trakPayload)
                if (newTrak.contentEquals(trakPayload)) {
                    out.write(moovPayload, header.start, header.size)
                } else {
                    writeBox(out, "trak", newTrak)
                    changed = true
                }
            } else {
                out.write(moovPayload, header.start, header.size)
            }
            pos += header.size
        }
        return if (changed) out.toByteArray() else moovPayload
    }

    private fun transformTrak(trakPayload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(trakPayload.size)
        var pos = 0
        var changed = false
        while (pos + 8 <= trakPayload.size) {
            val header = CencByteUtils.readBoxHeader(trakPayload, pos) ?: break
            if (header.type == "mdia") {
                val mdiaPayload = trakPayload.copyOfRange(header.payloadStart, header.payloadEnd)
                val newMdia = transformMdia(mdiaPayload)
                if (newMdia.contentEquals(mdiaPayload)) {
                    out.write(trakPayload, header.start, header.size)
                } else {
                    writeBox(out, "mdia", newMdia)
                    changed = true
                }
            } else {
                out.write(trakPayload, header.start, header.size)
            }
            pos += header.size
        }
        return if (changed) out.toByteArray() else trakPayload
    }

    private fun transformMdia(mdiaPayload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(mdiaPayload.size)
        var pos = 0
        var changed = false
        while (pos + 8 <= mdiaPayload.size) {
            val header = CencByteUtils.readBoxHeader(mdiaPayload, pos) ?: break
            if (header.type == "minf") {
                val minfPayload = mdiaPayload.copyOfRange(header.payloadStart, header.payloadEnd)
                val newMinf = transformMinf(minfPayload)
                if (newMinf.contentEquals(minfPayload)) {
                    out.write(mdiaPayload, header.start, header.size)
                } else {
                    writeBox(out, "minf", newMinf)
                    changed = true
                }
            } else {
                out.write(mdiaPayload, header.start, header.size)
            }
            pos += header.size
        }
        return if (changed) out.toByteArray() else mdiaPayload
    }

    private fun transformMinf(minfPayload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(minfPayload.size)
        var pos = 0
        var changed = false
        while (pos + 8 <= minfPayload.size) {
            val header = CencByteUtils.readBoxHeader(minfPayload, pos) ?: break
            if (header.type == "stbl") {
                val stblPayload = minfPayload.copyOfRange(header.payloadStart, header.payloadEnd)
                val newStbl = transformStbl(stblPayload)
                if (newStbl.contentEquals(stblPayload)) {
                    out.write(minfPayload, header.start, header.size)
                } else {
                    writeBox(out, "stbl", newStbl)
                    changed = true
                }
            } else {
                out.write(minfPayload, header.start, header.size)
            }
            pos += header.size
        }
        return if (changed) out.toByteArray() else minfPayload
    }

    private fun transformStbl(stblPayload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(stblPayload.size)
        var pos = 0
        var changed = false
        while (pos + 8 <= stblPayload.size) {
            val header = CencByteUtils.readBoxHeader(stblPayload, pos) ?: break
            if (header.type == "stsd") {
                val stsdPayload = stblPayload.copyOfRange(header.payloadStart, header.payloadEnd)
                val newStsd = transformStsd(stsdPayload)
                if (newStsd.contentEquals(stsdPayload)) {
                    out.write(stblPayload, header.start, header.size)
                } else {
                    writeBox(out, "stsd", newStsd)
                    changed = true
                }
            } else {
                out.write(stblPayload, header.start, header.size)
            }
            pos += header.size
        }
        return if (changed) out.toByteArray() else stblPayload
    }

    private fun transformStsd(stsdPayload: ByteArray): ByteArray {
        // stsd: 4 version+flags, 4 entry_count, entries
        if (stsdPayload.size < 8) return stsdPayload
        val out = ByteArrayOutputStream(stsdPayload.size)
        out.write(stsdPayload, 0, 8)  // prefix preserved

        var pos = 8
        var changed = false
        while (pos + 8 <= stsdPayload.size) {
            val header = CencByteUtils.readBoxHeader(stsdPayload, pos) ?: break
            val entryPayload = stsdPayload.copyOfRange(header.payloadStart, header.payloadEnd)

            val newEntry = if (header.type == "encv" || header.type == "enca") {
                transformSampleEntry(header.type, entryPayload)
            } else null

            if (newEntry != null) {
                writeBox(out, newEntry.first, newEntry.second)
                changed = true
            } else {
                out.write(stsdPayload, header.start, header.size)
            }
            pos += header.size
        }
        return if (changed) out.toByteArray() else stsdPayload
    }

    /**
     * Sample entry থেকে `sinf` সরিয়ে original format-এ rename করে।
     * @return (newType, newPayload) অথবা null যদি পরিবর্তন না লাগে।
     */
    private fun transformSampleEntry(entryType: String, payload: ByteArray): Pair<String, ByteArray>? {
        val prefixLen = SAMPLE_ENTRY_PREFIX[entryType] ?: return null
        if (payload.size < prefixLen) return null

        val prefix = payload.copyOfRange(0, prefixLen)
        val childrenStart = prefixLen

        // Walk child boxes to find 'sinf' and 'frma'
        var originalType: String? = null
        var sinfStart = -1
        var sinfSize = 0
        var pos = childrenStart
        while (pos + 8 <= payload.size) {
            val h = CencByteUtils.readBoxHeader(payload, pos) ?: break
            if (h.type == "sinf") {
                val sinfPayload = payload.copyOfRange(h.payloadStart, h.payloadEnd)
                originalType = findFrma(sinfPayload)
                sinfStart = h.start
                sinfSize = h.size
                break
            }
            pos += h.size
        }

        if (sinfStart < 0 || originalType.isNullOrBlank()) return null

        val out = ByteArrayOutputStream(payload.size)
        out.write(prefix)
        // child boxes — sinf বাদ দিয়ে
        pos = childrenStart
        while (pos + 8 <= payload.size) {
            val h = CencByteUtils.readBoxHeader(payload, pos) ?: break
            if (h.start != sinfStart) {
                out.write(payload, h.start, h.size)
            }
            pos += h.size
        }

        Log.i(TAG, "Init: $entryType → $originalType, stripped sinf ($sinfSize bytes)")
        return originalType to out.toByteArray()
    }

    private fun findFrma(sinfPayload: ByteArray): String? {
        var pos = 0
        while (pos + 8 <= sinfPayload.size) {
            val h = CencByteUtils.readBoxHeader(sinfPayload, pos) ?: break
            if (h.type == "frma") {
                val len = h.payloadEnd - h.payloadStart
                if (len >= 4) {
                    return String(sinfPayload, h.payloadStart, 4, StandardCharsets.US_ASCII)
                }
            }
            pos += h.size
        }
        return null
    }

    private fun writeBox(out: ByteArrayOutputStream, type: String, payload: ByteArray) {
        CencByteUtils.writeUInt32(out, (8 + payload.size).toLong())
        out.write(type.toByteArray(StandardCharsets.US_ASCII))
        out.write(payload)
    }
}
