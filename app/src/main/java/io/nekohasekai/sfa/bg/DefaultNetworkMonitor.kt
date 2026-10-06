package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.sfa.Application
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastName: String? = null
    private var lastIndex: Int? = null
    private var pendingLost: Runnable? = null
    private var pendingRetry: Runnable? = null
    private var pendingRebind: Runnable? = null
    private var pendingWatch: Runnable? = null
    private var rebindToken = 0
    private var lastInterfaceEventAt = 0L
    private var wakeReceiver: BroadcastReceiver? = null
    @Volatile
    private var running = false

    suspend fun start() {
        DefaultNetworkListener.start(this) { network ->
            val replaced = network != null && defaultNetwork != null && network != defaultNetwork
            defaultNetwork = network
            checkDefaultInterfaceUpdate(network, replaced)
        }
        defaultNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Application.connectivity.activeNetwork
        } else {
            DefaultNetworkListener.get()
        }
        running = true
        registerWake()
        scheduleWatch()
    }

    suspend fun stop() {
        running = false
        unregisterWake()
        cancelPending()
        pendingWatch?.let { mainHandler.removeCallbacks(it) }
        pendingWatch = null
        DefaultNetworkListener.stop(this)
    }

    suspend fun require(): Network {
        val network = defaultNetwork
        if (network != null) {
            return network
        }
        return DefaultNetworkListener.get()
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        lastName = null
        lastIndex = null
        checkDefaultInterfaceUpdate(defaultNetwork, false)
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?, replaced: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { checkDefaultInterfaceUpdate(newNetwork, replaced) }
            return
        }
        val lostPending = pendingLost != null
        cancelPending()
        if (newNetwork == null) {
            val lost = Runnable {
                pendingLost = null
                notifyIfChanged("", -1, false)
            }
            pendingLost = lost
            mainHandler.postDelayed(lost, LOST_DEBOUNCE_MS)
            return
        }
        resolveAndNotify(newNetwork, 0, replaced || lostPending)
    }

    private fun resolveAndNotify(network: Network, attempt: Int, forceReset: Boolean) {
        val linkProperties = Application.connectivity.getLinkProperties(network)
        val name = linkProperties?.interfaceName
        if (!name.isNullOrBlank()) {
            try {
                val index = NetworkInterface.getByName(name).index
                notifyIfChanged(name, index, forceReset)
                return
            } catch (_: Exception) {
            }
        }
        if (attempt >= MAX_RETRIES) return
        val retry = Runnable {
            pendingRetry = null
            if (defaultNetwork === network) {
                resolveAndNotify(network, attempt + 1, forceReset)
            }
        }
        pendingRetry = retry
        mainHandler.postDelayed(retry, RETRY_MS)
    }

    private fun notifyIfChanged(name: String, index: Int, forceReset: Boolean) {
        lastInterfaceEventAt = SystemClock.elapsedRealtime()
        val current = listener
        val same = name == lastName && index == lastIndex
        if (same && !forceReset) return
        if (name.isNotEmpty() && same && forceReset) {
            // The kernel ignores an update when the name and index did not change,
            // so a Wi-Fi drop that comes back as the same wlan0 never resets dials.
            current?.updateDefaultInterface("", -1, false, false)
            val token = ++rebindToken
            val rebound = Runnable {
                if (token != rebindToken) return@Runnable
                pendingRebind = null
                listener?.updateDefaultInterface(name, index, false, false)
            }
            pendingRebind = rebound
            mainHandler.postDelayed(rebound, REBIND_DELAY_MS)
            return
        }
        lastName = name
        lastIndex = index
        current?.updateDefaultInterface(name, index, false, false)
    }

    // Doze drops the default-network callback on some phones while the
    // interface stays up, so the periodic watch does not rebind. After a long
    // quiet period, unlocking the screen rebinds once. A normal unlock does
    // not, or every wake would drop live connections.
    private fun registerWake() {
        if (wakeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!running || listener == null) return
                val seen = lastInterfaceEventAt
                if (seen == 0L) return
                if (SystemClock.elapsedRealtime() - seen < WAKE_REBIND_AFTER_MS) return
                val name = lastName
                val index = lastIndex
                if (name.isNullOrEmpty() || index == null) return
                if (!interfaceUp(name)) {
                    reconcileUnderlying()
                    return
                }
                notifyIfChanged(name, index, true)
            }
        }
        wakeReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Application.application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            Application.application.registerReceiver(receiver, filter)
        }
    }

    private fun unregisterWake() {
        val receiver = wakeReceiver ?: return
        wakeReceiver = null
        runCatching { Application.application.unregisterReceiver(receiver) }
    }

    private fun scheduleWatch() {
        if (!running) return
        val watch = Runnable {
            pendingWatch = null
            if (!running) return@Runnable
            reconcileUnderlying()
            scheduleWatch()
        }
        pendingWatch = watch
        mainHandler.postDelayed(watch, WATCH_MS)
    }

    // Callbacks are dropped after doze on some phones. If the interface we told
    // the kernel about is down, bind whatever physical network is up now.
    private fun reconcileUnderlying() {
        if (listener == null || pendingRebind != null) return
        if (!lastName.isNullOrEmpty() && interfaceUp(lastName)) return
        val network = physicalNetwork()
        if (network == null) {
            if (!lastName.isNullOrEmpty()) notifyIfChanged("", -1, false)
            return
        }
        val name = Application.connectivity.getLinkProperties(network)?.interfaceName
        if (name.isNullOrBlank()) return
        val index = try {
            NetworkInterface.getByName(name)?.index
        } catch (_: Exception) {
            null
        } ?: return
        if (name == lastName && index == lastIndex) return
        defaultNetwork = network
        notifyIfChanged(name, index, false)
    }

    private fun interfaceUp(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return try {
            NetworkInterface.getByName(name)?.isUp == true
        } catch (_: Exception) {
            false
        }
    }

    private fun physicalNetwork(): Network? {
        val connectivity = Application.connectivity
        for (network in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            return network
        }
        return null
    }

    private fun cancelPending() {
        pendingLost?.let { mainHandler.removeCallbacks(it) }
        pendingLost = null
        pendingRetry?.let { mainHandler.removeCallbacks(it) }
        pendingRetry = null
        pendingRebind?.let { mainHandler.removeCallbacks(it) }
        pendingRebind = null
        rebindToken++
    }

    private const val LOST_DEBOUNCE_MS = 500L
    private const val RETRY_MS = 150L
    private const val REBIND_DELAY_MS = 200L
    private const val WATCH_MS = 30_000L
    private const val WAKE_REBIND_AFTER_MS = 30 * 60 * 1000L
    private const val MAX_RETRIES = 8
}