package com.streambd.iptv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.streambd.iptv.MainActivity
import com.streambd.iptv.data.settings.SettingsStore
import com.streambd.iptv.util.isTelevision
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * রিবুটের পর অ্যাপ চালু করে। শুধু TV-তে: ফোন বারবার রিবুট হয় না, আর Android 10+ ফোনে
 * ব্যাকগ্রাউন্ড থেকে activity চালানো নিষিদ্ধ। TV-তে "Display over other apps" অনুমতি
 * (Settings-এ চেকবক্স চাপলে চাওয়া হয়) থাকলে এই নিষেধ খাটে না।
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsStore

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        val appContext = context.applicationContext
        if (!appContext.isTelevision()) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (settings.flow.first().startAfterReboot) {
                    val launchIntent = Intent(appContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(launchIntent)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
