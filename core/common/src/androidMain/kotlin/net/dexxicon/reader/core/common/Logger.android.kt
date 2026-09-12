package net.dexxicon.reader.core.common

import android.util.Log

actual object Logger {
    actual fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    actual fun w(tag: String, message: String) {
        Log.w(tag, message)
    }
}
