package io.nekohasekai.sfa.utils

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Undo journal for a set of app-private roots. The caller closes ALL database handles and
 * excludes readers/writers before entering. Immutable old bytes are durable before READY;
 * mutations before COMMITTED are rolled back on restart, including an interrupted rollback.
 * Targets come from code, never from an imported backup or the journal's contents.
 * This models process termination, not every storage controller's power-loss behaviour.
 */
internal class RestoreJournal(
    directory: File,
    targets: Map<String, File>,
    private val syncDirectory: (File) -> Unit,
    private val cut: (String) -> Unit = {},
) {
    private val directory = directory.canonicalFile
    private val targets = targets.toSortedMap().mapValues { (_, file) ->
        // Reject a redirected root before canonicalisation could hide the link.
        val normalized = File(file.absoluteFile.parentFile!!.canonicalFile, file.name)
        check(normalized == normalized.canonicalFile) { "恢复事务不接受符号链接" }
        normalized
    }
    private val ready get() = File(directory, "READY")
    private val committed get() = File(directory, "COMMITTED")
    private val originals get() = File(directory, "originals")

    init {
        require(targets.isNotEmpty() && targets.keys.all { it.matches(Regex("[a-z0-9_-]+")) })
        require(this.targets.values.none { it == this.directory || it.path.startsWith(this.directory.path + "/") })
        val paths = this.targets.values.toList()
        require(paths.toSet().size == paths.size)
        require(paths.indices.all { i -> paths.indices.all { j -> i == j || !paths[i].path.startsWith(paths[j].path + "/") } })
    }

    fun needsRecovery(): Boolean = ready.exists() && !committed.exists()

    fun begin() {
        check(!needsRecovery()) { "上次恢复尚未完成，请保留应用数据并重启重试" }
        discardCompletedOrUnstarted()
        check(directory.mkdirs()) { "无法准备恢复事务目录" }
        syncDirectory(directory.parentFile!!)
        check(originals.mkdir())
        val records = targets.map { (name, target) ->
            val copy = File(originals, name)
            if (target.exists()) copyTree(target, copy)
            cut("snapshot_$name")
            name to if (copy.exists()) fingerprint(copy) else "absent"
        }
        writeDurably(File(directory, "index")) { out ->
            DataOutputStream(out).apply {
                writeInt(1)
                writeInt(records.size)
                records.forEach { (name, hash) -> writeUTF(name); writeUTF(hash) }
                flush()
            }
        }
        syncDirectory(originals)
        marker(ready)
        cut("ready")
    }

    fun commit() {
        check(ready.isFile)
        // The caller has closed SQLite first. Include every root and sidecar in the durability
        // barrier: a process death before COMMITTED always restores the complete old set.
        targets.values.filter(File::exists).forEach(::syncTree)
        targets.values.mapNotNull { it.parentFile }.distinct().forEach(syncDirectory)
        cut("before_commit")
        marker(committed)
        cut("committed")
        discardCompletedOrUnstarted()
    }

    fun recover(): Boolean {
        cleanFinishedGarbage()
        if (!directory.exists()) return false
        if (committed.exists()) {
            requireMarker(committed)
            discardCompletedOrUnstarted()
            return false
        }
        if (!ready.exists()) {
            // No live mutation is permitted until READY is installed.
            discardCompletedOrUnstarted()
            return false
        }
        requireMarker(ready)
        val records = DataInputStream(File(directory, "index").inputStream()).use { input ->
            check(input.readInt() == 1 && input.readInt() == targets.size) { "恢复事务索引损坏" }
            targets.keys.associateWith { expected ->
                check(input.readUTF() == expected) { "恢复事务目标不匹配" }
                input.readUTF().also { check(it == "absent" || it.matches(Regex("[0-9a-f]{64}"))) }
            }.also { check(input.read() == -1) }
        }
        // Verify ALL old roots before touching any live root. An unreadable/corrupt journal
        // must remain in place and block storage, never be mistaken for an empty old dataset.
        records.forEach { (name, hash) ->
            val saved = File(originals, name)
            check(if (hash == "absent") !saved.exists() else saved.exists() && fingerprint(saved) == hash) {
                "恢复事务原始数据不完整，请保留应用数据"
            }
        }
        val displaced = File(directory, "displaced").apply { check(isDirectory || mkdir()) }
        records.forEach { (name, hash) ->
            val target = targets.getValue(name)
            val parent = target.parentFile!!
            check(parent.isDirectory || parent.mkdirs())
            val replacement = File(parent, ".${target.name}.restore-${UUID.randomUUID()}")
            if (hash != "absent") copyTree(File(originals, name), replacement)
            cut("prepared_$name")
            if (target.exists()) {
                check(target.renameTo(File(displaced, "$name-${UUID.randomUUID()}"))) { "无法保留恢复中的现有数据" }
                syncDirectory(parent)
                syncDirectory(displaced)
            }
            cut("displaced_$name")
            if (hash != "absent") {
                check(replacement.renameTo(target)) { "无法恢复原有数据" }
                syncDirectory(parent)
            }
            cut("recovered_$name")
        }
        // A rollback may be interrupted after any root; the originals remain immutable until
        // the complete journal is atomically moved out of the active name.
        cut("rolled_back")
        discardCompletedOrUnstarted(rolledBack = true)
        return true
    }

    private fun cleanFinishedGarbage() {
        directory.parentFile?.listFiles()?.filter {
            it.name.startsWith(directory.name + "-finished-") && it.isDirectory && it.canonicalFile == it.absoluteFile
        }?.forEach { it.deleteRecursively() }
    }

    private fun marker(file: File) {
        val temporary = File(directory, file.name + ".new")
        writeDurably(temporary) { it.write("restore-v1\n".toByteArray()) }
        check(temporary.renameTo(file)) { "无法提交恢复事务记录" }
        syncDirectory(directory)
    }

    private fun requireMarker(file: File) {
        check(file.isFile && file.length() == 11L && file.readText() == "restore-v1\n") { "恢复事务状态损坏" }
    }

    private fun discardCompletedOrUnstarted(rolledBack: Boolean = false) {
        if (!directory.exists()) return
        check(rolledBack || !needsRecovery()) { "不能丢弃尚未恢复的数据" }
        // Never recursively delete the active journal: a death halfway through cleanup could
        // erase COMMITTED while leaving READY and cause an obsolete rollback on next launch.
        val garbage = File(directory.parentFile, "${directory.name}-finished-${UUID.randomUUID()}")
        check(directory.renameTo(garbage)) { "无法结束恢复事务" }
        syncDirectory(directory.parentFile!!)
        garbage.deleteRecursively() // Best-effort private garbage only; never a live root.
    }

    private fun checkPlain(file: File) {
        check(file.canonicalFile == file.absoluteFile) { "恢复事务不接受符号链接" }
        check(file.isFile || file.isDirectory) { "无法读取恢复事务数据" }
    }

    private fun copyTree(source: File, destination: File) {
        checkPlain(source)
        if (source.isDirectory) {
            check(destination.mkdir()) { "无法复制恢复目录" }
            val children = source.listFiles() ?: throw IOException("无法枚举恢复目录")
            children.sortedBy { it.name }.forEach { copyTree(it, File(destination, it.name)) }
            syncDirectory(destination)
        } else {
            writeDurably(destination) { output -> source.inputStream().use { it.copyTo(output) } }
        }
    }

    private fun writeDurably(file: File, body: (FileOutputStream) -> Unit) {
        FileOutputStream(file).use { output -> body(output); output.fd.sync() }
    }

    private fun syncTree(file: File) {
        checkPlain(file)
        if (file.isDirectory) {
            (file.listFiles() ?: throw IOException("无法枚举恢复目录")).forEach(::syncTree)
            syncDirectory(file)
        } else FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun fingerprint(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun visit(file: File, relative: String) {
            checkPlain(file)
            val label = (if (file.isDirectory) "D" else "F") + relative + "\u0000"
            digest.update(label.toByteArray(Charsets.UTF_8))
            if (file.isDirectory) {
                (file.listFiles() ?: throw IOException("无法枚举恢复目录")).sortedBy { it.name }
                    .forEach { visit(it, "$relative/${it.name}") }
            } else {
                digest.update(file.length().toString().toByteArray())
                digest.update(0.toByte())
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
            }
        }
        visit(root, "")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
