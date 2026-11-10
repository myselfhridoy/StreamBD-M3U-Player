package com.streambd.iptv.player.cenc

/**
 * Sample Encryption Box ('senc') পার্সার। CENC spec (ISO/IEC 23001-7 § 7.2):
 *
 * senc:
 *   4 bytes: version(1) + flags(3)
 *   4 bytes: sample_count
 *   প্রতি sample-এ:
 *      IV[defaultIvSize]                  ← সবসময় থাকে, কোন flag-ই বলার দরকার নেই
 *      if (flags & 0x02):
 *          2 bytes: subsample_count
 *          প্রতি subsample-এ 2+4 bytes
 *
 * ⭐ Bug ছিল: আগে IV পড়া হত শুধু `flags & 0x01` হলে। কিন্তু flags=0x02 হলে IV
 *    পড়া হত না — IV-এর বাইটগুলো subsample_count হিসেবে পড়া হচ্ছিল, সব garbage।
 */
internal object SencParser {

    data class SampleEncryption(
        val sampleCount: Int,
        val ivs: Array<ByteArray>,
        val subsamples: Array<List<Pair<Int, Int>>?>,
    )

    fun parse(sencPayload: ByteArray, defaultIvSize: Int): SampleEncryption {
        if (sencPayload.size < 8) {
            throw IllegalStateException("senc too short: ${sencPayload.size}")
        }

        val versionAndFlags = CencByteUtils.readUInt32(sencPayload, 0)
        val flags = (versionAndFlags and 0xFFFFFF).toInt()
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
            // ⭐ IV সবসময় থাকে
            if (pos + defaultIvSize > sencPayload.size) {
                throw IllegalStateException("senc IV truncated at sample $i (pos=$pos, size=${sencPayload.size})")
            }
            ivs[i] = sencPayload.copyOfRange(pos, pos + defaultIvSize)
            pos += defaultIvSize

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

        // ⭐ পুরো box ঠিকঠাক consume হয়েছে কিনা verify — ভুল IV size ধরার জন্য
        if (pos != sencPayload.size) {
            throw IllegalStateException("senc size mismatch: read $pos, payload ${sencPayload.size}")
        }

        return SampleEncryption(sampleCount, ivs, subsamples)
    }
}
