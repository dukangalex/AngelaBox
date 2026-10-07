package io.nekohasekai.sfa.vendor

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.core.content.FileProvider
import io.nekohasekai.sfa.database.Settings
import java.io.File
import java.io.FileInputStream
import android.content.pm.PackageInstaller as AndroidPackageInstaller

object SystemPackageInstaller {

    fun canSystemSilentInstall(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun install(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            throw IllegalStateException("更新包不存在或为空，请重新检查更新")
        }
        val run = Runnable { launchSystemInstaller(context, apkFile) }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run()
        } else {
            Handler(Looper.getMainLooper()).post(run)
        }
    }

    private fun launchSystemInstaller(context: Context, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settings = Intent(
                AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            Toast.makeText(
                context,
                "请先允许 AngelaBox 安装未知应用，返回后再点一次更新",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (Settings.silentInstallEnabled) {
            try {
                commitSession(context, apkFile)
                return
            } catch (_: Exception) {
                // Visible installer is the guaranteed confirmation UI.
            }
        }
        launchVisibleInstaller(context, apkFile)
    }

    private fun launchVisibleInstaller(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.cache",
            apkFile,
        )
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
        }
        context.startActivity(view)
    }

    private fun commitSession(context: Context, apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = AndroidPackageInstaller.SessionParams(AndroidPackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(AndroidPackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { params.setDontKillApp(true) }
        }

        val sessionId = packageInstaller.createSession(params)
        packageInstaller.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apkFile.length()).use { outputStream ->
                FileInputStream(apkFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                session.fsync(outputStream)
            }

            val intent = Intent(context, InstallResultReceiver::class.java).apply {
                action = InstallResultReceiver.ACTION_INSTALL_COMPLETE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )

            session.commit(pendingIntent.intentSender)
        }
    }
}
