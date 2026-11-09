package com.streambd.iptv.player.cenc

/**
 * Sample Encryption Box ('senc') পার্স করে। CENC spec-এ (ISO/IEC 23001-7):
 *
 * senc:
 *   4 bytes: version+flags
 *   4 bytes: sample_count
 *   for each sample:
 *     if flags & 0x01: [defaultIvSize bytes IV]
 *     if flags & 0x02: 2 bytes subsample_count, then each subsample 2+4 bytes
 */
internal object SencParser {

    data class SampleEncryption(
        val sampleCount: Int,
        val ivs: Array<ByteArray>,
        val subsamples: Array<List<Pair<Int, Int>>?>,
    )

    fun parse(
        sencPayload: ByteArray,   // senc box-এর payload (header বাদ দিয়ে)
        defaultIvSize: Int,       // tenc থেকে আসে, সাধারণত 8
    ): SampleEncryption {
        if (sencPayload.size < 8) throw IllegalStateException("senc too short: ${sencPayload.size}")

        val versionAndFlags = CencByteUtils.readUInt32(sencPayload, 0)
        val flags = (versionAndFlags and 0xFFFFFF).toInt()
        val hasIvOverride = (flags and 0x01) != 0
        val hasSubsample = (flags and 0x02) != 0

        var pos = 4
        val sampleCount = CencByteUtils.readUInt32(sencPayload, pos).toInt()
        pos += 4

        if (sampleCount < 0 || sampleCount > 100_000) {
            throw IllegalStateException("senc sample_count out of range: $sampleCount")
        }

        val ivs = Array(sampleCount) { ByteArray(defaultIvSize) }
        val subsamples = arrayOfNulls<List<Pair<Int, Int>>>(sampleCount)

        for (i in 0 until sampleCount) {
            if (hasIvOverride) {
                if (pos + defaultIvSize > sencPayload.size) {
                    throw IllegalStateException("senc IV truncated at sample $i")
                }
                ivs[i] = sencPayload.copyOfRange(pos, pos + defaultIvSize)
                pos += defaultIvSize
            }
            if (hasSubsample) {
                if (pos + 2 > sencPayload.size) {
                    throw IllegalStateException("senc subsample_count truncated at sample $i")
                }
                val subCount = CencByteUtils.readUInt16(sencPayload, pos)
                pos += 2
                val list = ArrayList<Pair<Int, Int>>(subCount)
                for (j in 0 until subCount) {
                    if (pos + 6 > sencPayload.size) {
                        throw IllegalStateException("senc subsample $j truncated at sample $i")
                    }
                    val clear = CencByteUtils.readUInt16(sencPayload, pos)
                    pos += 2
                    val enc = CencByteUtils.readUInt32(sencPayload, pos).toInt()
                    pos += 4
                    list.add(clear to enc)
                }
                subsamples[i] = list
            }
        }

        return SampleEncryption(sampleCount, ivs, subsamples)
    }
}
