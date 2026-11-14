package io.nekohasekai.sfa.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * X-3: the one way profile archive files get written.
 *
 * A profile file used to be written two ways: the background worker wrote
 * tmp + fsync + rename, while the foreground update paths used a bare
 * `File.writeText` — a truncate-then-write that leaves a half-written JSON
 * behind if the process dies mid-write, corrupting the profile, and that a
 * concurrent serviceReload can read mid-write. Every writer of a profile's
 * stored config goes through here so the archive is never observable in a
 * partial state.
 */
object ProfileFiles {

    /** Synchronous file lease only; callers must still guard DB/path lookup plus IO together. */
    fun readText(file: File): String = ApplicationDataAccess.access { file.readText() }

    fun readBytes(file: File): ByteArray = ApplicationDataAccess.access { file.readBytes() }

    fun delete(file: File): Boolean = ApplicationDataAccess.access { file.delete() }

    @Throws(IOException::class)
    fun writeAtomically(file: File, content: String) = ApplicationDataAccess.access {
        writeAtomicallyAdmitted(file, content)
    }

    /** Pure host-testable operation, called only inside the application's admission lease. */
    internal fun writeAtomicallyAdmitted(file: File, content: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        // Unique sibling: two admitted writers must never truncate, rename or
        // delete each other's temporary output. Restore drains both leases.
        val tmp = File.createTempFile("profile-", ".tmp", file.absoluteFile.parentFile)
        try {
            tmp.writeText(content)
            // fsync so a power loss right after the rename cannot resurrect
            // a zero-length or partial file.
            FileOutputStream(tmp, true).channel.use { it.force(true) }
            if (!tmp.renameTo(file)) {
                throw IOException("原子写入配置失败")
            }
        } finally {
            // A killed process can leave a unique scratch file. In-process
            // failures clean theirs without touching another writer's output.
            if (tmp.exists()) tmp.delete()
        }
    }
}
