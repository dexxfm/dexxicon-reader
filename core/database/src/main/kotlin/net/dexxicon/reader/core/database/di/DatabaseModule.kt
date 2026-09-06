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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DexxiconDatabase =
        Room.databaseBuilder(context, DexxiconDatabase::class.java, DexxiconDatabase.NAME)
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides
    fun provideServerDao(database: DexxiconDatabase): ServerDao = database.serverDao()

    @Provides
    fun provideReadingProgressDao(database: DexxiconDatabase): ReadingProgressDao =
        database.readingProgressDao()
}
