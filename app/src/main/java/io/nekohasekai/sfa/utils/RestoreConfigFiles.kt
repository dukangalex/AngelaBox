package io.nekohasekai.sfa.utils

import java.io.File
import java.io.IOException
import java.util.UUID

/** Prepare all file contents before replacing any live config. This is not a
 * transaction across configs and databases: a rename/prune failure or process
 * death during promotion can still leave a partial restore. */
internal fun restoreConfigFiles(staged: File, live: File, copy: (File, File) -> Unit = { from, to ->
    from.copyTo(to, overwrite = true)
}) {
    val stagedFiles = staged.listFiles() ?: throw IOException("无法读取备份配置目录")
    val incoming = stagedFiles.filter { it.isFile }
    val existing = live.listFiles() ?: throw IOException("无法读取现有配置目录")
    val scratch = File(live, ".restore-${UUID.randomUUID()}")
    if (incoming.any { it.name == scratch.name } || !scratch.mkdir()) {
        throw IOException("无法创建配置恢复暂存目录")
    }
    val prepared = mutableListOf<Pair<File, File>>()
    try {
        incoming.forEach { from ->
            // The private subdirectory is on the same filesystem as live.
            // A partial copy never touches the destination.
            val temp = File.createTempFile("config-", ".tmp", scratch)
            prepared.add(temp to File(live, from.name))
            copy(from, temp)
        }
        prepared.forEach { (temp, dest) ->
            if (!temp.renameTo(dest)) throw IOException("无法替换配置文件: ${dest.name}")
        }
        val names = incoming.map { it.name }.toSet()
        existing.filter { it.isFile && it.name !in names }.forEach {
            if (!it.delete()) throw IOException("无法删除旧配置文件: ${it.name}")
        }
    } finally {
        scratch.deleteRecursively()
    }
}
