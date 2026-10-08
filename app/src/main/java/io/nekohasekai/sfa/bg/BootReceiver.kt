package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootReceiver : BroadcastReceiver() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            launchApp(context)
        }
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        // Keep the process alive until the start is handed off; without
        // goAsync the system may kill it right after onReceive returns.
        val pendingResult = goAsync()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                if (Settings.startedByUser) {
                    CrashReportManager.refresh()
                    if (CrashReportManager.unreadCount.value > 0) {
                        Settings.startedByUser = false
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        // A refused foreground start must not crash the app at boot.
                        runCatching { BoxService.start() }.onFailure {
                            Log.w("BootReceiver", "auto start failed", it)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        fun launchApp(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            )
            runCatching { context.startActivity(launch) }
        }
    }
}
