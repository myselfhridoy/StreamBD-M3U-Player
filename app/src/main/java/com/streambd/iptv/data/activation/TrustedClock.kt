package com.streambd.iptv.data.activation

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** সার্ভারের সময় ধরে চলা ঘড়ি। ফোনের ঘড়ি পেছালেও সময় পেছায় না। */
@Singleton
class TrustedClock @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val sp = ctx.getSharedPreferences("streambd_clock", Context.MODE_PRIVATE)

    private fun bootCount(): Int =
        Settings.Global.getInt(ctx.contentResolver, "boot_count", -1)

    /** সার্ভার থেকে সময় পেলেই কল করুন। */
    fun sync(serverTime: Long) {
        if (serverTime <= 0L) return
        sp.edit()
            .putLong("server", serverTime)
            .putLong("elapsed", SystemClock.elapsedRealtime())
            .putInt("boot", bootCount())
            .putLong("last", serverTime)
            .apply()
    }

    fun now(): Long {
        val server = sp.getLong("server", 0L)
        val elapsedAt = sp.getLong("elapsed", 0L)
        val last = sp.getLong("last", 0L)
        val sameBoot = sp.getInt("boot", -1) == bootCount()
        val elapsed = SystemClock.elapsedRealtime()

        val est = if (server > 0L && sameBoot && elapsed >= elapsedAt) {
            server + (elapsed - elapsedAt)          // রিবুট হয়নি: সার্ভারের সময় + চলে যাওয়া সময়
        } else {
            maxOf(last, System.currentTimeMillis()) // রিবুটের পর: শেষ জানা সময়ের আগে যাবে না
        }
        if (est - last > 60_000L) sp.edit().putLong("last", est).apply()
        return est
    }
}
