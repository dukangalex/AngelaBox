package io.nekohasekai.sfa.bg

import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    suspend fun start() {
        DefaultNetworkListener.start(this) {
            defaultNetwork = it
            checkDefaultInterfaceUpdate(it)
        }
        defaultNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Application.connectivity.activeNetwork
        } else {
            DefaultNetworkListener.get()
        }
    }

    suspend fun stop() {
        cancelPending()
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
        checkDefaultInterfaceUpdate(defaultNetwork)
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { checkDefaultInterfaceUpdate(newNetwork) }
            return
        }
        cancelPending()
        if (newNetwork == null) {
            val lost = Runnable {
                pendingLost = null
                notifyIfChanged("", -1)
            }
            pendingLost = lost
            mainHandler.postDelayed(lost, LOST_DEBOUNCE_MS)
            return
        }
        resolveAndNotify(newNetwork, 0)
    }

    private fun resolveAndNotify(network: Network, attempt: Int) {
        val linkProperties = Application.connectivity.getLinkProperties(network)
        val name = linkProperties?.interfaceName
        if (!name.isNullOrBlank()) {
            try {
                val index = NetworkInterface.getByName(name).index
                notifyIfChanged(name, index)
                return
            } catch (_: Exception) {
            }
        }
        if (attempt >= MAX_RETRIES) return
        val retry = Runnable {
            pendingRetry = null
            if (defaultNetwork === network) {
                resolveAndNotify(network, attempt + 1)
            }
        }
        pendingRetry = retry
        mainHandler.postDelayed(retry, RETRY_MS)
    }

    private fun notifyIfChanged(name: String, index: Int) {
        val current = listener
        if (name == lastName && index == lastIndex) return
        lastName = name
        lastIndex = index
        current?.updateDefaultInterface(name, index, false, false)
    }

    private fun cancelPending() {
        pendingLost?.let { mainHandler.removeCallbacks(it) }
        pendingLost = null
        pendingRetry?.let { mainHandler.removeCallbacks(it) }
        pendingRetry = null
    }

    private const val LOST_DEBOUNCE_MS = 500L
    private const val RETRY_MS = 150L
    private const val MAX_RETRIES = 8
}
