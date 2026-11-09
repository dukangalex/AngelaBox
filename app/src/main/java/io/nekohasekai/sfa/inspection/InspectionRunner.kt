package io.nekohasekai.sfa.inspection

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Parcel
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object InspectionRunner {
    private val session = UUID.randomUUID().toString()
    private val epoch = AtomicLong()
    private val profileRevision = AtomicLong()
    private var initialized = false

    @Synchronized
    private fun initialize(context: Context) {
        if (initialized) return
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        fun networkCallback(singleNetwork: Boolean = false): ConnectivityManager.NetworkCallback {
            val tracker = NetworkEvidenceTracker<Network, Pair<Long, Long>, LinkProperties>(singleNetwork) { epoch.incrementAndGet() }
            return object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = tracker.available(network)
                override fun onLost(network: Network) = tracker.lost(network)
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    // Capability and transport bits can change route/validation; bandwidth
                    // and signal estimates alone do not change the evidence context.
                    var capabilityBits = 0L
                    var transportBits = 0L
                    for (bit in 0..63) {
                        if (capabilities.hasCapability(bit)) capabilityBits = capabilityBits or (1L shl bit)
                        if (capabilities.hasTransport(bit)) transportBits = transportBits or (1L shl bit)
                    }
                    tracker.capabilities(network, evidenceCapabilityBits(capabilityBits) to transportBits)
                }
                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) {
                    // Snapshot the mutable object using its public Parcelable API.
                    val parcel = Parcel.obtain()
                    try {
                        linkProperties.writeToParcel(parcel, 0)
                        parcel.setDataPosition(0)
                        tracker.link(network, LinkProperties.CREATOR.createFromParcel(parcel))
                    } finally {
                        parcel.recycle()
                    }
                }
                override fun onBlockedStatusChanged(
                    network: Network,
                    blocked: Boolean,
                ) {
                    tracker.blocked(network, blocked)
                }
            }
        }
        manager.registerDefaultNetworkCallback(networkCallback(singleNetwork = true))
        // Since Android 9 the default network may be our VPN. Its identity can
        // remain unchanged when Wi-Fi/mobile underneath it disappears or switches.
        // Invalidate reviewed evidence on those underlying transitions as well.
        manager.registerNetworkCallback(
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            networkCallback(),
        )
        ProfileManager.registerCallback {
            profileRevision.incrementAndGet()
            epoch.incrementAndGet()
        }
        initialized = true
    }
    data class ContextStamp(
        val profile: Long,
        val tunnelUp: Boolean,
        val network: Long?,
        val token: String,
        val collectionIdentity: InspectionCollectionIdentity,
    )
    fun stamp(context: Context): ContextStamp {
        initialize(context)
        return ContextStamp(
            Settings.selectedProfile,
            TunnelGate.up,
            context.getSystemService(ConnectivityManager::class.java)?.activeNetwork?.networkHandle,
            "$session:${epoch.get()}:${TunnelGate.generation}",
            InspectionCollectionIdentity(
                Settings.selectedProfile,
                profileRevision.get(),
                TunnelGate.generation,
                context.getSystemService(ConnectivityManager::class.java)?.activeNetwork?.networkHandle,
            ),
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
        withTimeout(90_000L) {
            val result =
                collectRecoveringEvidence(
                    current = { stamp(context) },
                    pause = { delay(it) },
                    elapsed = { android.os.SystemClock.elapsedRealtime() },
                    mayRecollect = { before, after ->
                        mayRecollectInspection(before.collectionIdentity, after.collectionIdentity)
                    },
                ) { before ->
                    val attempts = mutableListOf<InspectionPolicy.Fact>()
                    val result =
                        NetworkCheckup.run(context, before.network != null, before.tunnelUp, "", { null }, {}, {}) { round, target, code ->
                            InspectionPolicy.tunnelAttempt(round, target, code)?.let(attempts::add)
                        }
                    if (before != stamp(context)) throw StaleInspectionEvidence()
                    passive(before).copy(
                        facts =
                        passive(before).facts + attempts +
                            result.items.map {
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
                    )
                }
            if (result.contextToken != stamp(context).token) throw StaleInspectionEvidence()
            InspectionStore.save(context, result)
            result
        }
    }
    fun schedule(
        context: Context,
        enabled: Boolean,
    ) {
        val manager = WorkManager.getInstance(context)
        if (enabled) {
            manager
                .enqueueUniquePeriodicWork(
                    "AppLocalInspection",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequest
                        .Builder(InspectionWorker::class.java, InspectionPolicy.INTERVAL_MINUTES, TimeUnit.MINUTES)
                        .setInitialDelay(InspectionPolicy.INTERVAL_MINUTES, TimeUnit.MINUTES)
                        .build(),
                ).result
                .get()
        } else {
            manager.cancelUniqueWork("AppLocalInspection").result.get()
        }
    }
}

/** Passive OS/service observations only: no traffic, cloud call, notification spam, or repairs. */
class InspectionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
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
