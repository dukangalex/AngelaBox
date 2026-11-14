package io.nekohasekai.sfa.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.security.MessageDigest

/**
 * Durable preimages for the two databases (including WAL/SHM/rollback journals) and configs as one recovery unit.
 *
 * NOT a live-data lock. The caller must exclude ALL database/config users, including native
 * threads, for the entire session, and recover before any reader opens on process startup.
 * Not wired into BackupManager until those preconditions have an Android-validated owner.
 * Copying a live SQLite database/WAL pair here is NOT a valid SQLite snapshot.
 *
 * Paths are fixed by application code, never deserialized from a backup or journal. No NIO
 * filesystem APIs (API 26+) are used by the core; Android's durability adapter uses Os (API 21).
 */
internal class RestoreRecoveryJournal private constructor(
    private val root: File,
    private val io: Durability,
    private val lockFile: RandomAccessFile,
    private val lock: FileLock,
) : AutoCloseable {
    internal interface Durability {
        fun isSymbolicLink(file: File): Boolean
        fun syncDirectory(directory: File)
    }

    companion object {
        internal val targets = listOf(
            "databases/settings.db", "databases/settings.db-wal", "databases/settings.db-shm", "databases/settings.db-journal",
            "databases/profiles.db", "databases/profiles.db-wal", "databases/profiles.db-shm", "databases/profiles.db-journal",
            "files/configs",
        )
        private val manifest = "AngelaBox restore preimages v1\n" + targets.joinToString("\n", postfix = "\n")

        /** Process lock coordinates journal owners, NOT existing Room/config readers. */
        fun open(dataDirectory: File, durability: Durability): RestoreRecoveryJournal {
            val root = dataDirectory.canonicalFile
            require(root.isDirectory) { "Missing application data directory" }
            val path = File(root, ".restore-recovery.lock")
            check(!durability.isSymbolicLink(path)) { "Invalid restore lock" }
            val file = RandomAccessFile(path, "rw")
            try {
                val lock = file.channel.tryLock() ?: throw IOException("Restore recovery already in progress")
                return RestoreRecoveryJournal(root, durability, file, lock)
            } catch (error: Throwable) {
                file.close()
                throw error
            }
        }
    }

    private val journal = File(root, ".restore-recovery")
    private val retired = File(root, ".restore-recovery-retired")
    private var closed = false
    private var preparedHere = false

    private fun requireOpen() = check(!closed) { "Restore journal is closed" }
    private fun requirePlain(file: File) {
        check(!io.isSymbolicLink(file)) { "Symbolic link in restore path" }
        check(file.absoluteFile == file.canonicalFile) { "Non-canonical restore path" }
    }
    private fun exists(file: File): Boolean {
        requirePlain(file)
        return file.exists()
    }
    private fun mkdir(directory: File) {
        requirePlain(directory)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create restore directory" }
        requirePlain(directory)
    }
    private fun children(directory: File): List<File> {
        requirePlain(directory)
        check(directory.isDirectory) { "Invalid restore directory" }
        return (directory.listFiles() ?: throw IOException("Cannot list restore directory")).sortedBy { it.name }
    }
    private fun writeSynced(file: File, bytes: ByteArray) {
        requirePlain(file)
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    }
    private fun marker(name: String) {
        val temporary = File(journal, ".$name.tmp")
        val final = File(journal, name)
        check(!exists(final)) { "Restore marker already exists" }
        writeSynced(temporary, "1\n".toByteArray())
        check(temporary.renameTo(final)) { "Cannot publish restore marker" }
        io.syncDirectory(journal)
    }
    private fun validMarker(name: String): Boolean {
        val file = File(journal, name)
        if (!exists(file)) return false
        check(file.isFile && file.length() == 2L && file.readText() == "1\n") { "Invalid restore marker" }
        return true
    }
    private fun copyTree(source: File, destination: File) {
        requirePlain(source)
        requirePlain(destination)
        if (source.isDirectory) {
            mkdir(destination)
            children(source).forEach { copyTree(it, File(destination, it.name)) }
            io.syncDirectory(destination)
        } else {
            check(source.isFile) { "Non-regular restore preimage" }
            mkdir(destination.parentFile!!)
            source.inputStream().use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output); output.fd.sync() }
            }
        }
    }
    private fun removeTree(file: File) {
        if (!exists(file)) return
        if (file.isDirectory) children(file).forEach(::removeTree)
        check(file.delete()) { "Cannot remove restore file" }
    }
    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun visit(current: File, path: String) {
            requirePlain(current)
            val name = path.toByteArray(Charsets.UTF_8)
            digest.update(if (current.isDirectory) 1.toByte() else 2.toByte())
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(name.size).array())
            digest.update(name)
            if (current.isDirectory) children(current).forEach { visit(it, "$path/${it.name}") }
            else {
                check(current.isFile) { "Non-regular restore preimage" }
                digest.update(java.nio.ByteBuffer.allocate(8).putLong(current.length()).array())
                current.inputStream().use { input ->
                    val bytes = ByteArray(65536)
                    while (true) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        digest.update(bytes, 0, count)
                    }
                }
            }
        }
        visit(file, "")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun validateManifest() {
        val saved = File(journal, "manifest")
        requirePlain(saved)
        check(saved.isFile && saved.length() == manifest.toByteArray().size.toLong() && saved.readText() == manifest) {
            "Unknown restore journal format; recovery required"
        }
    }

    /** May run only after service termination AND DB/config access exclusion are established. */
    fun prepare() {
        requireOpen()
        check(!exists(journal) && !exists(retired)) { "Recover previous restore before preparing another" }
        // Check parent components and targets before constructing any preimage.
        listOf(File(root, "databases"), File(root, "files")).forEach { requirePlain(it) }
        targets.forEach { requirePlain(File(root, it)) }
        mkdir(journal)
        io.syncDirectory(root)
        writeSynced(File(journal, "manifest"), manifest.toByteArray())
        val old = File(journal, "old")
        mkdir(old)
        targets.forEachIndexed { index, name ->
            val source = File(root, name)
            val slot = File(old, index.toString())
            mkdir(slot)
            if (exists(source)) {
                val saved = File(slot, "data")
                copyTree(source, saved)
                writeSynced(File(slot, "present"), (fingerprint(saved) + "\n").toByteArray())
            } else writeSynced(File(slot, "absent"), "1\n".toByteArray())
            io.syncDirectory(slot)
        }
        io.syncDirectory(old)
        io.syncDirectory(journal)
        marker("PREPARED") // No caller may mutate live targets before this returns successfully.
        preparedHere = true
    }

    /**
     * Call only after all new database handles are closed. This flushes all target data and
     * parent directories before publishing the commit marker. A thrown commit has an UNKNOWN outcome: retain the journal and end access;
     * do not roll back or retry the mutation blindly. Startup recovery chooses from its marker.
     */
    fun commit() {
        requireOpen()
        check(preparedHere) { "Only the preparing session may commit; recover after reopening" }
        validateManifest()
        check(validMarker("PREPARED")) { "Restore not prepared" }
        targets.forEach { name ->
            val target = File(root, name)
            syncTree(target)
            if (target.parentFile!!.isDirectory) io.syncDirectory(target.parentFile!!)
        }
        io.syncDirectory(root) // Persist newly created files/databases parent entries too.
        marker("COMMITTED")
    }

    private fun checkTreePaths(file: File) {
        if (!exists(file)) return
        if (file.isDirectory) children(file).forEach(::checkTreePaths)
        else check(file.isFile) { "Non-regular restore target" }
    }

    private fun syncTree(file: File) {
        if (!exists(file)) return
        if (file.isDirectory) {
            children(file).forEach(::syncTree)
            io.syncDirectory(file)
        } else {
            check(file.isFile) { "Non-regular restored target" }
            RandomAccessFile(file, "rw").use { it.fd.sync() }
        }
    }

    /** Must finish before ANY DB/config access. Safe to repeat after process death during rollback. */
    fun recover(afterTarget: (Int) -> Unit = {}) {
        requireOpen()
        preparedHere = false
        cleanupRetired()
        if (!exists(journal)) return
        check(journal.isDirectory) { "Invalid restore journal" }
        if (!validMarker("PREPARED")) {
            // prepare did not authorize live mutation, so only partial preimages need removing.
            retireAndCleanup()
            return
        }
        validateManifest()
        if (validMarker("COMMITTED")) { retireAndCleanup(); return }
        // Validate ALL saved contents before touching the first live target.
        targets.indices.forEach { index ->
            val slot = File(journal, "old/$index")
            val present = File(slot, "present")
            val absent = File(slot, "absent")
            check(exists(present).xor(exists(absent))) { "Missing/ambiguous restore preimage" }
            if (present.exists()) {
                check(present.isFile && present.length() == 65L && present.readText() == fingerprint(File(slot, "data")) + "\n") {
                    "Corrupt restore preimage"
                }
            } else check(absent.isFile && absent.length() == 2L && absent.readText() == "1\n") { "Invalid absence marker" }
        }
        targets.forEach { checkTreePaths(File(root, it)) }
        targets.forEachIndexed { index, name ->
            val destination = File(root, name)
            val slot = File(journal, "old/$index")
            // All readers remain excluded; a kill here is repaired by replaying the immutable preimages.
            removeTree(destination)
            mkdir(destination.parentFile!!)
            if (File(slot, "present").exists()) copyTree(File(slot, "data"), destination)
            io.syncDirectory(destination.parentFile!!)
            afterTarget(index)
        }
        io.syncDirectory(root)
        // Rolled-back bytes are now durable. This also makes cleanup interruption unambiguous.
        marker("COMMITTED")
        retireAndCleanup()
    }

    /** After successful commit, cleanup errors must be reported as COMMITTED, never as an aborted restore. */
    fun cleanupCommitted() {
        requireOpen()
        validateManifest()
        check(validMarker("PREPARED") && validMarker("COMMITTED")) { "Restore not committed" }
        retireAndCleanup()
    }
    private fun retireAndCleanup() {
        check(!exists(retired)) { "Retired restore journal still present" }
        check(journal.renameTo(retired)) { "Cannot retire restore journal" }
        io.syncDirectory(root) // Never delete a preimage/marker before retirement itself is durable.
        cleanupRetired()
    }
    private fun cleanupRetired() {
        if (!exists(retired)) return
        check(retired.isDirectory) { "Invalid retired restore journal" }
        io.syncDirectory(root) // Also covers a previous retirement whose fsync reported an error.
        removeTree(retired)
        io.syncDirectory(root)
    }
    override fun close() {
        if (closed) return
        closed = true
        try { lock.release() } finally { lockFile.close() }
    }
}
