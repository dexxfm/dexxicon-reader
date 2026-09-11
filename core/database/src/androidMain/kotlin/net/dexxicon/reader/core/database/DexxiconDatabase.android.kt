package net.dexxicon.reader.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/** Same db file, path, and name as the pre-KMP setup — an existing install's database opens
 * unchanged; only the migrations below it have moved (issue #58). */
fun getDatabaseBuilder(context: Context): RoomDatabase.Builder<DexxiconDatabase> {
    val dbFile = context.applicationContext.getDatabasePath(DexxiconDatabase.NAME)
    return Room.databaseBuilder<DexxiconDatabase>(context.applicationContext, dbFile.absolutePath)
}
