package net.dexxicon.reader.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import net.dexxicon.reader.core.database.dao.ReadingProgressDao
import net.dexxicon.reader.core.database.dao.ServerDao
import net.dexxicon.reader.core.database.entity.ReadingProgressEntity
import net.dexxicon.reader.core.database.entity.ServerEntity

@Database(
    entities = [
        ServerEntity::class,
        ReadingProgressEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class DexxiconDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun readingProgressDao(): ReadingProgressDao

    companion object {
        const val NAME = "dexxicon.db"
    }
}
