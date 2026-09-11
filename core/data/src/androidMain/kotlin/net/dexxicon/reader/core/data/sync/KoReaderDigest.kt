package net.dexxicon.reader.core.data.sync

import java.security.MessageDigest

/**
 * KOReader's "partial MD5" document identifier — MD5 over 1 KiB samples taken at a fixed
 * set of byte offsets. Must match `util.partialMD5` in KOReader exactly so progress synced
 * from our app lands on the same document as KOReader's.
 *
 * Offsets: 0, then 1024 << (2*i) for i in 0..10 (KOReader's `i = -1` case wraps to 0 in its
 * 32-bit bit library).
 */
object KoReaderDigest {

    private const val SAMPLE_SIZE = 1024

    val offsets: LongArray = longArrayOf(0L) + LongArray(11) { i -> 1024L shl (2 * i) }

    /**
     * @param readAt reads up to [SAMPLE_SIZE] bytes starting at the given offset, or fewer
     *   (or an empty array) at/after EOF.
     */
    suspend fun compute(readAt: suspend (offset: Long, length: Int) -> ByteArray): String {
        val md5 = MessageDigest.getInstance("MD5")
        for (offset in offsets) {
            val sample = readAt(offset, SAMPLE_SIZE)
            if (sample.isEmpty()) break
            md5.update(sample)
        }
        return md5.digest().joinToString("") { "%02x".format(it) }
    }
}
