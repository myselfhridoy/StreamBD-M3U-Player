package com.streambd.iptv.data.addon

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** JS `crypto` ব্রিজ: hash / hmac / AES / random / convert। ভুল হলে "\u0000ERR:..." ফেরত। */
internal object AddonCrypto {
    const val ERR = "\u0000ERR:"
    private val rng = SecureRandom()

    fun handle(json: String): String = try {
        val o = JSONObject(json)
        when (o.getString("op")) {
            "hash" -> {
                val md = MessageDigest.getInstance(digestName(o.getString("alg")))
                encode(md.digest(decode(o.optString("data"), o.optString("dataEnc", "utf8"))), o.optString("out", "hex"))
            }
            "hmac" -> {
                val name = "Hmac" + digestName(o.getString("alg")).replace("-", "")
                val mac = Mac.getInstance(name)
                val key = decode(o.optString("key"), o.optString("keyEnc", "utf8"))
                mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, name))
                encode(mac.doFinal(decode(o.optString("data"), o.optString("dataEnc", "utf8"))), o.optString("out", "hex"))
            }
            "aes" -> aes(o)
            "random" -> {
                val n = o.optInt("n", 16).coerceIn(1, 1024)
                encode(ByteArray(n).also { rng.nextBytes(it) }, "hex")
            }
            "convert" -> encode(decode(o.optString("data"), o.optString("dataEnc", "utf8")), o.optString("out", "hex"))
            else -> ERR + "unknown crypto operation"
        }
    } catch (e: Exception) {
        ERR + (e.message ?: e.javaClass.simpleName)
    }

    private fun digestName(alg: String): String = when (alg.lowercase().replace("-", "")) {
        "md5" -> "MD5"
        "sha1" -> "SHA-1"
        "sha256" -> "SHA-256"
        "sha384" -> "SHA-384"
        "sha512" -> "SHA-512"
        else -> throw IllegalArgumentException("Unsupported algorithm: $alg")
    }

    private fun aes(o: JSONObject): String {
        val encrypt = o.optBoolean("encrypt", true)
        val mode = o.optString("mode", "cbc").lowercase()
        val padding = if (o.optString("padding", "pkcs7").lowercase() == "none") "NoPadding" else "PKCS5Padding"
        val key = decode(o.optString("key"), o.optString("keyEnc", "utf8"))
        if (key.size != 16 && key.size != 24 && key.size != 32) throw IllegalArgumentException("AES key must be 16, 24 or 32 bytes")
        val iv = decode(o.optString("iv"), o.optString("ivEnc", "utf8"))
        val data = decode(o.optString("data"), o.optString("dataEnc", "utf8"))
        val opMode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        val spec = SecretKeySpec(key, "AES")

        val cipher: Cipher = when (mode) {
            "cbc" -> Cipher.getInstance("AES/CBC/$padding").also { it.init(opMode, spec, IvParameterSpec(iv)) }
            "ecb" -> Cipher.getInstance("AES/ECB/$padding").also { it.init(opMode, spec) }
            "ctr" -> Cipher.getInstance("AES/CTR/NoPadding").also { it.init(opMode, spec, IvParameterSpec(iv)) }
            "gcm" -> Cipher.getInstance("AES/GCM/NoPadding").also {
                it.init(opMode, spec, GCMParameterSpec(o.optInt("tagBits", 128), iv))
                val aad = o.optString("aad")
                if (aad.isNotEmpty()) it.updateAAD(aad.toByteArray(Charsets.UTF_8))
            }
            else -> throw IllegalArgumentException("Unsupported AES mode: $mode")
        }
        return encode(cipher.doFinal(data), o.optString("out", if (encrypt) "base64" else "utf8"))
    }

    private fun decode(value: String, enc: String): ByteArray = when (enc.lowercase()) {
        "hex" -> hexToBytes(value)
        "base64" -> Base64.decode(value.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
        else -> value.toByteArray(Charsets.UTF_8)
    }

    private fun encode(bytes: ByteArray, enc: String): String = when (enc.lowercase()) {
        "base64" -> Base64.encodeToString(bytes, Base64.NO_WRAP)
        "utf8" -> String(bytes, Charsets.UTF_8)
        else -> bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.trim().removePrefix("0x")
        require(h.length % 2 == 0) { "Invalid hex string" }
        return ByteArray(h.length / 2) { i ->
            val hi = Character.digit(h[i * 2], 16)
            val lo = Character.digit(h[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex string" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
