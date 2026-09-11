package net.dexxicon.reader.core.database

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/** iOS has no prior install to preserve — this is a fresh database in the app's Documents
 * directory, created by the same migration chain up to the current version (issue #58). */
@OptIn(ExperimentalForeignApi::class)
fun getDatabaseBuilder(): RoomDatabase.Builder<DexxiconDatabase> {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    val dbFilePath = requireNotNull(documentDirectory?.path) { "No Documents directory" } +
        "/" + DexxiconDatabase.NAME
    return Room.databaseBuilder<DexxiconDatabase>(name = dbFilePath)
}
