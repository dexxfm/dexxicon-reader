package net.dexxicon.reader.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import net.dexxicon.reader.core.database.dao.BookmarkDao
import net.dexxicon.reader.core.database.dao.DownloadDao
import net.dexxicon.reader.core.database.dao.HighlightDao
import net.dexxicon.reader.core.database.dao.ReadingProgressDao
import net.dexxicon.reader.core.database.dao.ServerDao
import net.dexxicon.reader.core.database.entity.BookmarkEntity
import net.dexxicon.reader.core.database.entity.DownloadEntity
import net.dexxicon.reader.core.database.entity.HighlightEntity
import net.dexxicon.reader.core.database.entity.ReadingProgressEntity
import net.dexxicon.reader.core.database.entity.ServerEntity

@Database(
    entities = [
        ServerEntity::class,
        ReadingProgressEntity::class,
        DownloadEntity::class,
        HighlightEntity::class,
        BookmarkEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class DexxiconDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun downloadDao(): DownloadDao
    abstract fun highlightDao(): HighlightDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        const val NAME = "dexxicon.db"
    }
}
