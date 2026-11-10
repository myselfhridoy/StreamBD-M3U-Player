package com.streambd.iptv.player.cenc

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CTR per-sample decryption for CENC 'cenc' / 'cens' schemes.
 *
 * ⭐ ISO/IEC 23001-7 Section 9.5.2: 8-byte IV-কে 16-byte counter block-এ pad করতে
 *    হয় IV + 8 zero bytes দিয়ে — NOT zeros + IV। আগের code-এ ভুল ছিল।
 */
internal object CencDecryptor {

    fun decryptSample(
        key: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        subsamples: List<Pair<Int, Int>>?,
    ): ByteArray {
        require(key.size == 16) { "AES key must be 16 bytes, got ${key.size}" }
        require(iv.size == 8 || iv.size == 16) { "IV must be 8 or 16 bytes, got ${iv.size}" }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        // ⭐ FIX: IV first, then pad with zeros. Not the other way around.
        val fullIv = if (iv.size == 16) iv else iv + ByteArray(8)

        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(fullIv),
        )

        if (subsamples.isNullOrEmpty()) {
            return cipher.doFinal(ciphertext)
        }

        val out = ByteArray(ciphertext.size)
        var inPos = 0
        var outPos = 0
        for ((clearLen, encLen) in subsamples) {
            if (clearLen > 0) {
                ciphertext.copyInto(out, outPos, inPos, inPos + clearLen)
                inPos += clearLen
                outPos += clearLen
            }
            if (encLen > 0) {
                val decrypted = cipher.update(ciphertext, inPos, encLen)
                decrypted.copyInto(out, outPos)
                inPos += encLen
                outPos += decrypted.size
            }
        }
        return out
    }
}
