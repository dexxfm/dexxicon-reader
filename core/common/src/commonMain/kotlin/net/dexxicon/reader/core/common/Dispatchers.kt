package net.dexxicon.reader.core.common

/** The dispatchers the app injects. The `@Dispatcher` qualifier that selects one is
 *  JVM-only (`javax.inject`) and lives in `androidMain`. */
enum class DexxiconDispatcher { Default, IO }
