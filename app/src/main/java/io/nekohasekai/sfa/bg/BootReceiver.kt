package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
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
            // Don't yank the user to the foreground after an update; a
            // notification they can tap is far less disruptive.
            notifyUpdated(context)
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
        private const val UPDATED_CHANNEL_ID = "app_updated"
        private const val UPDATED_NOTIFICATION_ID = 0xA9B0

        fun launchApp(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            )
            runCatching { context.startActivity(launch) }
        }

        fun notifyUpdated(context: Context) {
            val versionName = runCatching {
                val pm = context.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(context.packageName, 0)
                }.versionName
            }.getOrNull() ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Application.notification.createNotificationChannel(
                    NotificationChannel(
                        UPDATED_CHANNEL_ID,
                        context.getString(R.string.app_updated_title),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val contentIntent = PendingIntent.getActivity(context, 0, launch, flags)
            val notification = NotificationCompat.Builder(context, UPDATED_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_qs_tile)
                .setContentTitle(context.getString(R.string.app_updated_title))
                .setContentText(context.getString(R.string.app_updated_text, versionName))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            Application.notification.notify(UPDATED_NOTIFICATION_ID, notification)
        }
    }
}
