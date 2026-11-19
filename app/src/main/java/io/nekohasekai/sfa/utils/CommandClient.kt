package io.nekohasekai.sfa.utils

import android.util.Log
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.sfa.ktx.toList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

open class CommandClient(
    private val scope: CoroutineScope,
    private val connectionTypes: List<ConnectionType>,
    private val handler: Handler,
    private val localOnly: Boolean = false,
) {
    constructor(
        scope: CoroutineScope,
        connectionType: ConnectionType,
        handler: Handler,
        localOnly: Boolean = false,
    ) : this(scope, listOf(connectionType), handler, localOnly)

    private val additionalHandlers = mutableListOf<Handler>()
    private var cachedGroups: MutableList<OutboundGroup>? = null
    private var cachedOutbounds: List<io.nekohasekai.libbox.OutboundGroupItem>? = null

    fun addHandler(handler: Handler) {
        synchronized(access) {
            synchronized(additionalHandlers) {
                if (!additionalHandlers.contains(handler)) {
                    additionalHandlers.add(handler)
                    cachedGroups?.let { groups ->
                        handler.updateGroups(groups)
                    }
                    cachedOutbounds?.let { outbounds ->
                        handler.updateOutbounds(outbounds)
                    }
                }
            }
        }
    }

    fun removeHandler(handler: Handler) {
        synchronized(additionalHandlers) {
            additionalHandlers.remove(handler)
        }
    }

    private fun getAllHandlers(): List<Handler> = synchronized(additionalHandlers) {
        listOf(handler) + additionalHandlers
    }

    enum class ConnectionType {
        Status,
        Groups,
        Log,
        ClashMode,
        Connections,
        Outbounds,
    }

    enum class ConnectionErrorKind {
        // A connect attempt failed; retrying is not expected to succeed.
        ConnectFailed,

        // An established connection dropped (app suspension, network change,
        // server restart); reconnecting may recover.
        ConnectionLost,
    }

    interface Handler {
        fun onConnected() {}

        fun onDisconnected() {}

        fun onConnectionError(kind: ConnectionErrorKind, message: String) {}

        fun updateStatus(status: StatusMessage) {}

        fun setDefaultLogLevel(level: Int) {}

        fun clearLogs() {}

        fun appendLogs(message: List<LogEntry>) {}

        fun updateGroups(newGroups: MutableList<OutboundGroup>) {}

        fun updateOutbounds(outbounds: List<io.nekohasekai.libbox.OutboundGroupItem>) {}

        fun initializeClashMode(modeList: List<String>, currentMode: String) {}

        fun updateClashMode(newMode: String) {}

        fun writeConnectionEvents(events: ConnectionEvents) {}
    }

    private val epochGate = CommandEpochGate()
    private val access = epochGate.lock
    private var commandClient: io.nekohasekai.libbox.CommandClient? = null

    fun connect() {
        val epoch: Int
        val previousClient: io.nekohasekai.libbox.CommandClient?
        synchronized(access) {
            val hadConnectedCallback = epochGate.hasConnected()
            epoch = epochGate.advance()
            previousClient = commandClient
            commandClient = null
            cachedGroups = null
            cachedOutbounds = null
            if (previousClient != null || hadConnectedCallback) {
                dispatch(epoch) { it.onDisconnected() }
            }
        }
        // A remote connect dials over the network and blocks until the probe
        // completes, so it must run off the main thread.
        scope.launch(Dispatchers.IO) {
            previousClient?.apply {
                runCatching {
                    disconnect()
                }
            }
            val options = CommandClientOptions()
            connectionTypes.forEach { connectionType ->
                val command =
                    when (connectionType) {
                        ConnectionType.Status -> Libbox.CommandStatus
                        ConnectionType.Groups -> Libbox.CommandGroup
                        ConnectionType.Log -> Libbox.CommandLog
                        ConnectionType.ClashMode -> Libbox.CommandClashMode
                        ConnectionType.Connections -> Libbox.CommandConnections
                        ConnectionType.Outbounds -> Libbox.CommandOutbounds
                    }
                options.addCommand(command)
            }
            options.statusInterval = 1 * 1000 * 1000 * 1000
            val remoteServer = if (localOnly) null else CommandTarget.remoteServer
            val newClient: io.nekohasekai.libbox.CommandClient
            try {
                newClient =
                    if (remoteServer != null) {
                        Libbox.newRemoteCommandClient(
                            ClientHandler(epoch),
                            options,
                            CommandTarget.libboxOptions(remoteServer),
                        )
                    } else {
                        io.nekohasekai.libbox.CommandClient(ClientHandler(epoch), options)
                    }
                newClient.connect()
            } catch (e: Exception) {
                Log.d("CommandClient", "connect failed", e)
                epochGate.runIfCurrent(epoch) {
                    if (epochGate.hasConnected()) {
                        epochGate.setConnected(epoch, false)
                        dispatch(epoch) { it.onDisconnected() }
                    }
                    if (!epochGate.isCurrent(epoch)) return@runIfCurrent
                    handler.onConnectionError(
                        ConnectionErrorKind.ConnectFailed,
                        e.message ?: e.toString(),
                    )
                }
                return@launch
            }
            val stale =
                synchronized(access) {
                    if (!epochGate.isCurrent(epoch)) {
                        true
                    } else {
                        commandClient = newClient
                        false
                    }
                }
            if (stale) {
                runCatching {
                    newClient.disconnect()
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun disconnect() {
        val client: io.nekohasekai.libbox.CommandClient?
        synchronized(access) {
            val hadConnectedCallback = epochGate.hasConnected()
            val epoch = epochGate.advance()
            client = commandClient
            commandClient = null
            cachedGroups = null
            cachedOutbounds = null
            if (client != null || hadConnectedCallback) dispatch(epoch) { it.onDisconnected() }
        }
        if (client != null) {
            // The owning scope may already be cancelled when this is called from
            // ViewModel.onCleared, so the connection is released independently.
            GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    client.disconnect()
                }
            }
        }
    }

    // Hold the same gate through the check and handler dispatch. A transition can
    // no longer slip in between them; reentrant transitions stop the remaining fan-out.
    private fun dispatch(epoch: Int, block: (Handler) -> Unit) {
        epochGate.runIfCurrent(epoch) {
            for (target in getAllHandlers()) {
                if (!epochGate.isCurrent(epoch)) break
                block(target)
            }
        }
    }

    private inner class ClientHandler(private val epoch: Int) : CommandClientHandler {
        override fun connected() {
            epochGate.runIfCurrent(epoch) {
                epochGate.setConnected(epoch, true)
                dispatch(epoch) { it.onConnected() }
            }
            Log.d("CommandClient", "connected")
        }

        override fun disconnected(message: String?) {
            epochGate.runIfCurrent(epoch) {
                epochGate.setConnected(epoch, false)
                dispatch(epoch) { it.onDisconnected() }
                if (message != null && epochGate.isCurrent(epoch)) {
                    handler.onConnectionError(ConnectionErrorKind.ConnectionLost, message)
                }
                Log.d("CommandClient", "disconnected: $message")
            }
        }

        override fun writeGroups(message: OutboundGroupIterator?) {
            if (message == null) return
            val groups = mutableListOf<OutboundGroup>()
            while (message.hasNext()) groups.add(message.next())
            epochGate.runIfCurrent(epoch) {
                cachedGroups = groups
                dispatch(epoch) { it.updateGroups(groups) }
            }
        }

        override fun writeOutbounds(message: OutboundGroupItemIterator?) {
            if (message == null) return
            val outbounds = mutableListOf<io.nekohasekai.libbox.OutboundGroupItem>()
            while (message.hasNext()) outbounds.add(message.next())
            epochGate.runIfCurrent(epoch) {
                cachedOutbounds = outbounds
                dispatch(epoch) { it.updateOutbounds(outbounds) }
            }
        }

        override fun setDefaultLogLevel(level: Int) = dispatch(epoch) { it.setDefaultLogLevel(level) }

        override fun clearLogs() = dispatch(epoch) { it.clearLogs() }

        override fun writeLogs(messageList: LogIterator?) {
            if (messageList == null) return
            val logs = messageList.toList()
            dispatch(epoch) { it.appendLogs(logs) }
        }

        override fun writeStatus(message: StatusMessage) = dispatch(epoch) { it.updateStatus(message) }

        override fun initializeClashMode(modeList: StringIterator, currentMode: String) {
            val modes = modeList.toList()
            dispatch(epoch) { it.initializeClashMode(modes, currentMode) }
        }

        override fun updateClashMode(newMode: String) = dispatch(epoch) { it.updateClashMode(newMode) }

        override fun writeConnectionEvents(events: ConnectionEvents?) {
            if (events == null) return
            dispatch(epoch) { it.writeConnectionEvents(events) }
        }
    }
}
