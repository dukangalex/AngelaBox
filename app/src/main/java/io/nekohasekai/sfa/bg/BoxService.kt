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
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.chain.ChainRuntimeCompiler
import io.nekohasekai.sfa.utils.ConfigChainReapply
import io.nekohasekai.sfa.utils.ConfigCheckOracle
import io.nekohasekai.sfa.utils.ConfigDiagnose
import io.nekohasekai.sfa.utils.ConfigGate
import io.nekohasekai.sfa.utils.ConfigInboundCompat
import io.nekohasekai.sfa.utils.ConfigNormalize
import io.nekohasekai.sfa.utils.ConfigQuarantine
import io.nekohasekai.sfa.utils.ConfigQuicOverride
import io.nekohasekai.sfa.utils.OverlayScripts
import io.nekohasekai.sfa.utils.OverrideNotice
import io.nekohasekai.sfa.utils.OverrideStatus
import io.nekohasekai.sfa.utils.QuarantineBudget
import io.nekohasekai.sfa.utils.RuleSetCacheWarmer
import io.nekohasekai.sfa.utils.StartRetryPolicy
import io.nekohasekai.sfa.utils.TunnelGate
import io.nekohasekai.sfa.ktx.hasPermission
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    companion object {
        private const val START_BUDGET_MS = 45_000L
        // The ladder as a whole gets a budget too: each rung having its
        // own 45 s attempt budget let pathological starts sit on the
        // Starting screen for minutes (7 attempts + repeated apply()).
        private const val LADDER_BUDGET_MS = 180_000L
        private const val TAG = "BoxService"
        // A rule-set stall is remembered for 10 min on the same default
        // network, but (AB-019) the memory is only consulted by reloads
        // inside the current service run — a fresh service start always
        // attempts the full config first. A start that keeps the sets
        // clears the memory.
        private val ruleSetStalls = RuleSetStallMemory(clock = SystemClock::elapsedRealtime)

        /**
         * Canonical stall-memory key (audit E-4): physical interface name +
         * transport via [RuleSetStallMemory.networkKey]. The old key used
         * Network.toString() (the netId), which changes on every reconnect
         * and, while the tunnel is up, describes the VPN itself.
         */
        private fun defaultNetworkKey(): String? = RuleSetStallMemory.networkKey(
            DefaultNetworkMonitor.currentInterfaceName,
            DefaultNetworkMonitor.currentTransport(),
        )

        /**
         * Content dimension for the stall memory (audit X-1): a stall is
         * remembered against the exact subscription content that stalled,
         * so a subscription update invalidates it without the updater
         * having to reach this in-process memory.
         */
        private fun contentHashOf(rawContent: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(rawContent.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

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

    // Serializes startOrReloadKernel: a background subscription update's
    // reload used to run a second pipeline concurrently with an ongoing
    // start, the two sharing commandServer / OverrideStatus / stall memory
    // (one side's retry could restartCommandServer under the other).
    private val kernelStartMutex = Mutex()

    // Kernel acceptance oracle (Libbox.checkConfig) for the quarantine
    // rung and the pipeline-fatal recovery: structural faults are
    // localised by asking the kernel, not by guessing from error text.
    private val checkOracle = ConfigCheckOracle { candidate -> ConfigGate.libboxCheck(candidate) }

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

    /**
     * Foreground-notification title. With privacyProtect on, the profile
     * name (often a subscription/airport name) is replaced by the app name —
     * the notification is visible on the lock screen to anyone looking.
     */
    private fun notificationLabel(): String =
        if (Settings.privacyProtect) "AngelaBox" else lastProfileName

    private suspend fun startService() {
        try {
            withContext(Dispatchers.Main) {
                notification.show(notificationLabel(), R.string.status_starting)
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
                notification.show(notificationLabel(), R.string.status_starting)
            }

            DefaultNetworkMonitor.start()

            if (!startOrReloadKernel(rawContent, selectedProfileId, freshStart = true)) return

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
                notification.show(notificationLabel(), R.string.status_started)
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
        // Same exception net as startService: this runs on a kernel
        // callback thread, where a StartCancelled / ChainApplyException /
        // pipeline failure escaping used to kill the reload silently or
        // crash, leaving the service state inconsistent.
        try {
            serviceReload0Guarded()
        } catch (e: Exception) {
            if (e is StartCancelled) {
                stopAndAlert(Alert.StartService, null, silent = true)
            } else {
                stopAndAlert(Alert.StartService, ConfigDiagnose.explain(e.message))
            }
            return
        }
    }

    private suspend fun serviceReload0Guarded() {
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
        if (!startOrReloadKernel(rawContent, selectedProfileId, freshStart = false)) return

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
            notification.show(notificationLabel(), R.string.status_started)
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

    private suspend fun startOrReloadKernel(
        rawContent: String,
        profileId: Long,
        freshStart: Boolean,
    ): Boolean =
        kernelStartMutex.withLock { startOrReloadKernelLocked(rawContent, profileId, freshStart) }

    private suspend fun startOrReloadKernelLocked(
        rawContent: String,
        profileId: Long,
        freshStart: Boolean,
    ): Boolean {
        val ladderStart = SystemClock.elapsedRealtime()
        fun ladderExpired(): Boolean =
            SystemClock.elapsedRealtime() - ladderStart > LADDER_BUDGET_MS
        val options = buildOverrideOptions()
        val scriptBound = OverlayScripts.isBound(profileId)
        val chainBound = ChainBindings.get(profileId) != null

        var droppedRuleSets = false
        var substitutedRuleSets = false
        // Pipeline-fatal recovery state (G1): set when the first apply()
        // threw and a degraded-but-startable config was recovered.
        var recoveryNotice: OverrideNotice? = null
        var recoveredContent = false

        // G1: a pipeline fatal (heal fatal / chain compile failure /
        // post-script sanitize rejection) used to bypass the ENTIRE
        // ladder — the first apply() was unguarded, so the exception
        // escaped and the start died with zero rungs tried, including
        // the script rollback rung that exists precisely for
        // script-produced fatals. The first apply is now guarded and a
        // fatal is rerouted into recoverFromPipelineFatal + the ladder.
        var content: String
        val firstApply = runCatching { ConfigQuicOverride.apply(rawContent, profileId = profileId) }
        if (firstApply.isSuccess) {
            content = firstApply.getOrThrow()
            // Memory demotion (AB-019): the stall memory is consulted
            // only on reloads inside this service run. A fresh start
            // always attempts the full config first — pre-skipping it
            // would punish a recovered network for a stall it did not
            // cause, with no chance to prove otherwise.
            if (StartRetryPolicy.mayConsultStallMemory(freshStart) &&
                ruleSetStalls.shouldSkipRemote(profileId, defaultNetworkKey(), contentHashOf(rawContent))
            ) {
                // This network + content stalled on remote rule sets before.
                // Substitute the cached local copies first (normalization
                // instead of skipping); only sets with no cache fall back to
                // the old anti-stall drop.
                val substituted = applyForRung(
                    rawContent,
                    substituteCachedRuleSets = true,
                    profileId = profileId,
                )
                if (substituted != null && substituted != content) {
                    content = substituted
                    substitutedRuleSets = true
                } else {
                    val dropped = applyForRung(
                        rawContent,
                        dropAllRemoteRuleSets = true,
                        profileId = profileId,
                    )
                    if (dropped != null && dropped != content) {
                        content = dropped
                        droppedRuleSets = true
                    }
                }
            }
        } else {
            val recovered = recoverFromPipelineFatal(
                rawContent,
                profileId,
                firstApply.exceptionOrNull(),
                scriptBound,
                chainBound,
                ladderExpired = { ladderExpired() },
            )
            if (recovered == null) {
                stopAndAlert(
                    Alert.CreateService,
                    ConfigDiagnose.explain(
                        firstApply.exceptionOrNull()?.message,
                        scriptsBound = scriptBound,
                    ),
                )
                return false
            }
            content = recovered.content
            recoveryNotice = recovered.notice
            recoveredContent = true
            // A recovered start must not clear the rule-set stall
            // memory: it never proved the remote sets load.
            droppedRuleSets = true
        }
        if (startAbort.get()) {
            stopAndAlert(Alert.StartService, null, silent = true)
            return false
        }

        // Every successful start goes through here. Starting with the remote
        // rule-sets kept proves they load again, so the stall is forgotten.
        fun started(keptRuleSets: Boolean): Boolean {
            recoveryNotice?.let { OverrideStatus.add(it) }
            if (keptRuleSets) ruleSetStalls.clear(profileId)
            return true
        }

        var result = attempt(content, options)
        if (result.isSuccess) {
            if (droppedRuleSets && !recoveredContent) noteRuleSetsSkipped(ConfigDiagnose.RuleSetSkipCause.MEMORY_PRE_SKIP)
            if (substitutedRuleSets) noteRuleSetsSubstituted()
            return started(!droppedRuleSets)
        }
        if (result.exceptionOrNull() is StartCancelled) {
            stopAndAlert(Alert.StartService, null, silent = true)
            return false
        }
        val firstErr = result.exceptionOrNull()?.message
        if (shouldRestartAsVpn(firstErr)) {
            // Do not persist Settings.serviceMode here: rebuildServiceMode()
            // (run on every BoxService.start) is the single source for the
            // mode and writes it itself. Persisting from this error-text
            // guess forked that source and could silently flip the user's
            // stored mode on a false positive.
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
            if (result.isSuccess) {
                if (droppedRuleSets && !recoveredContent) noteRuleSetsSkipped(ConfigDiagnose.RuleSetSkipCause.MEMORY_PRE_SKIP)
                if (substitutedRuleSets) noteRuleSetsSubstituted()
                return started(!droppedRuleSets)
            }
            keep(result.exceptionOrNull()?.message)
        }

        if (ConfigDiagnose.looksLikeBadEch(firstErr) || ConfigDiagnose.looksLikeBadEch(err)) {
            val echContent = applyForRung(
                rawContent,
                stripEch = true,
                dropAllRemoteRuleSets = droppedRuleSets,
                substituteCachedRuleSets = substitutedRuleSets,
                profileId = profileId,
            )
            if (echContent != null) {
                restartCommandServer()
                result = attempt(echContent, options)
            }
            if (echContent != null && result.isSuccess) {
                OverrideStatus.add(
                    OverrideNotice(
                        title = "ECH 已跳过",
                        reason = "节点自带的 ECH 参数内核读不了，已去掉后启动。",
                        hint = "节点还在，没有改成直连。",
                    ),
                )
                if (droppedRuleSets && !recoveredContent) noteRuleSetsSkipped(ConfigDiagnose.RuleSetSkipCause.MEMORY_PRE_SKIP)
                if (substitutedRuleSets) noteRuleSetsSubstituted()
                return started(!droppedRuleSets)
            }
            keep(result.exceptionOrNull()?.message)
        }

        // The resolver heal patches the already-processed content; later
        // rungs rebuild from rawContent and would silently lose it, so
        // remember and re-apply it to whatever they produce (reheal).
        var resolverHealed = false
        fun reheal(candidate: String): String =
            if (resolverHealed) ConfigInboundCompat.healRuntimeConfig(candidate) else candidate

        if (ConfigDiagnose.looksLikeDomainResolver(firstErr) ||
            ConfigDiagnose.looksLikeDomainResolver(err)
        ) {
            val patched = ConfigInboundCompat.healRuntimeConfig(content)
            if (patched != content) {
                resolverHealed = true
                restartCommandServer()
                content = patched
                result = attempt(content, options)
                if (result.isSuccess) {
                    if (droppedRuleSets && !recoveredContent) noteRuleSetsSkipped(ConfigDiagnose.RuleSetSkipCause.MEMORY_PRE_SKIP)
                    if (substitutedRuleSets) noteRuleSetsSubstituted()
                    return started(!droppedRuleSets)
                }
                keep(result.exceptionOrNull()?.message)
            }
        }

        val ruleSetFail = ConfigDiagnose.looksLikeRuleSetFailure(firstErr) ||
            ConfigDiagnose.looksLikeRuleSetFailure(err)
        // A start-budget timeout is NOT proof the rule-sets are at fault
        // (slow device / big config / slow DNS bootstrap all look alike),
        // but dropping the remote sets is still the most effective retry —
        // so timed-out starts enter this rung without the fabricated
        // "rule-set" attribution, and the stall memory is only written
        // when the evidence supports it (a real network stall error, or
        // the drop-retry actually rescuing a timed-out start).
        val timedOut = ConfigDiagnose.looksLikeStartTimeout(firstErr) ||
            ConfigDiagnose.looksLikeStartTimeout(err)
        val stalled = isRuleSetStall(firstErr) || isRuleSetStall(err)
        var needles = ConfigDiagnose.ruleSetNeedles(firstErr)
        if (needles.isEmpty() && ruleSetFail && !stalled) {
            needles = ConfigInboundCompat.remoteRuleSetTags(content)
        }
        var ruleSetRetried = false
        if ((ruleSetFail || timedOut) && !ladderExpired()) {
            ruleSetRetried = true
            if (stalled) ruleSetStalls.recordStall(profileId, defaultNetworkKey(), contentHashOf(rawContent))
            if (!stalled && needles.isNotEmpty()) {
                val replaced = applyForRung(
                    rawContent,
                    replaceRuleSetNeedles = needles,
                    profileId = profileId,
                )
                if (replaced != null) {
                    restartCommandServer()
                    result = attempt(reheal(replaced), options)
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
            }
            // Budget decoupling (AB-019): a timed-out first attempt is
            // not proof the rule sets are at fault, so before anything
            // degrades the config, a profile whose remote sets still
            // have no cache copy gets ONE retry of the full config at
            // the extended budget. Healthy-but-slow cold starts (the
            // rule-set files total a few hundred KB) finish inside it
            // and lose nothing; only a retry that also times out moves
            // on to the substitute / drop rungs below.
            if (StartRetryPolicy.shouldRetryWithExtendedBudget(
                    timedOut = timedOut,
                    alreadyExtended = false,
                    uncachedRemoteRuleSets = RuleSetCacheWarmer
                        .uncachedRemoteTags(content, ruleSetWorkingDir()).size,
                    ladderExpired = ladderExpired(),
                )
            ) {
                restartCommandServer()
                result = attempt(content, options, StartRetryPolicy.EXTENDED_START_BUDGET_MS)
                if (result.isSuccess) {
                    if (droppedRuleSets && !recoveredContent) {
                        noteRuleSetsSkipped(ConfigDiagnose.RuleSetSkipCause.MEMORY_PRE_SKIP)
                    }
                    if (substitutedRuleSets) noteRuleSetsSubstituted()
                    return started(!droppedRuleSets)
                }
                keep(result.exceptionOrNull()?.message)
            }
            if (!substitutedRuleSets && !ladderExpired()) {
                // Normalization substitute before any skipping: swap the
                // remote sets that have a cached copy for local ones and
                // retry. Only sets without a cache can still fail here,
                // and only they reach the drop below.
                val substitutedRetry = applyForRung(
                    rawContent,
                    replaceRuleSetNeedles = needles,
                    substituteCachedRuleSets = true,
                    profileId = profileId,
                )
                if (substitutedRetry != null) {
                    restartCommandServer()
                    result = attempt(reheal(substitutedRetry), options)
                    if (result.isSuccess) {
                        noteRuleSetsSubstituted()
                        return started(false)
                    }
                    keep(result.exceptionOrNull()?.message)
                }
            }
            val droppedAll = applyForRung(
                rawContent,
                dropAllRemoteRuleSets = true,
                profileId = profileId,
            )
            if (droppedAll != null) {
                restartCommandServer()
                result = attempt(reheal(droppedAll), options)
                if (result.isSuccess) {
                    if (timedOut && !stalled) {
                        // The drop rescued a timed-out start: now the stall is
                        // evidenced, so remembering it is fair.
                        ruleSetStalls.recordStall(profileId, defaultNetworkKey(), contentHashOf(rawContent))
                    }
                    // Failure must speak (AB-019): say which cause the
                    // evidence supports — a bare timeout is reported as a
                    // timeout, never dressed up as a download failure;
                    // a real kernel error is quoted verbatim (prefer the
                    // error that actually names a rule set).
                    noteRuleSetsSkipped(
                        if (timedOut) {
                            ConfigDiagnose.RuleSetSkipCause.START_TIMEOUT
                        } else {
                            ConfigDiagnose.RuleSetSkipCause.DOWNLOAD_FAILED
                        },
                        kernelDetail = listOf(firstErr, err)
                            .firstOrNull { ConfigDiagnose.looksLikeRuleSetFailure(it) }
                            ?: err ?: firstErr,
                    )
                    return started(false)
                }
                keep(result.exceptionOrNull()?.message)
            }
        }

        // Script rollback, attribution by experiment rather than by error
        // text: with scripts bound and every targeted rung above exhausted,
        // retry once without scripts. Success convicts the script (its
        // output — syntax-valid but reference-broken — is exactly what the
        // old looksLikeScriptFault substring gate could never recognize);
        // failure clears it and the alert reports both attempts. This also
        // unifies the recovery parameters: the configNormalize switch no
        // longer forks whether the rollback keeps the rule-set needles.
        var scriptRollbackError: String? = null
        if (scriptBound && !ladderExpired()) {
            val classicScriptFault = ConfigDiagnose.looksLikeScriptFault(firstErr) ||
                ConfigDiagnose.looksLikeScriptFault(err)
            val rolledRaw = applyForRung(
                rawContent,
                skipScripts = true,
                replaceRuleSetNeedles = needles,
                dropAllRemoteRuleSets = droppedRuleSets,
                substituteCachedRuleSets = substitutedRuleSets,
                profileId = profileId,
            )
            if (rolledRaw != null) {
                restartCommandServer()
                result = attempt(reheal(rolledRaw), options)
                if (result.isSuccess) {
                    OverrideStatus.add(
                        OverrideNotice(
                            title = "脚本启动失败，已回滚",
                            reason = if (classicScriptFault) {
                                ConfigDiagnose.explain(firstErr, scriptsBound = true)
                            } else {
                                "脚本改出的配置内核启动不了：${firstErr?.take(120) ?: "未知错误"}"
                            },
                            hint = ConfigDiagnose.rollbackHint(),
                            error = true,
                        ),
                    )
                    if (droppedRuleSets && !recoveredContent) noteRuleSetsSkipped(ConfigDiagnose.RuleSetSkipCause.MEMORY_PRE_SKIP)
                    if (substitutedRuleSets) noteRuleSetsSubstituted()
                    return started(!droppedRuleSets)
                }
                // No longer an immediate dead end (G1/G2): the message
                // is kept for the final failure, but the quarantine
                // rung below gets its turn first.
                scriptRollbackError =
                    ConfigDiagnose.explain(firstErr, scriptsBound = true) +
                        "\n\n关掉脚本后仍失败：" +
                        ConfigDiagnose.explain(result.exceptionOrNull()?.message, scriptsBound = false)
            }
        }

        // Fidelity degradation rung （保真降级档）: every text-attributed
        // rung above is exhausted. Ask the kernel itself which parts of
        // the config it rejects and quarantine ONLY those (see
        // ConfigQuarantine) — a structural fault outside every known
        // error pattern no longer kills an otherwise valid profile.
        // Skipped when this start already runs on recovered content.
        var quarantineAttempted = false
        if (!recoveredContent && !ladderExpired()) {
            quarantineAttempted = true
            val quarantined = runCatching {
                ConfigQuarantine.degrade(
                    content,
                    checkOracle,
                    QuarantineBudget(expired = { ladderExpired() }),
                )
            }.getOrNull()
            if (quarantined != null &&
                (quarantined.removedItems.isNotEmpty() || quarantined.floorUsed)
            ) {
                restartCommandServer()
                result = attempt(reheal(quarantined.content), options)
                if (result.isSuccess) {
                    OverrideStatus.add(quarantineNotice(quarantined))
                    if (chainBound &&
                        ChainRuntimeCompiler.compiledChain(content) != null &&
                        ChainRuntimeCompiler.compiledChain(quarantined.content) == null
                    ) {
                        // The compiled chain was collateral damage of
                        // the quarantine: say so loudly — a chained
                        // profile must never silently start unchained.
                        OverrideStatus.add(
                            OverrideNotice(
                                title = "链式代理这次没生效",
                                reason = "链路里有一段被隔离，这次没有走链式，流量由入口节点直接出去。",
                                hint = "订阅原文和链式绑定都没改，下次启动会先重试带链的完整配置。",
                                error = true,
                            ),
                        )
                    }
                    if (droppedRuleSets && !recoveredContent) noteRuleSetsSkipped(ConfigDiagnose.RuleSetSkipCause.MEMORY_PRE_SKIP)
                    if (substitutedRuleSets) noteRuleSetsSubstituted()
                    return started(false)
                }
                keep(result.exceptionOrNull()?.message)
            }
        }

        if (scriptRollbackError != null) {
            stopAndAlert(Alert.CreateService, scriptRollbackError)
            return false
        }
        var failureMessage = ConfigDiagnose.explain(
            err,
            scriptsBound = scriptBound,
            ruleSetRetried = ruleSetRetried,
        )
        if (quarantineAttempted) {
            // Even the check-driven quarantine could not make the
            // kernel accept this config: attach the kernel's own words
            // so the failure stays diagnosable instead of generic (G10).
            val checkError = checkOracle.check(content)
            if (checkError != null) {
                failureMessage += "\n\n逐部件检疫也没能让内核接受这份配置。内核校验原话：" +
                    checkError.take(200)
            }
        }
        if (ladderExpired()) {
            failureMessage += "\n\n启动重试的时间已用完，还有降级手段没来得及试；下次启动会从完整配置重新尝试。"
        }
        stopAndAlert(Alert.CreateService, failureMessage)
        return false
    }

    /**
     * Ladder-internal pipeline call (G1): one rung's apply() throwing
     * must not blow up the whole ladder the way the unguarded first
     * apply used to — the rung simply fails, the ladder moves on, and
     * the quarantine rung works from whatever content is at hand.
     */
    private suspend fun applyForRung(
        rawContent: String,
        skipScripts: Boolean = false,
        replaceRuleSetNeedles: Collection<String> = emptyList(),
        dropAllRemoteRuleSets: Boolean = false,
        substituteCachedRuleSets: Boolean = false,
        stripEch: Boolean = false,
        profileId: Long,
    ): String? {
        return try {
            ConfigQuicOverride.apply(
                rawContent,
                skipScripts = skipScripts,
                replaceRuleSetNeedles = replaceRuleSetNeedles,
                dropAllRemoteRuleSets = dropAllRemoteRuleSets,
                substituteCachedRuleSets = substituteCachedRuleSets,
                stripEch = stripEch,
                profileId = profileId,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private class PipelineRecovery(val content: String, val notice: OverrideNotice)

    /**
     * G1 reroute for a pipeline fatal. The fatal says the PROCESSED
     * config cannot be trusted, but the ladder was never the problem —
     * so instead of dying, recover a startable config in fidelity
     * order and let the normal ladder attempt it:
     * 1. scripts skipped (a script-produced fatal is exactly what the
     *    rollback rung exists for; it just could never be reached);
     * 2. the heal product's floor, chain-compiled first when a chain
     *    is bound (G7: keep the chain if at all possible; degrade
     *    without it only with a loud notice; a landing detour is never
     *    silently retargeted at a direct outbound);
     * 3. the heal product through the full quarantine (isolation of
     *    the provably bad parts, floor as its own last resort).
     * Returns null when nothing startable could be recovered.
     */
    private suspend fun recoverFromPipelineFatal(
        rawContent: String,
        profileId: Long,
        fatal: Throwable?,
        scriptBound: Boolean,
        chainBound: Boolean,
        ladderExpired: () -> Boolean,
    ): PipelineRecovery? {
        if (scriptBound) {
            val rolled = applyForRung(rawContent, skipScripts = true, profileId = profileId)
            if (rolled != null) {
                return PipelineRecovery(
                    rolled,
                    OverrideNotice(
                        title = "脚本启动失败，已回滚",
                        reason = "脚本改出的配置在加工阶段就被内核拒绝：${fatal?.message?.take(120) ?: "未知错误"}",
                        hint = ConfigDiagnose.rollbackHint(),
                        error = true,
                    ),
                )
            }
        }
        val healed = runCatching { ConfigNormalize.heal(rawContent) }.getOrNull() ?: return null
        val floor = ConfigQuarantine.buildFloor(healed.content)
        if (floor != null) {
            if (chainBound) {
                val chained = runCatching { ConfigChainReapply.apply(floor, profileId) }.getOrNull()
                if (chained != null && checkOracle.check(chained) == null) {
                    return PipelineRecovery(
                        chained,
                        OverrideNotice(
                            title = "已用最小配置启动，链式保持中",
                            reason = "完整配置加工失败，已用还能用的节点拼成最小配置，链式照常成立。",
                            hint = "订阅原文没有改，下次启动会先重试完整配置。",
                            error = true,
                        ),
                    )
                }
            }
            if (checkOracle.check(floor) == null) {
                return PipelineRecovery(
                    floor,
                    if (chainBound) {
                        OverrideNotice(
                            title = "链式代理这次没生效",
                            reason = "完整配置加工失败，最小配置也编译不出链式，这次没有走链式。",
                            hint = "订阅原文和链式绑定都没改，下次启动会先重试带链的完整配置。",
                            error = true,
                        )
                    } else {
                        OverrideNotice(
                            title = "已用最小配置启动",
                            reason = "完整配置加工失败，已用还能用的节点拼成最小配置启动。",
                            hint = "订阅原文没有改，下次启动会先重试完整配置。",
                            error = true,
                        )
                    },
                )
            }
        }
        val quarantined = runCatching {
            ConfigQuarantine.degrade(
                healed.content,
                checkOracle,
                QuarantineBudget(expired = ladderExpired),
            )
        }.getOrNull() ?: return null
        if (chainBound) {
            val chained = runCatching {
                ConfigChainReapply.apply(quarantined.content, profileId)
            }.getOrNull()
            if (chained != null && checkOracle.check(chained) == null) {
                return PipelineRecovery(
                    chained,
                    quarantineRecoveryNotice(quarantined, chainDropped = false),
                )
            }
            return PipelineRecovery(
                quarantined.content,
                quarantineRecoveryNotice(quarantined, chainDropped = true),
            )
        }
        return PipelineRecovery(
            quarantined.content,
            quarantineRecoveryNotice(quarantined, chainDropped = false),
        )
    }

    private fun quarantineRecoveryNotice(
        outcome: ConfigQuarantine.Outcome,
        chainDropped: Boolean,
    ): OverrideNotice {
        val removed = outcome.removedItems
        val base = when {
            outcome.floorUsed ->
                "完整配置加工失败，已用还能用的节点拼成最小配置启动"
            removed.isEmpty() ->
                "完整配置加工失败，已用规范化后的配置启动"
            else ->
                "完整配置加工失败，已隔离 ${removed.size} 处内核不认的内容后启动：" +
                    removed.take(6).joinToString("、")
        }
        return OverrideNotice(
            title = if (chainDropped) "链式代理这次没生效" else "配置规范化",
            reason = if (chainDropped) "$base。这次没有走链式。" else "$base。",
            hint = "订阅原文没有改，下次启动会先重试完整配置。",
            error = true,
        )
    }

    private fun quarantineNotice(outcome: ConfigQuarantine.Outcome): OverrideNotice {
        val removed = outcome.removedItems
        return if (outcome.floorUsed) {
            OverrideNotice(
                title = "已用最小配置启动",
                reason = "完整配置有多处内核不认的内容，已用还能用的节点拼成最小配置启动。涉及：${removed.take(6).joinToString("、")}",
                hint = "订阅原文没有改，下次启动会先重试完整配置。",
                error = true,
            )
        } else {
            OverrideNotice(
                title = "已隔离坏部件后启动",
                reason = "配置里有 ${removed.size} 处内核不认的内容，已只去掉这些，其余原样保留：${removed.take(6).joinToString("、")}",
                hint = "订阅原文没有改，下次启动会先重试完整配置。",
                error = true,
            )
        }
    }

    private suspend fun attempt(
        content: String,
        options: OverrideOptions,
        budgetMs: Long = START_BUDGET_MS,
    ): Result<Unit> {
        val result = startWithinBudget(content, options, budgetMs)
        if (result.exceptionOrNull() is StartCancelled) throw StartCancelled()
        return result
    }

    /**
     * Remote rule-set downloads have no read timeout. Run the kernel start
     * off this thread so a blackholed fetch can be cancelled, and so a tap
     * on the power button during the spin can give up instead of sticking
     * in Starting forever (onStartCommand ignores a start that is not Stopped).
     */
    private fun startWithinBudget(
        content: String,
        options: OverrideOptions,
        budgetMs: Long = START_BUDGET_MS,
    ): Result<Unit> {
        if (startAbort.get()) return Result.failure(StartCancelled())
        val server = commandServer
        val holder = arrayOfNulls<Result<Unit>>(1)
        val worker = Thread({
            holder[0] = runCatching { server.startOrReloadService(content, options) }
        }, "kernel-start")
        worker.isDaemon = true
        worker.start()
        val deadline = SystemClock.elapsedRealtime() + budgetMs
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0L) break
            worker.join(left.coerceAtMost(400L))
            if (!worker.isAlive) {
                return holder[0] ?: Result.failure(
                    IllegalStateException("内核启动没有返回结果"),
                )
            }
            if (startAbort.get()) {
                abandonStart(server, worker)
                return Result.failure(StartCancelled())
            }
        }
        abandonStart(server, worker)
        // Neutral wording on purpose: a budget timeout proves nothing
        // about WHY the kernel was slow. The old message fabricated
        // "initialize rule-set: 远程规则集超过 45 秒还没下完" for every
        // slow start, which the ladder then read as a confirmed rule-set
        // stall — recording the memory and dropping all remote rule-sets
        // for 10 minutes even when the sets were perfectly fine. The
        // seconds in the message are the budget actually enforced
        // (45 s standard, 90 s for the extended full-config retry).
        return Result.failure(
            IllegalStateException("内核启动超过 ${budgetMs / 1000} 秒没有返回"),
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

    /** Same working dir the startup substitute and the cache warmer use. */
    private fun ruleSetWorkingDir(): File =
        runCatching { Application.application.getExternalFilesDir(null) }.getOrNull()
            ?: Application.application.filesDir

    private fun noteRuleSetsSkipped(
        cause: ConfigDiagnose.RuleSetSkipCause,
        kernelDetail: String? = null,
    ) {
        OverrideStatus.add(
            OverrideNotice(
                title = "规则集已跳过",
                reason = ConfigDiagnose.ruleSetSkippedReason(cause, kernelDetail),
                hint = "节点和分组还在，其余分流还在。没有改成直连。",
            ),
        )
    }

    private fun noteRuleSetsSubstituted() {
        OverrideStatus.add(
            OverrideNotice(
                title = "配置规范化",
                reason = "远程规则集暂时下不下来，已用本地缓存的同名规则集替代后启动。",
                hint = "节点、分组和分流没动。网络恢复、规则集能下载后会自动换回远程版。",
            ),
        )
    }

    private fun isRuleSetStall(err: String?): Boolean {
        val text = err.orEmpty()
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
        val text = err.lowercase().trim()
        if (text.contains("configure tun interface") || text.contains("open tun")) return true
        if (!text.contains("invalid argument")) return false
        // PlatformInterfaceWrapper.openTun throws a bare "invalid argument"
        // with no tun context, and the gomobile round-trip may surface it
        // as the whole error (or the tail of the chain) — the old
        // "invalid argument AND tun" double-keyword match missed exactly
        // that form and left the user with a generic failure instead of
        // the switch-to-VPN guidance.
        return text.contains("tun") ||
            text == "invalid argument" ||
            text.endsWith(": invalid argument")
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
                // 关键状态同步落盘，防 GlobalScope 协程没跑完进程就被杀。
                Settings.startedByUser = false
                GlobalScope.launch(Dispatchers.IO) {
                    DefaultNetworkMonitor.stop()
                    if (::commandServer.isInitialized) {
                        closeService()
                        runCatching { commandServer.close() }
                    }
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
                notificationLabel(),
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
        Log.d("sing-box", message.orEmpty())
    }

    override fun connectSSHAgent(): Int = -1
}
