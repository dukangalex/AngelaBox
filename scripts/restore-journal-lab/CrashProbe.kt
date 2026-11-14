package io.nekohasekai.sfa.utils

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ

/** Host-only probe. Target contents are test bytes, NOT SQLite databases. */
fun main(args: Array<String>) {
    val host = object : RestoreRecoveryJournal.Durability {
        override fun isSymbolicLink(file: File) = Files.isSymbolicLink(file.toPath())
        override fun syncDirectory(directory: File) {
            FileChannel.open(directory.toPath(), READ).use { it.force(true) }
        }
    }
    RestoreRecoveryJournal.open(File(args[1]), host).use { journal ->
        when (args[0]) {
            "prepare" -> journal.prepare()
            "recover" -> journal.recover { index ->
                if (args.size > 2 && args[2].toInt() == index) Runtime.getRuntime().halt(73)
            }
            else -> error("Unknown probe command")
        }
    }
}
