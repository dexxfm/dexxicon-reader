package net.dexxicon.reader.core.datastore

// Matches the exact location `Context.preferencesDataStore("app_prefs")` used before this
// class moved to commonMain (that delegate creates "datastore/" under filesDir itself) — see
// this class's doc comment for why the path must match precisely.
actual fun appPreferencesFilePath(context: PlatformStorageContext): String {
    val dir = context.context.filesDir.resolve("datastore").apply { mkdirs() }
    return dir.resolve("app_prefs.preferences_pb").absolutePath
}
