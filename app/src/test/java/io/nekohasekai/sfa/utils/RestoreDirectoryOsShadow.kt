package io.nekohasekai.sfa.utils

import android.system.Os
import java.io.File
import java.io.FileDescriptor
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.IdentityHashMap
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Robolectric's Linux shadow cannot open directories. Keep the real host directory-force
 * barrier rather than turning durability into a no-op; not proof of Android's Os implementation. */
@Implements(Os::class)
class RestoreDirectoryOsShadow {
    companion object {
        private val channels = IdentityHashMap<FileDescriptor, FileChannel>()
        @Implementation @JvmStatic fun open(path: String, flags: Int, mode: Int): FileDescriptor {
            check(File(path).isDirectory) { "unexpected non-directory Os.open in restore test: $path" }
            return FileDescriptor().also { descriptor -> synchronized(channels) {
                channels[descriptor] = FileChannel.open(File(path).toPath(), StandardOpenOption.READ)
            } }
        }
        @Implementation @JvmStatic fun fsync(fd: FileDescriptor) {
            synchronized(channels) { checkNotNull(channels[fd]).force(true) }
        }
        @Implementation @JvmStatic fun close(fd: FileDescriptor) {
            synchronized(channels) { checkNotNull(channels.remove(fd)).close() }
        }
    }
}
