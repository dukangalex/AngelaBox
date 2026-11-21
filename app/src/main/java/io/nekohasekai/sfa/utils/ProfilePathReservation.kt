package io.nekohasekai.sfa.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Caller must hold the restore lease through file+database commit and check referenced
 * paths under the same storage gate. Markers must not be deleted on uncertain outcomes.
 * Experimental integration; marker collection and device power-loss acceptance remain open. */
internal class ProfilePathReservation(
    private val configs: File,
    private val markers: File,
    private val syncDirectory: (File) -> Unit,
    private val isReferenced: (File) -> Boolean,
    private val nextId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    fun reserve(): File {
        listOf(configs, markers).forEach { directory ->
            val parent = directory.absoluteFile.parentFile ?: throw IOException("Missing directory parent")
            if (!directory.isDirectory && !directory.mkdir() && !directory.isDirectory) {
                throw IOException("Cannot create profile directory")
            }
            // Even if another process created the directory, its parent barrier may be pending.
            syncDirectory(parent)
        }
        repeat(10_000) {
            val id = nextId()
            if (!id.matches(Regex("[0-9a-f]{32}"))) throw IOException("Invalid reservation ID")
            val marker = File(markers, "$id.reserved")
            val target = File(configs, "profile-$id.json").absoluteFile
            if (!marker.createNewFile()) return@repeat
            // Persist reservation before exposing a path. Retain the marker on every failure.
            FileOutputStream(marker, true).use { it.fd.sync() }
            syncDirectory(markers)
            if (target.canonicalFile != File(configs.canonicalFile, target.name) ||
                target.exists() || isReferenced(target)
            ) {
                return@repeat
            }
            return target
        }
        throw IOException("Cannot reserve a unique profile path")
    }
}
