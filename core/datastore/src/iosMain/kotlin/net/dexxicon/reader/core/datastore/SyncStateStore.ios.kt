package net.dexxicon.reader.core.datastore

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual class PlatformStorageContext

actual fun syncStateFilePath(context: PlatformStorageContext): String {
    val documentsDir = NSFileManager.defaultManager.URLsForDirectory(
        NSDocumentDirectory,
        NSUserDomainMask,
    ).firstOrNull() as? platform.Foundation.NSURL
    val basePath = documentsDir?.path ?: NSFileManager.defaultManager.currentDirectoryPath
    return "$basePath/sync_state.preferences_pb"
}
