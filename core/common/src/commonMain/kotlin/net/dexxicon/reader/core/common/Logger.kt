package net.dexxicon.reader.core.common

/**
 * Phase 4 restructure (issue #126) — the one thing several `core:data` sync classes needed
 * from `android.util.Log` to become portable. `androidMain`'s actual wraps `Log.i`/`Log.w`
 * directly (same tag/message shape callers already use); `iosMain`'s wraps `NSLog`.
 */
expect object Logger {
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
}
