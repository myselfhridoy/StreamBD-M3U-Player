package com.streambd.iptv.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log

/**
 * Robust TV detection for real Android TV, cheap Chinese TV boxes
 * (TX3 Mini, MXQ, H96, X88, Tanix, …), tablets, and phones.
 *
 * Cheap boxes typically fail the official `FEATURE_LEANBACK` check, so we
 * combine multiple signals and accept "any one matches". This is intentionally
 * generous — a false-positive (treating a big tablet as TV) is much less
 * harmful than a false-negative (a TV box stuck in mobile layout).
 */
object DeviceType {

    private const val TAG = "DeviceType"

    @Volatile private var cachedIsTv: Boolean? = null

    fun isTv(context: Context): Boolean {
        cachedIsTv?.let { return it }
        synchronized(this) {
            cachedIsTv?.let { return it }
            val result = detectTv(context.applicationContext)
            cachedIsTv = result
            return result
        }
    }

    private fun detectTv(context: Context): Boolean {
        val pm = context.packageManager
        val config = context.resources.configuration

        // 1) UI mode — real Android TV & most boxes
        val uiMode = config.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) {
            Log.d(TAG, "isTv=true (uiMode=TELEVISION)")
            return true
        }

        // 2) Leanback — official Android TV
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            Log.d(TAG, "isTv=true (FEATURE_LEANBACK)")
            return true
        }

        // 3) Legacy television feature
        if (pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
            Log.d(TAG, "isTv=true (FEATURE_TELEVISION)")
            return true
        }

        // 4) No touchscreen → almost certainly a TV or set-top box
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            Log.d(TAG, "isTv=true (no touchscreen)")
            return true
        }

        // 5) Model / product / device-name / hardware heuristics.
        val model = (
            Build.MODEL + " " +
                Build.PRODUCT + " " +
                Build.DEVICE + " " +
                Build.BRAND + " " +
                Build.MANUFACTURER + " " +
                Build.HARDWARE
            ).lowercase()
        val boxPatterns = listOf(
            "tx3", "tx5", "tx6", "tx8", "tx9",
            "mxq", "mx10", "m8s", "m8", "mxiii",
            "h96", "x88", "x96", "t95", "a95",
            "s905", "s912", "s922", "rk33", "rk32",
            "amlogic", "sunvell", "wetek", "beelink", "minix", "ugoos",
            "tv box", "androidtv", "android tv", "smarttv", "smart tv",
            "bravia", "firetv", "fire tv", "nexus player", "shield",
            "meson", "gxl", "g12"
        )
        if (boxPatterns.any { model.contains(it) }) {
            Log.d(TAG, "isTv=true (model pattern matched in: $model)")
            return true
        }

        // 6) Landscape-only + no telephony + no sensors
        val hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val hasAccel = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER)
        val hasGyro = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_GYROSCOPE)
        val isLandscapeOnly = config.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                !hasAccel && !hasGyro
        if (isLandscapeOnly && !hasTelephony) {
            Log.d(TAG, "isTv=true (landscape-only, no telephony, no sensors)")
            return true
        }

        Log.d(TAG, "isTv=false (all checks failed — model=$model)")
        return false
    }
}

/**
 * Extension function যাতে আগের মতোই সব জায়গায় `context.isTelevision()` কল করা যায়।
 */
fun Context.isTelevision(): Boolean {
    return DeviceType.isTv(this)
}
