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
import android.os.SystemClock
import android.util.Log
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    companion object {
        private const val START_BUDGET_MS = 45_000L
        private const val TAG = "BoxService"
        // A 45 s rule-set stall skips remote rule-sets on the next start only within
        // 10 min and on the same default network; a start that keeps them clears it.
        private val ruleSetStalls = RuleSetStallMemory(clock = SystemClock::elapsedRealtime)

        private fun defaultNetworkKey(): String? = DefaultNetworkMonitor.defaultNetwork?.toString()

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

    // Written from the kernel thread (openTun), read and closed on IO/Main.
    @Volatile
    var fileDescriptor: ParcelFileDescriptor? = null

    // kernel-start threads that were given up on. One can still be inside Go
    // and reach openTun later; it must not replace the live tunnel then.
    private val abandonedStartThreads: MutableSet<Thread> =
        Collections.synchronizedSet(Collections.newSetFromMap(java.util.WeakHashMap()))

    fun isAbandonedStartThread(thread: Thread = Thread.currentThread()): Boolean =
        thread in abandonedStartThreads

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private lateinit var commandServer: CommandServer
    private val startAbort = AtomicBoolean(false)
    @Volatile private var startInFlight = false

    private class StartCancelled : IllegalStateException("已取消启动")

    private var receiverRegistered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Action.SERVICE_CLOSE -> {
                        stopService()
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
            // stopService() runs on the main thread and only aborts while the
            // status is Starting. Check the abort and publish Started in the
            // same main-thread step, or a stop tapped in between is lost.
            val aborted = withContext(Dispatchers.Main) {
                if (startAbort.get()) {
                    true
                } else {
                    status.value = Status.Started
                    false
                }
            }
            if (aborted) {
                stopAndAlert(Alert.StartService, null, silent = true)
                return
            }
            notePrivateDns()
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_started)
            }
            notification.start()
        } catch (e: Exception) {
            if (e is StartCancelled) {
                stopAndAlert(Alert.StartService, null, silent = true)
            } else {
                stopAndAlert(Alert.StartService, ConfigDiagnose.explain(e.message))
            }
            return
        }
    }

    private fun notePrivateDns() {
        val mode = runCatching {
            android.provider.Settings.Global.getString(service.contentResolver, "private_dns_mode")
        }.getOrNull()
        if (mode != "hostname") return
        OverrideStatus.add(
            OverrideNotice(
                title = "私人 DNS 开着",
                reason = "系统正在用指定的 DNS over TLS，查询可能不经过本应用。",
                hint = "到系统设置的「私人 DNS」里改成「自动」或「关闭」。",
            ),
        )
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

        var content = ConfigQuicOverride.apply(rawContent)
        if (startAbort.get()) {
            stopAndAlert(Alert.StartService, null, silent = true)
            return false
        }
        var droppedRuleSets = false
        if (ruleSetStalls.shouldSkipRemote(profileId, defaultNetworkKey())) {
            val dropped = ConfigQuicOverride.apply(rawContent, dropAllRemoteRuleSets = true)
            if (dropped != content) {
                content = dropped
                droppedRuleSets = true
            }
        }

        // Every successful start goes through here. Starting with the remote
        // rule-sets kept proves they load again, so the stall is forgotten.
        fun started(keptRuleSets: Boolean): Boolean {
            if (keptRuleSets) ruleSetStalls.clear(profileId)
            return true
        }

        var result = attempt(content, options)
        if (result.isSuccess) {
            if (droppedRuleSets) noteRuleSetsSkipped()
            return started(!droppedRuleSets)
        }
        if (result.exceptionOrNull() is StartCancelled) {
            stopAndAlert(Alert.StartService, null, silent = true)
            return false
        }
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
            result = attempt(content, options)
            if (result.isSuccess) return started(!droppedRuleSets)
            keep(result.exceptionOrNull()?.message)
        }

        if (ConfigDiagnose.looksLikeBadEch(firstErr) || ConfigDiagnose.looksLikeBadEch(err)) {
            restartCommandServer()
            result = attempt(
                ConfigQuicOverride.apply(
                    rawContent,
                    stripEch = true,
                    dropAllRemoteRuleSets = droppedRuleSets,
                ),
                options,
            )
            if (result.isSuccess) {
                OverrideStatus.add(
                    OverrideNotice(
                        title = "ECH 已跳过",
                        reason = "节点自带的 ECH 参数内核读不了，已去掉后启动。",
                        hint = "节点还在，没有改成直连。",
                    ),
                )
                return started(!droppedRuleSets)
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
                result = attempt(content, options)
                if (result.isSuccess) return started(!droppedRuleSets)
                keep(result.exceptionOrNull()?.message)
            }
        }

        val ruleSetFail = ConfigDiagnose.looksLikeRuleSetFailure(firstErr) ||
            ConfigDiagnose.looksLikeRuleSetFailure(err)
        val stalled = isRuleSetStall(firstErr) || isRuleSetStall(err)
        var needles = ConfigDiagnose.ruleSetNeedles(firstErr)
        if (needles.isEmpty() && ruleSetFail && !stalled) {
            needles = ConfigInboundCompat.remoteRuleSetTags(content)
        }
        var ruleSetRetried = false
        if (ruleSetFail) {
            ruleSetRetried = true
            if (stalled) ruleSetStalls.recordStall(profileId, defaultNetworkKey())
            if (!stalled && needles.isNotEmpty()) {
                restartCommandServer()
                result = attempt(
                    ConfigQuicOverride.apply(rawContent, replaceRuleSetNeedles = needles),
                    options,
                )
                if (result.isSuccess) {
                    OverrideStatus.add(
                        OverrideNotice(
                            title = "配置规范化",
                            reason = "已修正",
                            hint = "打不开的规则集已换成官方地址后再启动。节点、分组和其余分流没动。",
                        ),
                    )
                    return started(true)
                }
                keep(result.exceptionOrNull()?.message)
            }
            restartCommandServer()
            result = attempt(
                ConfigQuicOverride.apply(rawContent, dropAllRemoteRuleSets = true),
                options,
            )
            if (result.isSuccess) {
                noteRuleSetsSkipped()
                return started(false)
            }
            keep(result.exceptionOrNull()?.message)
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
                    dropAllRemoteRuleSets = droppedRuleSets,
                )
                result = attempt(recovered, options)
                if (result.isSuccess) {
                    OverrideStatus.add(
                        OverrideNotice(
                            title = "脚本启动失败，已回滚",
                            reason = ConfigDiagnose.explain(firstErr, scriptsBound = true),
                            hint = ConfigDiagnose.rollbackHint(),
                            error = true,
                        ),
                    )
                    return started(!droppedRuleSets)
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
            val rolled = ConfigQuicOverride.apply(
                rawContent,
                skipScripts = true,
                dropAllRemoteRuleSets = droppedRuleSets,
            )
            result = attempt(rolled, options)
            if (result.isSuccess) {
                OverrideStatus.add(
                    OverrideNotice(
                        title = "脚本启动失败，已回滚",
                        reason = ConfigDiagnose.explain(firstErr, scriptsBound = true),
                        hint = ConfigDiagnose.rollbackHint(),
                        error = true,
                    ),
                )
                return started(!droppedRuleSets)
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

    private suspend fun attempt(content: String, options: OverrideOptions): Result<Unit> {
        val result = startWithinBudget(content, options)
        if (result.exceptionOrNull() is StartCancelled) throw StartCancelled()
        return result
    }

    /**
     * Remote rule-set downloads have no read timeout. Run the kernel start
     * off this thread so a blackholed fetch can be cancelled, and so a tap
     * on the power button during the spin can give up instead of sticking
     * in Starting forever (onStartCommand ignores a start that is not Stopped).
     */
    private fun startWithinBudget(content: String, options: OverrideOptions): Result<Unit> {
        if (startAbort.get()) return Result.failure(StartCancelled())
        val server = commandServer
        val holder = arrayOfNulls<Result<Unit>>(1)
        val worker = Thread({
            holder[0] = runCatching { server.startOrReloadService(content, options) }
        }, "kernel-start")
        worker.isDaemon = true
        worker.start()
        val deadline = SystemClock.elapsedRealtime() + START_BUDGET_MS
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0L) break
            worker.join(left.coerceAtMost(400L))
            if (!worker.isAlive) {
                return holder[0] ?: Result.failure(
                    IllegalStateException("initialize rule-set: 启动没有返回"),
                )
            }
            if (startAbort.get()) {
                abandonStart(server, worker)
                return Result.failure(StartCancelled())
            }
        }
        abandonStart(server, worker)
        return Result.failure(
            IllegalStateException("initialize rule-set: 远程规则集超过 45 秒还没下完"),
        )
    }

    private fun abandonStart(server: CommandServer, worker: Thread) {
        abandonedStartThreads.add(worker)
        val closer = Thread({
            runCatching { server.closeService() }
        }, "kernel-cancel")
        closer.isDaemon = true
        closer.start()
        worker.join(8_000)
        if (worker.isAlive) {
            runCatching { server.close() }
            worker.join(1_000)
        } else {
            closer.join(2_000)
        }
        if (::commandServer.isInitialized && commandServer === server) {
            runCatching { commandServer.close() }
            startCommandServer()
        }
    }

    private fun noteRuleSetsSkipped() {
        OverrideStatus.add(
            OverrideNotice(
                title = "规则集已跳过",
                reason = "远程规则集下不下来，已跳过这些规则集后启动。",
                hint = "节点和分组还在，其余分流还在。没有改成直连。",
            ),
        )
    }

    private fun isRuleSetStall(err: String?): Boolean {
        val text = err.orEmpty()
        if (text.contains("超过 45 秒")) return true
        if (!ConfigDiagnose.looksLikeRuleSetFailure(text)) return false
        return text.contains("timeout", ignoreCase = true) ||
            text.contains("connection refused", ignoreCase = true) ||
            text.contains("no such host", ignoreCase = true) ||
            text.contains("network is unreachable", ignoreCase = true) ||
            text.contains("connection reset", ignoreCase = true) ||
            text.contains("i/o timeout", ignoreCase = true)
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

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        if (status.value == Status.Starting) {
            startAbort.set(true)
            if (!startInFlight) {
                // Starting with no start in flight means the kernel asked to stop
                // (serviceStop). The command server, network monitor and receiver
                // are still live, so tear them down instead of only stopSelf().
                status.value = Status.Stopping
                if (receiverRegistered) {
                    service.unregisterReceiver(receiver)
                    receiverRegistered = false
                }
                notification.close()
                GlobalScope.launch(Dispatchers.IO) {
                    DefaultNetworkMonitor.stop()
                    if (::commandServer.isInitialized) {
                        closeService()
                        runCatching { commandServer.close() }
                    }
                    Settings.startedByUser = false
                    withContext(Dispatchers.Main) {
                        TunnelGate.setUp(false)
                        status.value = Status.Stopped
                        service.stopSelf()
                    }
                }
            }
            return
        }
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

    private suspend fun stopAndAlert(type: Alert, message: String? = null, silent: Boolean = false) {
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
                if (!silent) callback.onServiceAlert(type.ordinal, message)
            }
            TunnelGate.setUp(false)
            status.value = Status.Stopped
            service.stopSelf()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SameReturnValue")
    internal fun onStartCommand(): Int {
        // Every start arrives through startForegroundService(). If the service
        // stops before startForeground() (command server failure, or a start
        // request landing while Starting/Stopping), Android crashes the app with
        // "did not then call Service.startForeground()". Satisfy it right away.
        runCatching {
            notification.show(
                lastProfileName,
                if (status.value == Status.Started) R.string.status_started else R.string.status_starting,
            )
        }
        if (status.value != Status.Stopped) return Service.START_STICKY
        startAbort.set(false)
        startInFlight = true
        TunnelGate.setUp(false)
        status.value = Status.Starting

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter().apply {
                    addAction(Action.SERVICE_CLOSE)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                Settings.startedByUser = true
                try {
                    startCommandServer()
                } catch (e: Exception) {
                    stopAndAlert(Alert.StartCommandServer, e.message)
                    return@launch
                }
                startService()
            } finally {
                startInFlight = false
            }
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
