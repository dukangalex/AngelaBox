package io.nekohasekai.sfa.vendor

import android.content.Context
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.database.Settings
import java.io.File

enum class InstallMethod {
    PACKAGE_INSTALLER,
    ROOT,
}

object ApkInstaller {

    fun getConfiguredMethod(): InstallMethod {
        // The user's configured method wins. An LSPosed/Xposed hook being
        // active used to force ROOT here, silently overriding a configured
        // PACKAGE_INSTALLER (user-action) choice for background update
        // installs (audit P3-5). ROOT is used only when the user
        // configured it. There was no availability fallback in this
        // branch to preserve: availability is probed separately in
        // canSilentInstall(), per configured method. This flavor has no
        // SHIZUKU method, so a stored "SHIZUKU" setting keeps mapping to
        // ROOT as before.
        return if (Settings.silentInstallEnabled) {
            val method = Settings.silentInstallMethod
            if (method == "SHIZUKU") InstallMethod.ROOT else InstallMethod.valueOf(method)
        } else {
            InstallMethod.PACKAGE_INSTALLER
        }
    }

    suspend fun install(context: Context, apkFile: File, method: InstallMethod = getConfiguredMethod()) {
        when (method) {
            InstallMethod.ROOT -> RootInstaller.install(apkFile)
            InstallMethod.PACKAGE_INSTALLER -> SystemPackageInstaller.install(context, apkFile)
        }
    }

    fun canSystemSilentInstall(): Boolean = SystemPackageInstaller.canSystemSilentInstall()

    suspend fun canSilentInstall(): Boolean {
        val method = getConfiguredMethod()
        return when (method) {
            InstallMethod.PACKAGE_INSTALLER -> canSystemSilentInstall()
            InstallMethod.ROOT -> RootClient.checkRootAvailable()
        }
    }
}
