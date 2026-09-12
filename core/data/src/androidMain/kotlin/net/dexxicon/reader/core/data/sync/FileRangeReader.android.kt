package net.dexxicon.reader.core.data.sync

import java.io.File
import java.io.RandomAccessFile

actual fun readFileRange(path: String, offset: Long, length: Int): ByteArray {
    val file = File(path)
    if (!file.exists() || offset >= file.length()) return ByteArray(0)
    RandomAccessFile(file, "r").use { raf ->
        raf.seek(offset)
        val buffer = ByteArray(minOf(length.toLong(), file.length() - offset).toInt())
        var read = 0
        while (read < buffer.size) {
            val n = raf.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }
}
