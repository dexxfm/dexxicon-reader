package net.dexxicon.reader.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import net.dexxicon.reader.core.database.dao.ServerDao
import net.dexxicon.reader.core.database.entity.ServerEntity

@Database(
    entities = [
        ServerEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DexxiconDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    companion object {
        const val NAME = "dexxicon.db"
    }
}
