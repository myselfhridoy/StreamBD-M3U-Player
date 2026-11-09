package com.streambd.iptv.player.cenc

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal object CencByteUtils {

    fun readUInt32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun writeUInt32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    fun writeUInt32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    fun boxType(data: ByteArray, offset: Int): String =
        String(data, offset + 4, 4, StandardCharsets.US_ASCII)

    /**
     * @return (boxStart, boxSize, headerSize, payloadStart, payloadEnd)
     *         অথবা null যদি invalid box হয়।
     */
    fun readBoxHeader(data: ByteArray, offset: Int): BoxHeader? {
        if (offset + 8 > data.size) return null
        val size32 = readUInt32(data, offset)
        val type = boxType(data, offset)
        if (size32 < 8 || type.any { !it.isLetterOrDigit() && it != ' ' && it != '-' }) return null

        var headerSize = 8
        var actualSize = size32
        if (size32 == 1L) {
            if (offset + 16 > data.size) return null
            actualSize = readUInt32(data, offset + 8) shl 32 or readUInt32(data, offset + 12)
            headerSize = 16
        } else if (size32 == 0L) {
            actualSize = (data.size - offset).toLong()
        }

        if (actualSize < headerSize || offset + actualSize > data.size) return null

        var payloadStart = offset + headerSize
        if (type == "uuid") {
            if (payloadStart + 16 > data.size) return null
            payloadStart += 16
            headerSize += 16
        }

        return BoxHeader(
            type = type,
            start = offset,
            size = actualSize.toInt(),
            headerSize = headerSize,
            payloadStart = payloadStart,
            payloadEnd = offset + actualSize.toInt(),
        )
    }

    data class BoxHeader(
        val type: String,
        val start: Int,
        val size: Int,
        val headerSize: Int,
        val payloadStart: Int,
        val payloadEnd: Int,
    )
}
