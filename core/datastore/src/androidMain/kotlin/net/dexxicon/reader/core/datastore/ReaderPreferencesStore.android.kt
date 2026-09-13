package net.dexxicon.reader.core.datastore

// Matches the exact location `Context.preferencesDataStore("reader_prefs")` used before this
// class moved to commonMain — see AppPreferencesStore.android.kt's actual for the same
// reasoning (that delegate creates "datastore/" under filesDir itself).
actual fun readerPreferencesFilePath(context: PlatformStorageContext): String {
    val dir = context.context.filesDir.resolve("datastore").apply { mkdirs() }
    return dir.resolve("reader_prefs.preferences_pb").absolutePath
}
