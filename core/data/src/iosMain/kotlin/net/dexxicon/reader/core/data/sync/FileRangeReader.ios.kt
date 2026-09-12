package net.dexxicon.reader.core.data.sync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.lseek
import platform.posix.open
import platform.posix.read

/** Plain POSIX (`open`/`lseek`/`read`/`close`) rather than Foundation's `NSFileHandle` — the
 * C interop here is stable and well-documented, unlike guessing at Kotlin/Native's
 * Objective-C method-name bridging for `NSFileHandle`'s class factory methods. */
@OptIn(ExperimentalForeignApi::class)
actual fun readFileRange(path: String, offset: Long, length: Int): ByteArray {
    val fd = open(path, O_RDONLY)
    if (fd < 0) return ByteArray(0)
    try {
        if (lseek(fd, offset, SEEK_SET) < 0) return ByteArray(0)
        val buffer = ByteArray(length)
        var totalRead = 0
        buffer.usePinned { pinned ->
            while (totalRead < length) {
                val n = read(fd, pinned.addressOf(totalRead), (length - totalRead).toULong())
                if (n <= 0) break
                totalRead += n.toInt()
            }
        }
        return if (totalRead == length) buffer else buffer.copyOf(totalRead)
    } finally {
        close(fd)
    }
}
