package io.nekohasekai.sfa.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Caller must hold the restore lease through file+database commit and check referenced
 * paths under the same storage gate. Markers must not be deleted on uncertain outcomes.
 * Collection only removes markers whose target is absent and unreferenced under that gate.
 * Device power-loss acceptance remains open. */
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

    /** Only called under the same lease and synchronous storage gate as creation.
     * A failed directory listing is uncertainty, not evidence that a target is absent.
     */
    fun collectUnreferencedMarkers() {
        val names = configs.list()?.toHashSet() ?: throw IOException("Cannot list profile directory")
        val candidates = markers.listFiles() ?: throw IOException("Cannot list reservation directory")
        var removed = false
        try {
            for (marker in candidates) {
                val match = Regex("([0-9a-f]{32})\\.reserved").matchEntire(marker.name) ?: continue
                if (!marker.isFile || marker.canonicalFile != File(markers.canonicalFile, marker.name)) continue
                val target = File(configs, "profile-${match.groupValues[1]}.json").absoluteFile
                if (target.name in names || isReferenced(target)) continue
                if (!marker.delete()) throw IOException("Cannot remove profile reservation")
                removed = true
            }
        } finally {
            if (removed) syncDirectory(markers)
        }
    }
}
