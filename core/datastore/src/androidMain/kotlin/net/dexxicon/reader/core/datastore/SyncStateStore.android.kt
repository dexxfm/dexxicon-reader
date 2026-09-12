package net.dexxicon.reader.core.datastore

import android.content.Context

actual class PlatformStorageContext(val context: Context)

actual fun syncStateFilePath(context: PlatformStorageContext): String =
    context.context.filesDir.resolve("sync_state.preferences_pb").absolutePath
