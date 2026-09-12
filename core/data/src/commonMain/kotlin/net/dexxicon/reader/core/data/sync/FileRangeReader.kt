package net.dexxicon.reader.core.data.sync

/**
 * Reads up to [length] bytes starting at [offset] in the file at [path] — [KoSyncRepository]'s
 * one piece of genuinely platform-specific file I/O (`java.io.File`/`RandomAccessFile` aren't
 * available on Kotlin/Native). Returns fewer bytes (or an empty array) at/after EOF, or if
 * [path] doesn't exist. androidMain: `RandomAccessFile`, unchanged from before this class
 * moved to commonMain. iosMain: `NSFileHandle`.
 */
expect fun readFileRange(path: String, offset: Long, length: Int): ByteArray
