package io.nekohasekai.sfa.inspection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.NetworkCheckup
import io.nekohasekai.sfa.utils.TunnelGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object InspectionRunner {
    private val session = UUID.randomUUID().toString()
    private val epoch = AtomicLong()
    private var initialized = false

    @Synchronized
    private fun initialize(context: Context) {
        if (initialized) return
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                epoch.incrementAndGet()
            }
            override fun onLost(network: Network) {
                epoch.incrementAndGet()
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                epoch.incrementAndGet()
            }
        })
        ProfileManager.registerCallback { epoch.incrementAndGet() }
        initialized = true
    }
    data class ContextStamp(val profile: Long, val tunnelUp: Boolean, val network: Long?, val token: String)
    fun stamp(context: Context): ContextStamp {
        initialize(context)
        return ContextStamp(
            Settings.selectedProfile,
            TunnelGate.up,
            context.getSystemService(ConnectivityManager::class.java)?.activeNetwork?.networkHandle,
            "$session:${epoch.get()}:${TunnelGate.generation}",
        )
    }
    fun passive(stamp: ContextStamp): InspectionPolicy.Snapshot = InspectionPolicy.Snapshot(
        System.currentTimeMillis(),
        stamp.profile,
        stamp.tunnelUp,
        listOf(
            InspectionPolicy.Fact("network", if (stamp.network != null) InspectionPolicy.State.OBSERVED else InspectionPolicy.State.ATTENTION, if (stamp.network != null) "present" else "absent"),
            InspectionPolicy.Fact("service", InspectionPolicy.State.OBSERVED, if (stamp.tunnelUp) "running" else "stopped"),
        ),
        networkId = stamp.network,
        contextToken = stamp.token,
    )
    suspend fun manual(context: Context): InspectionPolicy.Snapshot = withContext(Dispatchers.IO) {
        val before = stamp(context)
        val result = NetworkCheckup.run(context, before.network != null, before.tunnelUp, "", { null }, {}, {})
        require(before == stamp(context)) { "stale" }
        passive(before).copy(
            facts = passive(before).facts + result.items.map {
                InspectionPolicy.normalized(
                    InspectionPolicy.Fact(
                        it.id.name,
                        when (it.status) {
                            NetworkCheckup.ItemStatus.OK -> InspectionPolicy.State.OBSERVED
                            NetworkCheckup.ItemStatus.WARN, NetworkCheckup.ItemStatus.FAIL -> InspectionPolicy.State.ATTENTION
                            NetworkCheckup.ItemStatus.SKIP -> InspectionPolicy.State.NOT_CHECKED
                        },
                        it.code,
                    ),
                )
            },
        ).also { InspectionStore.save(context, it) }
    }
    fun schedule(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (enabled) {
            manager.enqueueUniquePeriodicWork(
                "AppLocalInspection",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(InspectionWorker::class.java, InspectionPolicy.INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setInitialDelay(InspectionPolicy.INTERVAL_MINUTES, TimeUnit.MINUTES).build(),
            ).result.get()
        } else {
            manager.cancelUniqueWork("AppLocalInspection").result.get()
        }
    }
}

/** Passive OS/service observations only: no traffic, cloud call, notification spam, or repairs. */
class InspectionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!InspectionStore.enabled(applicationContext)) return Result.success()
        return try {
            val stamp = InspectionRunner.stamp(applicationContext)
            val snapshot = InspectionRunner.passive(stamp)
            if (InspectionStore.enabled(applicationContext) && stamp == InspectionRunner.stamp(applicationContext)) {
                InspectionStore.save(applicationContext, snapshot)
            }
            Result.success()
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
