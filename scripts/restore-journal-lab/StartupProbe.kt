package io.nekohasekai.sfa.utils

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ

/** Host startup wiring probe, matching attachBaseContext's gate/replay ordering. */
fun main(args: Array<String>) {
    val host = object : RestoreRecoveryJournal.Durability {
        override fun isSymbolicLink(file: File) = Files.isSymbolicLink(file.toPath())
        override fun syncDirectory(directory: File) { FileChannel.open(directory.toPath(), READ).use { it.force(true) } }
    }
    val gate = RestoreAccessGate()
    gate.initialize { RestoreRecoveryJournal.open(File(args[0]), host).use { it.recover() } }
    gate.access { println("RECOVERY_COMPLETE_ACCESS_ADMITTED") }
}
