package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import go.Seq
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.ConfigDiagnose
import io.nekohasekai.sfa.utils.ConfigInboundCompat
import io.nekohasekai.sfa.utils.ConfigQuicOverride
import io.nekohasekai.sfa.utils.OverlayScripts
import io.nekohasekai.sfa.utils.OverrideNotice
import io.nekohasekai.sfa.utils.OverrideStatus
import io.nekohasekai.sfa.utils.TunnelGate
import io.nekohasekai.sfa.ktx.hasPermission
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    companion object {
        private const val PROFILE_UPDATE_INTERVAL = 15L * 60 * 1000
        private const val TAG = "BoxService"

        fun start() {
            val intent =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        runCatching { Settings.rebuildServiceMode() }
                        Intent(Application.application, Settings.serviceClass())
                    }
                }
            ContextCompat.startForegroundService(Application.application, intent)
        }

        fun stop() {
            Application.application.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(
                    Application.application.packageName,
                ),
            )
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private lateinit var commandServer: CommandServer

    private var receiverRegistered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Action.SERVICE_CLOSE -> {
                        stopService()
                    }

                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            serviceUpdateIdleMode()
                        }
                    }
                }
            }
        }

    private fun startCommandServer() {
        val commandServer = CommandServer(this, platformInterface)
        commandServer.start()
        this.commandServer = commandServer
    }

    private var lastProfileName = ""

    private suspend fun startService() {
        try {
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            val selectedProfileId = Settings.selectedProfile
            if (selectedProfileId == -1L) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val profile = ProfileManager.get(selectedProfileId)
            if (profile == null) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val rawContent = File(profile.typed.path).readText()
            if (rawContent.isBlank()) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            lastProfileName = profile.name
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            DefaultNetworkMonitor.start()

            if (!startOrReloadKernel(rawContent, selectedProfileId)) return

            if (commandServer.needWIFIState()) {
                val wifiPermission =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }
                if (!service.hasPermission(wifiPermission)) {
                    stopAndAlert(Alert.RequestLocationPermission)
                    return
                }
            }

            TunnelGate.setUp(true)
            status.postValue(Status.Started)
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_started)
            }
            notification.start()
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, ConfigDiagnose.explain(e.message))
            return
        }
    }

    override fun serviceStop() {
        notification.close()
        TunnelGate.setUp(false)
        status.postValue(Status.Starting)
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        closeService()
    }

    override fun serviceReload() {
        runBlocking {
            serviceReload0()
        }
    }

    suspend fun serviceReload0() {
        val selectedProfileId = Settings.selectedProfile
        if (selectedProfileId == -1L) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val profile = ProfileManager.get(selectedProfileId)
        if (profile == null) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val rawContent = File(profile.typed.path).readText()
        if (rawContent.isBlank()) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }
        lastProfileName = profile.name
        if (!startOrReloadKernel(rawContent, selectedProfileId)) return

        if (commandServer.needWIFIState()) {
            val wifiPermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
            if (!service.hasPermission(wifiPermission)) {
                stopAndAlert(Alert.RequestLocationPermission)
                return
            }
        }

        withContext(Dispatchers.Main) {
            notification.show(lastProfileName, R.string.status_started)
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? {
        val status = SystemProxyStatus()
        if (service is VPNService) {
            status.available = service.systemProxyAvailable
            status.enabled = service.systemProxyEnabled
        }
        return status
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        serviceReload()
    }

    private fun buildOverrideOptions(): OverrideOptions = OverrideOptions().apply {
        autoRedirect = Settings.autoRedirect
        if (Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled) {
            val appList = Settings.getEffectivePerAppProxyList()
            if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                includePackage =
                    PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
            } else {
                excludePackage =
                    PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
            }
        }
    }

    private suspend fun startOrReloadKernel(rawContent: String, profileId: Long): Boolean {
        val options = buildOverrideOptions()
        val scriptBound = OverlayScripts.isBound(profileId)
        fun tryStart(content: String): Result<Unit> = runCatching {
            commandServer.startOrReloadService(content, options)
        }

        var content = ConfigQuicOverride.apply(rawContent)
        var result = tryStart(content)
        if (result.isSuccess) return true
        val firstErr = result.exceptionOrNull()?.message
        if (shouldRestartAsVpn(firstErr)) {
            Settings.serviceMode = ServiceMode.VPN
            stopAndAlert(Alert.RestartAsVpn, null)
            return false
        }
        var err = firstErr

        fun keep(retry: String?) {
            err = ConfigDiagnose.preferKernelError(firstErr, retry)
        }

        if (ConfigDiagnose.looksLikeRpcDeath(err)) {
            restartCommandServer()
            result = tryStart(content)
            if (result.isSuccess) return true
            keep(result.exceptionOrNull()?.message)
        }

        if (ConfigDiagnose.looksLikeBadEch(firstErr) || ConfigDiagnose.looksLikeBadEch(err)) {
            restartCommandServer()
            result = tryStart(ConfigQuicOverride.apply(rawContent, stripEch = true))
            if (result.isSuccess) {
                OverrideStatus.add(
                    OverrideNotice(
                        title = "ECH 已跳过",
                        reason = "节点自带的 ECH 参数内核读不了，已去掉后启动。",
                        hint = "节点还在，没有改成直连。",
                    ),
                )
                return true
            }
            keep(result.exceptionOrNull()?.message)
        }

        if (ConfigDiagnose.looksLikeDomainResolver(firstErr) ||
            ConfigDiagnose.looksLikeDomainResolver(err)
        ) {
            val patched = ConfigInboundCompat.healRuntimeConfig(content)
            if (patched != content) {
                restartCommandServer()
                content = patched
                result = tryStart(content)
                if (result.isSuccess) return true
                keep(result.exceptionOrNull()?.message)
            }
        }

        val ruleSetFail = ConfigDiagnose.looksLikeRuleSetFailure(firstErr) ||
            ConfigDiagnose.looksLikeRuleSetFailure(err)
        var needles = ConfigDiagnose.ruleSetNeedles(firstErr)
        if (needles.isEmpty() && ruleSetFail) {
            needles = ConfigInboundCompat.remoteRuleSetTags(content)
        }
        var ruleSetRetried = false
        if (ruleSetFail && needles.isNotEmpty()) {
            ruleSetRetried = true
            restartCommandServer()
            result = tryStart(
                ConfigQuicOverride.apply(rawContent, replaceRuleSetNeedles = needles),
            )
            if (result.isSuccess) {
                OverrideStatus.add(
                    OverrideNotice(
                        title = "配置规范化",
                        reason = "已修正",
                        hint = "打不开的规则集已换成官方地址后再启动。节点、分组和其余分流没动。",
                    ),
                )
                return true
            }
            keep(result.exceptionOrNull()?.message)
            val drop = ConfigDiagnose.ruleSetNeedles(err).ifEmpty { needles }
            if (ConfigDiagnose.looksLikeRuleSetFailure(err) && drop.isNotEmpty()) {
                restartCommandServer()
                result = tryStart(
                    ConfigQuicOverride.apply(rawContent, dropRuleSetNeedles = drop),
                )
                if (result.isSuccess) {
                    OverrideStatus.add(
                        OverrideNotice(
                            title = "规则集已跳过",
                            reason = "打不开的规则集已去掉后再启动。",
                            hint = "节点和分组没动，其余分流仍在。没有改成直连。",
                        ),
                    )
                    return true
                }
                keep(result.exceptionOrNull()?.message)
            }
        }

        if (Settings.configNormalize) {
            if (ConfigDiagnose.looksLikeScriptFault(firstErr) ||
                ConfigDiagnose.looksLikeScriptFault(err)
            ) {
                restartCommandServer()
                val recovered = ConfigQuicOverride.apply(
                    rawContent,
                    skipScripts = true,
                    replaceRuleSetNeedles = needles,
                )
                result = tryStart(recovered)
                if (result.isSuccess) {
                    OverrideStatus.add(
                        OverrideNotice(
                            title = "脚本启动失败，已回滚",
                            reason = ConfigDiagnose.explain(firstErr, scriptsBound = true),
                            hint = ConfigDiagnose.rollbackHint(),
                            error = true,
                        ),
                    )
                    return true
                }
                keep(result.exceptionOrNull()?.message)
            }
            stopAndAlert(
                Alert.CreateService,
                ConfigDiagnose.explain(
                    err,
                    scriptsBound = scriptBound,
                    ruleSetRetried = ruleSetRetried,
                ),
            )
            return false
        }

        if (scriptBound && ConfigDiagnose.looksLikeScriptFault(err)) {
            restartCommandServer()
            val rolled = ConfigQuicOverride.apply(rawContent, skipScripts = true)
            result = tryStart(rolled)
            if (result.isSuccess) {
                OverrideStatus.add(
                    OverrideNotice(
                        title = "脚本启动失败，已回滚",
                        reason = ConfigDiagnose.explain(firstErr, scriptsBound = true),
                        hint = ConfigDiagnose.rollbackHint(),
                        error = true,
                    ),
                )
                return true
            }
            stopAndAlert(
                Alert.CreateService,
                ConfigDiagnose.explain(firstErr, scriptsBound = true) +
                    "\n\n关掉脚本后仍失败：" +
                    ConfigDiagnose.explain(result.exceptionOrNull()?.message, scriptsBound = false),
            )
            return false
        }

        stopAndAlert(
            Alert.CreateService,
            ConfigDiagnose.explain(err, scriptsBound = scriptBound, ruleSetRetried = ruleSetRetried),
        )
        return false
    }

    private fun shouldRestartAsVpn(err: String?): Boolean {
        if (service is VPNService) return false
        if (err.isNullOrBlank()) return false
        val text = err.lowercase()
        return text.contains("configure tun interface") ||
            (text.contains("invalid argument") && text.contains("tun"))
    }

    private fun restartCommandServer() {
        runCatching { commandServer.closeService() }
        runCatching { commandServer.close() }
        startCommandServer()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun serviceUpdateIdleMode() {
        if (Application.powerManager.isDeviceIdleMode) {
            val keepAlive = Application.powerManager.isIgnoringBatteryOptimizations(
                Application.application.packageName,
            )
            if (keepAlive) {
                // User allowed background: keep TUN and the selected node.
                // Urltest is 10m; TCP keepalive holds NAT. Do not DevicePause.
                return
            }
            commandServer.pause()
        } else {
            commandServer.wake()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        if (status.value != Status.Started) return
        TunnelGate.setUp(false)
        status.value = Status.Stopping
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        GlobalScope.launch(Dispatchers.IO) {
            val pfd = fileDescriptor
            if (pfd != null) {
                pfd.close()
                fileDescriptor = null
            }
            DefaultNetworkMonitor.stop()
            closeService()
            commandServer.apply {
                close()
            }
            runCatching { PowerReportManager.refresh() }
            Settings.startedByUser = false
            withContext(Dispatchers.Main) {
                TunnelGate.setUp(false)
                status.value = Status.Stopped
                service.stopSelf()
            }
        }
    }

    private fun closeService() {
        runCatching {
            commandServer.closeService()
        }.onFailure {
            commandServer.setError("android: close service: ${it.message}")
        }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        Settings.startedByUser = false
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        DefaultNetworkMonitor.stop()
        if (::commandServer.isInitialized) {
            closeService()
            commandServer.close()
        }
        withContext(Dispatchers.Main) {
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
            binder.broadcast { callback ->
                callback.onServiceAlert(type.ordinal, message)
            }
            TunnelGate.setUp(false)
            status.value = Status.Stopped
            service.stopSelf()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SameReturnValue")
    internal fun onStartCommand(): Int {
        if (status.value != Status.Stopped) return Service.START_STICKY
        TunnelGate.setUp(false)
        status.value = Status.Starting

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter().apply {
                    addAction(Action.SERVICE_CLOSE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        GlobalScope.launch(Dispatchers.IO) {
            Settings.startedByUser = true
            try {
                startCommandServer()
            } catch (e: Exception) {
                stopAndAlert(Alert.StartCommandServer, e.message)
                return@launch
            }
            startService()
        }
        return Service.START_STICKY
    }

    internal fun onBind(): IBinder = binder

    internal fun onDestroy() {
        binder.close()
    }

    internal fun onRevoke() {
        stopService()
    }

    internal fun sendNotification(notification: Notification) {
        val channel = "notification-${notification.typeID}"
        val builder =
            NotificationCompat.Builder(service, channel).setShowWhen(false)
                .setContentTitle(notification.title).setContentText(notification.body)
                .setOnlyAlertOnce(true).setSmallIcon(R.drawable.ic_qs_tile)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        if (!notification.openURL.isNullOrBlank()) {
            val parsed = Uri.parse(notification.openURL)
            if (parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()) {
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        service,
                        0,
                        Intent(
                            service,
                            MainActivity::class.java,
                        ).apply {
                            setAction(Action.OPEN_URL).setData(parsed)
                            setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        },
                        ServiceNotification.flags,
                    ),
                )
            }
        }
        GlobalScope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Application.notification.createNotificationChannel(
                    NotificationChannel(
                        channel,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            Application.notification.notify(notification.identifier, notification.typeID, builder.build())
        }
    }

    internal fun cancelNotification(identifier: String, typeID: Int) {
        GlobalScope.launch(Dispatchers.Main) {
            Application.notification.cancel(identifier, typeID)
        }
    }

    override fun triggerNativeCrash() {
        Thread {
            Thread.sleep(200)
            throw RuntimeException("debug native crash")
        }.start()
    }

    override fun writeDebugMessage(message: String?) {
        Log.d("sing-box", message!!)
    }

    override fun connectSSHAgent(): Int = -1
}
