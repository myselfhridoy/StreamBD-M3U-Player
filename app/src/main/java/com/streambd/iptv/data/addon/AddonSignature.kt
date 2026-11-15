package com.streambd.iptv.data.addon

import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * অফিশিয়াল ক্যাটালগের Add-on সাইনিং (ECDSA P-256 / SHA-256)।
 * সাইন করা বার্তা: "streambd-addon-v1\n<id>\n<version>\n<sha256(script) hex>"।
 * সাইনিং করে Worker; এখানে শুধু যাচাই। প্রাইভেট কী অ্যাপে নেই।
 */
object AddonSignature {
    /** `node gen-addon-key.mjs` এর PUBLIC অংশ এখানে বসান। খালি/ভুল হলে কোনো সিগনেচারই বৈধ হবে না। */
    const val PUBLIC_KEY_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1XF4h5iV71ENhPTVn8UoWjFCnbn9jhJkBdMg9oSHq/bjQxjQyooSiAc8f4a+N0SVYXK+MZsYXD69mShJCDowxw=="

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** [script] এর হ্যাশ নিজে হিসাব করে যাচাই করা হয় (সার্ভারের দেওয়া হ্যাশ বিশ্বাস করা হয় না)। */
    fun verify(id: String, version: String, script: String, signatureB64: String): Boolean = try {
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(key)
        sig.update("streambd-addon-v1\n$id\n$version\n${sha256Hex(script)}".toByteArray(Charsets.UTF_8))
        sig.verify(Base64.decode(signatureB64, Base64.DEFAULT))
    } catch (e: Exception) {
        false
    }
}
