package io.nekohasekai.sfa.database

import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.chain.ChainBinding
import io.nekohasekai.sfa.chain.ChainBindingCodec
import io.nekohasekai.sfa.chain.ChainBindings
import io.nekohasekai.sfa.utils.ProfileFiles
import java.io.File

/**
 * One reentrant monitor for synchronous file/settings/metadata access. Never suspend
 * or acquire ProfileMutationGate while holding it. Coroutine writers take that gate
 * first, then enter here only for their synchronous commit. Recovery does not acquire
 * the coroutine gate and uses an explicit raw DAO identity lookup.
 */
internal object ProfileBindingStorage {
    private val barrier = RecoveringProfileStorage(ChainBindings) {
        journal.recover().also { recovered ->
            if (recovered) Settings.dataStore.notifyCommitted(ProfileBindingJournal.bindingKeys)
        }
    }
    private val journal: ProfileBindingJournal
        get() = ProfileBindingJournal(
            database = Settings::bindingDatabase,
            stageDirectory = File(Application.application.noBackupFilesDir, "profile-binding-commits"),
            profilePath = ProfileManager::pathForBindingRecovery,
            profileDirectory = File(Application.application.filesDir, "configs"),
        )

    fun <T> access(action: () -> T): T = io.nekohasekai.sfa.utils.RestoreStorageAccess.access { barrier.access(action) }

    fun <T> mutate(action: () -> T): T = io.nekohasekai.sfa.utils.RestoreStorageAccess.access { barrier.mutate(action) }

    fun readText(file: File): String = access { file.readText() }

    fun readBytes(file: File): ByteArray = access { file.readBytes() }

    fun writeAtomically(file: File, content: String) = mutate { ProfileFiles.writeAtomically(file, content) }

    fun writeIfUnchanged(file: File, expected: String, content: String): Boolean = mutate {
        ProfileFiles.writeIfUnchanged(file, expected, content)
    }

    fun commit(profileId: Long, file: File, expected: String, content: String, binding: ChainBinding?): Boolean = mutate {
        require(binding == null || binding.profileId == profileId)
        val next = ChainBindings.all().associateBy { it.profileId }.toMutableMap()
        if (binding == null) next.remove(profileId) else next[profileId] = binding
        val entries = ProfileBindingJournal.entries(ChainBindingCodec.snapshot(next, Settings.selectedProfile))
        journal.commit(profileId, file, expected, content, entries).also { applied ->
            if (applied) Settings.dataStore.notifyCommitted(ProfileBindingJournal.bindingKeys)
        }
    }

    /** Linearization snapshot for startup/reload. A concurrent mutation requires a retry. */
    fun runtimeEpoch(profileId: Long, raw: String): Long = access {
        val path = ProfileManager.pathForBindingRecovery(profileId) ?: error("配置已被删除，请重试")
        check(File(path).readText() == raw) { "配置已变化，请重试启动" }
        barrier.currentEpoch()
    }

    fun requireRuntimeEpoch(expected: Long) = io.nekohasekai.sfa.utils.RestoreStorageAccess.access { barrier.requireEpoch(expected) }
}

/** Shared by production entry points and fault-injection tests. No suspending work inside. */
internal class RecoveringProfileStorage(private val monitor: Any, private val recover: () -> Boolean) {
    private var epoch = 0L

    fun <T> access(action: () -> T): T = synchronized(monitor) {
        if (recover()) epoch++
        action()
    }

    fun <T> mutate(action: () -> T): T = access {
        epoch++ // A partial or failed mutation invalidates an in-flight startup too.
        action()
    }

    fun currentEpoch(): Long = access { epoch }

    fun requireEpoch(expected: Long) = access {
        check(epoch == expected) { "配置或链路已变化，请重试启动" }
    }
}
