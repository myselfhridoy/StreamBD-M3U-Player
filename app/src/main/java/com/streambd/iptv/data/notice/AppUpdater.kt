package com.streambd.iptv.data.notice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.streambd.iptv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * অ্যাপের ভেতরে APK নামিয়ে ইনস্টল স্ক্রিন খোলে (GitHub Releases ইত্যাদি সরাসরি .apk লিংক থেকে)।
 * ইনস্টল নিশ্চিত করে অ্যান্ড্রয়েডের নিজস্ব ইনস্টলার; আমরা শুধু ফাইলটা যাচাই করে হস্তান্তর করি।
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val ctx: Context,
    http: OkHttpClient,
) {
    sealed interface State {
        object Idle : State
        data class Downloading(val percent: Int) : State   // -1 = আকার অজানা
        object NeedPermission : State
        data class Failed(val message: String) : State
    }

    private companion object { const val TAG = "AppUpdater" }

    private val client = http.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val dir get() = File(ctx.externalCacheDir ?: ctx.cacheDir, "updates")
    private val apk get() = File(dir, "update.apk")

    /** সরাসরি APK লিংক হলে অ্যাপের ভেতরে আপডেট করা যায়; নইলে ব্রাউজারে খুলতে হবে। */
    fun canUpdateInApp(url: String): Boolean {
        val u = url.trim().substringBefore('?').substringBefore('#').lowercase()
        return u.startsWith("http") && (u.endsWith(".apk") || "/releases/download/" in u)
    }

    fun clearError() { if (_state.value is State.Failed || _state.value is State.NeedPermission) _state.value = State.Idle }

    fun start(url: String) {
        if (job?.isActive == true) return
        _state.value = State.Downloading(-1)
        job = scope.launch {
            try {
                if (!isNewerApk(apk)) download(url)
                if (!isNewerApk(apk)) throw IllegalStateException("The downloaded file is not a newer version of this app")
                install()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update failed: ${e.message}")
                runCatching { apk.delete() }
                _state.value = State.Failed(e.message ?: "Update failed")
            }
        }
    }

    private fun download(url: String) {
        dir.mkdirs()
        val tmp = File(dir, "update.apk.part")
        tmp.delete()
        client.newCall(Request.Builder().url(url.trim()).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Download failed (HTTP ${resp.code})")
            val body = resp.body ?: throw IllegalStateException("Empty download")
            val total = body.contentLength()
            var done = 0L
            var lastPct = -2
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; _state.value = State.Downloading(pct) }
                    }
                }
            }
            if (total > 0 && done != total) throw IllegalStateException("Download incomplete")
        }
        apk.delete()
        if (!tmp.renameTo(apk)) throw IllegalStateException("Could not save the update file")
    }

    /** ফাইলটা আমাদের অ্যাপেরই এবং বর্তমানের চেয়ে নতুন ভার্সন কি না। */
    @Suppress("DEPRECATION")
    private fun isNewerApk(f: File): Boolean {
        if (!f.isFile || f.length() < 1024) return false
        val info = ctx.packageManager.getPackageArchiveInfo(f.absolutePath, 0) ?: return false
        if (info.packageName != ctx.packageName) return false
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        return code > BuildConfig.VERSION_CODE
    }

    private fun install() {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedPermission
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(settings)
            } catch (e: Exception) {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        } else {
            apk.setReadable(true, false)
            Uri.fromFile(apk)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(intent)
        _state.value = State.Idle
    }
}
