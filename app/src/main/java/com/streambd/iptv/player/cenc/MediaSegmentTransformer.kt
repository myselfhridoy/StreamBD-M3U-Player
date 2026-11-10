package com.streambd.iptv.player.cenc

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal object MediaSegmentTransformer {

    private const val TAG = "CencMedia"

    private val ENCRYPTION_BOXES =
        setOf("senc", "saiz", "saio", "sbgp", "sgpd", "tenc", "pssh")

    data class TrackInfo(
        val trackId: Long,
        val trunDataOffset: Long,
        val hasExplicitDataOffset: Boolean,
        val sampleSizes: List<Int>,
        val sampleEncryption: SencParser.SampleEncryption?,
    )

    fun transform(data: ByteArray, key: ByteArray): ByteArray {
        val headers = mutableListOf<CencByteUtils.BoxHeader>()
        var pos = 0
        while (pos + 8 <= data.size) {
            val h = CencByteUtils.readBoxHeader(data, pos) ?: break
            headers.add(h)
            pos += h.size
        }

        val moofHeader = headers.firstOrNull { it.type == "moof" } ?: return data
        val mdatHeader = headers.firstOrNull { it.type == "mdat" } ?: return data

        val tracks = parseMoof(data, moofHeader, mdatHeader)
        val encTracks = tracks.filter { it.sampleEncryption != null }
        if (encTracks.isEmpty()) {
            Log.d(TAG, "Media: no senc — pass through")
            return data
        }

        val mdatPayload = data.copyOfRange(mdatHeader.payloadStart, mdatHeader.payloadEnd)
        var totalDecrypted = 0

        for (t in encTracks) {
            val enc = t.sampleEncryption ?: continue

            // trun.data_offset is relative to moof box start.
            // If absent, spec says "immediately after previous run" — for the
            // first run this means "right after moof", which is mdat payload start.
            val relativeOffset = if (t.hasExplicitDataOffset) {
                t.trunDataOffset
            } else {
                // Fall back: samples start right after moof box (mdat payload)
                (mdatHeader.payloadStart - moofHeader.start).toLong()
            }

            val absoluteSampleStart = moofHeader.start + relativeOffset
            var dataOffset = (absoluteSampleStart - mdatHeader.payloadStart).toInt()

            Log.i(
                TAG,
                "Media: track=${t.trackId} hasOffset=${t.hasExplicitDataOffset} " +
                    "trunOff=${t.trunDataOffset} rel=$relativeOffset " +
                    "moofStart=${moofHeader.start} mdatPayStart=${mdatHeader.payloadStart} " +
                    "→ dataOffset=$dataOffset mdatSize=${mdatPayload.size}",
            )

            if (dataOffset < 0 || dataOffset >= mdatPayload.size) {
                Log.e(TAG, "Media: ABORT — dataOffset=$dataOffset out of range for track=${t.trackId}")
                return data
            }

            var samplesInThisTrack = 0
            for (i in 0 until enc.sampleCount) {
                val size = t.sampleSizes.getOrNull(i) ?: run {
                    Log.e(TAG, "Media: ABORT — missing size for sample $i")
                    return data
                }
                if (dataOffset + size > mdatPayload.size) {
                    Log.e(TAG, "Media: ABORT — sample $i overflow")
                    return data
                }

                val encBytes = mdatPayload.copyOfRange(dataOffset, dataOffset + size)
                val iv = enc.ivs[i] ?: run {
                    Log.e(TAG, "Media: ABORT — missing IV for sample $i")
                    return data
                }
                val subs = enc.subsamples[i]

                if (i == 0) {
                    Log.d(
                        TAG,
                        "Media: sample0 size=$size ivSize=${iv.size} " +
                            "subs=${subs?.size ?: 0} encFirst4=${hex4(encBytes)}",
                    )
                }

                val clear = try {
                    CencDecryptor.decryptSample(key, iv, encBytes, subs)
                } catch (e: Throwable) {
                    Log.e(TAG, "Media: ABORT — decrypt failed at sample $i", e)
                    return data
                }

                if (i == 0) {
                    Log.d(TAG, "Media: sample0 clearFirst4=${hex4(clear)}")
                }

                clear.copyInto(mdatPayload, dataOffset)
                dataOffset += size
                samplesInThisTrack++
                totalDecrypted += size
            }
            Log.i(TAG, "Media: track ${t.trackId} decrypted $samplesInThisTrack samples")
        }

        if (totalDecrypted == 0) {
            Log.w(TAG, "Media: nothing decrypted — passthrough")
            return data
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

    private fun hex4(b: ByteArray): String =
        if (b.size < 4) "??" else String.format("%02x%02x%02x%02x", b[0], b[1], b[2], b[3])

    private fun parseMoof(
        data: ByteArray,
        moofHeader: CencByteUtils.BoxHeader,
        mdatHeader: CencByteUtils.BoxHeader,
    ): List<TrackInfo> {
        val moofPayload = data.copyOfRange(moofHeader.payloadStart, moofHeader.payloadEnd)
        val tracks = mutableListOf<TrackInfo>()
        var pos = 0
        while (pos + 8 <= moofPayload.size) {
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
        var hasExplicitDataOffset = false
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
                        trunDataOffset = CencByteUtils.readUInt32(traf, p)
                        hasExplicitDataOffset = true
                        p += 4
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
        return TrackInfo(trackId, trunDataOffset, hasExplicitDataOffset, sampleSizes, encryption)
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
