package net.dexxicon.reader.shared.home

import net.dexxicon.reader.core.common.currentTimeMillis

/**
 * Portable replacement (issue #130) for native's old `feature/library/LibraryScreen.kt`
 * `relativeTime()`, which called `android.text.format.DateUtils.getRelativeTimeSpanString(...)`
 * directly — not available on Kotlin/Native. Approximates the same shape ("just now", then
 * "Nm ago"/"Nh ago"/"Nd ago") rather than reproducing `DateUtils`'s exact wording/thresholds,
 * which are an Android implementation detail, not a contract worth matching byte-for-byte.
 */
internal fun relativeTime(atMillis: Long): String {
    if (atMillis <= 0L) return "just now"
    val minutes = (currentTimeMillis() - atMillis).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}
