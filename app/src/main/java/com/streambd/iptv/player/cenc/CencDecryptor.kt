package com.streambd.iptv.player.cenc

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CTR per-sample decryption (CENC 'cenc' / 'cens' schemes).
 * 'cbcs' (pattern) scheme-supported না — IVS এই scheme ব্যবহার করে না।
 */
internal object CencDecryptor {

    fun decryptSample(
        key: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        subsamples: List<Pair<Int, Int>>?,
    ): ByteArray {
        if (key.size != 16) throw IllegalArgumentException("AES key must be 16 bytes, got ${key.size}")
        if (iv.size != 8 && iv.size != 16) throw IllegalArgumentException("IV must be 8 or 16 bytes, got ${iv.size}")

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val fullIv = if (iv.size == 16) iv else ByteArray(8) + iv
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(fullIv),
        )

        if (subsamples == null || subsamples.isEmpty()) {
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
