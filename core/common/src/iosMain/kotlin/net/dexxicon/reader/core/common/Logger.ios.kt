package net.dexxicon.reader.core.common

import platform.Foundation.NSLog

actual object Logger {
    actual fun i(tag: String, message: String) {
        NSLog("[$tag] $message")
    }

    actual fun w(tag: String, message: String) {
        NSLog("[$tag] WARN $message")
    }
}
