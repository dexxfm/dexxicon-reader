package net.dexxicon.reader.core.common

/**
 * Phase 4 restructure (issue #126) — the portable replacement for `System.currentTimeMillis()`
 * (JVM-only, not available on Kotlin/Native) that the newly-commonMain sync classes need.
 */
expect fun currentTimeMillis(): Long
