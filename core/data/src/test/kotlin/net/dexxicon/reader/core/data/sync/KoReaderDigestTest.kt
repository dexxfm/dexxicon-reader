package net.dexxicon.reader.core.data.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class KoReaderDigestTest {

    @Test
    fun `offsets match KOReader util_partialMD5`() {
        // 0, then 1024 << (2*i) for i in 0..10
        assertThat(KoReaderDigest.offsets.toList()).containsExactly(
            0L, 1024L, 4096L, 16384L, 65536L, 262144L, 1_048_576L, 4_194_304L,
            16_777_216L, 67_108_864L, 268_435_456L, 1_073_741_824L,
        ).inOrder()
    }

    @Test
    fun `digest matches reference implementation and stops at EOF`() = runTest {
        val size = 5000
        val data = ByteArray(size) { (it % 251).toByte() }

        val digest = KoReaderDigest.compute { offset, length ->
            if (offset >= size) ByteArray(0)
            else data.copyOfRange(offset.toInt(), minOf(size, (offset + length).toInt()))
        }

        // Cross-checked against the same offsets/samples in Python's hashlib.
        assertThat(digest).isEqualTo("e77dcca7f22a949ae8492c260ca19f32")
    }
}
