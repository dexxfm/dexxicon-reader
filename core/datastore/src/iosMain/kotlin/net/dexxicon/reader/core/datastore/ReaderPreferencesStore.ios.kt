package net.dexxicon.reader.core.datastore

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

actual fun readerPreferencesFilePath(context: PlatformStorageContext): String {
    val documentsDir = NSFileManager.defaultManager.URLsForDirectory(
        NSDocumentDirectory,
        NSUserDomainMask,
    ).firstOrNull() as? NSURL
    val basePath = documentsDir?.path ?: NSFileManager.defaultManager.currentDirectoryPath
    return "$basePath/reader_prefs.preferences_pb"
}
