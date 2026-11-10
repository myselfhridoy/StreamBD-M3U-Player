package com.streambd.iptv.player.cenc

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal object MediaSegmentTransformer {

    private const val TAG = "CencMedia"

    private val ENCRYPTION_BOXES = setOf("senc", "saiz", "saio", "sbgp", "sgpd", "tenc", "pssh")

    data class TrackInfo(
        val trackId: Long,
        val trunDataOffset: Long,
        val sampleSizes: List<Int>,
        val sampleEncryption: SencParser.SampleEncryption?,
    )

    fun transform(data: ByteArray, key: ByteArray): ByteArray {
        val headers = mutableListOf<CencByteUtils.BoxHeader>()
        var pos = 0
        while (pos + 8 <= data.size) {
            val h = CencByteUtils.readBoxHeader(data, pos) ?: break
            headers.add(h); pos += h.size
        }

        val moofHeader = headers.firstOrNull { it.type == "moof" } ?: return data
        val mdatHeader = headers.firstOrNull { it.type == "mdat" } ?: return data

        val tracks = parseMoof(data, moofHeader.payloadStart, moofHeader.payloadEnd)
        val encTracks = tracks.filter { it.sampleEncryption != null }
        if (encTracks.isEmpty()) {
            Log.d(TAG, "Media: no senc — pass through")
            return data
        }

        val mdatPayload = data.copyOfRange(mdatHeader.payloadStart, mdatHeader.payloadEnd)

        // ⭐ CRITICAL FIX: trun.data_offset is relative to moof start (usually 0).
        // Convert to offset within mdatPayload by subtracting mdat payload start.
        val moofStart = moofHeader.start  // usually 0
        val mdatPayloadStart = mdatHeader.payloadStart

        for (t in encTracks) {
            val enc = t.sampleEncryption ?: continue

            // trun.data_offset is absolute position from moof start
            // Convert: absolute_segment_position - mdatPayloadStart = offset in mdatPayload
            val absoluteOffset = moofStart + t.trunDataOffset
            var dataOffset = (absoluteOffset - mdatPayloadStart).toInt()

            Log.d(
                TAG,
                "Media: track=${t.trackId} trunOffset=${t.trunDataOffset} " +
                    "moofStart=$moofStart mdatPayloadStart=$mdatPayloadStart → dataOffset=$dataOffset",
            )

            if (dataOffset < 0 || dataOffset > mdatPayload.size) {
                Log.w(TAG, "Media: bad dataOffset $dataOffset for track ${t.trackId}")
                continue
            }

            for (i in 0 until enc.sampleCount) {
                val size = t.sampleSizes.getOrNull(i) ?: break
                if (dataOffset + size > mdatPayload.size) {
                    Log.w(TAG, "Media: sample $i exceeds payload at $dataOffset+$size")
                    break
                }
                val encBytes = mdatPayload.copyOfRange(dataOffset, dataOffset + size)
                val iv = enc.ivs[i]
                val subs = enc.subsamples[i]

                if (i == 0) {
                    Log.d(TAG, "Media: sample 0 size=$size ivSize=${iv.size} subs=${subs?.size ?: 0}")
                }

                val clear = try {
                    CencDecryptor.decryptSample(key, iv, encBytes, subs)
                } catch (e: Throwable) {
                    Log.e(TAG, "Media: decrypt sample $i failed", e)
                    return data
                }
                clear.copyInto(mdatPayload, dataOffset)
                dataOffset += size
            }
            Log.i(TAG, "Media: track ${t.trackId} decrypted ${enc.sampleCount} samples")
        }

        val moofPayload = data.copyOfRange(moofHeader.payloadStart, moofHeader.payloadEnd)
        val newMoofPayload = stripEncryptionBoxes(moofPayload)

        val out = ByteArrayOutputStream(data.size)
        out.write(data, 0, moofHeader.start)
        writeBox(out, "moof", newMoofPayload)
        out.write(data, moofHeader.payloadEnd, mdatHeader.start - moofHeader.payloadEnd)
        writeBox(out, "mdat", mdatPayload)
        out.write(data, mdatHeader.payloadEnd, data.size - mdatHeader.payloadEnd)
        return out.toByteArray()
    }

    private fun parseMoof(moofPayload: ByteArray, start: Int, end: Int): List<TrackInfo> {
        val tracks = mutableListOf<TrackInfo>()
        var pos = start
        while (pos + 8 <= end) {
            val h = CencByteUtils.readBoxHeader(moofPayload, pos) ?: break
            if (h.type == "traf") {
                parseTraf(moofPayload, h.payloadStart, h.payloadEnd)?.let { tracks.add(it) }
            }
            pos += h.size
        }
        return tracks
    }

    private fun parseTraf(traf: ByteArray, start: Int, end: Int): TrackInfo? {
        var trackId = -1L
        var trunDataOffset = 0L
        val sampleSizes = mutableListOf<Int>()
        var sencPayload: ByteArray? = null

        var pos = start
        while (pos + 8 <= end) {
            val h = CencByteUtils.readBoxHeader(traf, pos) ?: break
            when (h.type) {
                "tfhd" -> trackId = CencByteUtils.readUInt32(traf, h.payloadStart + 4)
                "trun" -> {
                    val t0 = h.payloadStart
                    val flags = (CencByteUtils.readUInt32(traf, t0) and 0xFFFFFF).toInt()
                    val sampleCount = CencByteUtils.readUInt32(traf, t0 + 4).toInt()
                    var p = t0 + 8
                    if (flags and 0x01 != 0) {
                        trunDataOffset = CencByteUtils.readUInt32(traf, p); p += 4
                    }
                    if (flags and 0x04 != 0) p += 4
                    val hasDur = flags and 0x100 != 0
                    val hasSize = flags and 0x200 != 0
                    val hasFlags = flags and 0x400 != 0
                    val hasCto = flags and 0x800 != 0
                    for (i in 0 until sampleCount) {
                        if (hasDur) p += 4
                        if (hasSize) {
                            sampleSizes.add(CencByteUtils.readUInt32(traf, p).toInt()); p += 4
                        }
                        if (hasFlags) p += 4
                        if (hasCto) p += 4
                    }
                }
                "senc" -> sencPayload = traf.copyOfRange(h.payloadStart, h.payloadEnd)
            }
            pos += h.size
        }

        if (trackId < 0) return null
        val ivSize = 8
        val encryption = sencPayload?.let {
            try {
                SencParser.parse(it, ivSize)
            } catch (e: Throwable) {
                Log.w(TAG, "senc parse failed track=$trackId: ${e.message}")
                null
            }
        }
        return TrackInfo(trackId, trunDataOffset, sampleSizes, encryption)
    }

    private fun stripEncryptionBoxes(moofPayload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(moofPayload.size)
        var pos = 0
        while (pos + 8 <= moofPayload.size) {
            val h = CencByteUtils.readBoxHeader(moofPayload, pos) ?: break
            when (h.type) {
                "traf" -> {
                    val trafPayload = moofPayload.copyOfRange(h.payloadStart, h.payloadEnd)
                    writeBox(out, "traf", stripInTraf(trafPayload))
                }
                "pssh" -> writeFreeBox(out, h.size)
                else -> out.write(moofPayload, h.start, h.size)
            }
            pos += h.size
        }
        return out.toByteArray()
    }

    private fun stripInTraf(trafPayload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(trafPayload.size)
        var pos = 0
        while (pos + 8 <= trafPayload.size) {
            val h = CencByteUtils.readBoxHeader(trafPayload, pos) ?: break
            if (h.type in ENCRYPTION_BOXES) writeFreeBox(out, h.size)
            else out.write(trafPayload, h.start, h.size)
            pos += h.size
        }
        return out.toByteArray()
    }

    private fun writeBox(out: ByteArrayOutputStream, type: String, payload: ByteArray) {
        CencByteUtils.writeUInt32(out, (8 + payload.size).toLong())
        out.write(type.toByteArray(StandardCharsets.US_ASCII))
        out.write(payload)
    }

    private fun writeFreeBox(out: ByteArrayOutputStream, size: Int) {
        CencByteUtils.writeUInt32(out, size.toLong())
        out.write("free".toByteArray(StandardCharsets.US_ASCII))
        for (i in 8 until size) out.write(0)
    }
}
