package io.nekohasekai.sfa.utils

import java.io.File
import java.io.IOException

/** Copy all staged files before pruning; any failure must reach the restore caller. */
internal fun restoreConfigFiles(staged: File, live: File, copy: (File, File) -> Unit = { from, to ->
    from.copyTo(to, overwrite = true)
}) {
    val stagedFiles = staged.listFiles() ?: throw IOException("无法读取备份配置目录")
    val incoming = stagedFiles.filter { it.isFile }
    val existing = live.listFiles() ?: throw IOException("无法读取现有配置目录")
    incoming.forEach { copy(it, File(live, it.name)) }
    val names = incoming.map { it.name }.toSet()
    existing.filter { it.isFile && it.name !in names }.forEach {
        if (!it.delete()) throw IOException("无法删除旧配置文件: ${it.name}")
    }
}
