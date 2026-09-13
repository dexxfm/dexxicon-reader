package net.dexxicon.reader.core.datastore

// Matches the exact location `Context.preferencesDataStore("player_prefs")` used before this
// class moved to commonMain — see AppPreferencesStore.android.kt's actual for the same
// reasoning (that delegate creates "datastore/" under filesDir itself).
actual fun playerPreferencesFilePath(context: PlatformStorageContext): String {
    val dir = context.context.filesDir.resolve("datastore").apply { mkdirs() }
    return dir.resolve("player_prefs.preferences_pb").absolutePath
}
