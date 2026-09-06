package net.dexxicon.reader.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.dexxicon.reader.core.database.DexxiconDatabase
import net.dexxicon.reader.core.database.dao.DownloadDao
import net.dexxicon.reader.core.database.dao.HighlightDao
import net.dexxicon.reader.core.database.dao.ReadingProgressDao
import net.dexxicon.reader.core.database.dao.ServerDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `reading_progress` (
                    `key` TEXT NOT NULL,
                    `serverId` TEXT NOT NULL,
                    `bookId` TEXT NOT NULL,
                    `percent` REAL,
                    `locator` TEXT,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `downloads` (
                    `key` TEXT NOT NULL,
                    `serverId` TEXT NOT NULL,
                    `bookId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `authors` TEXT NOT NULL,
                    `series` TEXT,
                    `coverUrl` TEXT,
                    `format` TEXT NOT NULL,
                    `sourceUrl` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `downloadedBytes` INTEGER NOT NULL,
                    `totalBytes` INTEGER,
                    `localPath` TEXT,
                    `error` TEXT,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent(),
            )
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `servers` ADD COLUMN `koSyncUrl` TEXT")
            db.execSQL("ALTER TABLE `servers` ADD COLUMN `koSyncUsername` TEXT")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `highlights` (
                    `id` TEXT NOT NULL,
                    `serverId` TEXT NOT NULL,
                    `bookId` TEXT NOT NULL,
                    `locatorJson` TEXT NOT NULL,
                    `progression` REAL NOT NULL,
                    `text` TEXT NOT NULL,
                    `note` TEXT,
                    `color` TEXT NOT NULL,
                    `chapterTitle` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `remoteId` TEXT,
                    `dirty` INTEGER NOT NULL,
                    `deleted` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_serverId_bookId` ON `highlights` (`serverId`, `bookId`)")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DexxiconDatabase =
        Room.databaseBuilder(context, DexxiconDatabase::class.java, DexxiconDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

    @Provides
    fun provideServerDao(database: DexxiconDatabase): ServerDao = database.serverDao()

    @Provides
    fun provideReadingProgressDao(database: DexxiconDatabase): ReadingProgressDao =
        database.readingProgressDao()

    @Provides
    fun provideDownloadDao(database: DexxiconDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun provideHighlightDao(database: DexxiconDatabase): HighlightDao = database.highlightDao()
}
