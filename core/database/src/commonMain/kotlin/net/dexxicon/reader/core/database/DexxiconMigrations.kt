package net.dexxicon.reader.core.database

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The app's full migration history, ported from `SupportSQLiteDatabase` (Android-only) to
 * `SQLiteConnection` (commonMain) when `:core:database` went Kotlin Multiplatform (issue
 * #58). Every statement is unchanged from the original Android-only migrations — only the
 * receiver type and `db.execSQL(...)` → `connection.execSQL(...)` changed.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
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

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
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

internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `servers` ADD COLUMN `koSyncUrl` TEXT")
        connection.execSQL("ALTER TABLE `servers` ADD COLUMN `koSyncUsername` TEXT")
    }
}

internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
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
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_serverId_bookId` ON `highlights` (`serverId`, `bookId`)")
    }
}

internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `reading_progress` ADD COLUMN `title` TEXT")
        connection.execSQL("ALTER TABLE `reading_progress` ADD COLUMN `author` TEXT")
        connection.execSQL("ALTER TABLE `reading_progress` ADD COLUMN `coverUrl` TEXT")
        connection.execSQL("ALTER TABLE `reading_progress` ADD COLUMN `format` TEXT")
        connection.execSQL("ALTER TABLE `reading_progress` ADD COLUMN `digestUrl` TEXT")
    }
}

internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `downloads` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `servers` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
        // Seed the priority from the existing add order so nothing visibly reshuffles.
        connection.execSQL(
            """
            UPDATE `servers` SET `sortOrder` =
                (SELECT COUNT(*) FROM `servers` AS s2 WHERE s2.`createdAt` < `servers`.`createdAt`)
            """.trimIndent(),
        )
    }
}

internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(connection: SQLiteConnection) {
        // Brand-new table — never carries data across an upgrade, so recreate cleanly.
        connection.execSQL("DROP TABLE IF EXISTS `bookmarks`")
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `bookmarks` (
                `id` TEXT NOT NULL,
                `serverId` TEXT NOT NULL,
                `bookId` TEXT NOT NULL,
                `locatorJson` TEXT NOT NULL,
                `progression` REAL NOT NULL,
                `title` TEXT NOT NULL,
                `foreignCfi` TEXT,
                `createdAt` INTEGER NOT NULL,
                `remoteId` TEXT,
                `dirty` INTEGER NOT NULL,
                `deleted` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_serverId_bookId` ON `bookmarks` (`serverId`, `bookId`)")
    }
}

internal val ALL_MIGRATIONS = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
)

/** Finishes a platform [getDatabaseBuilder]'s builder — driver, migrations, dispatcher. */
fun RoomDatabase.Builder<DexxiconDatabase>.finish(
    queryDispatcher: CoroutineDispatcher,
): DexxiconDatabase = setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(queryDispatcher)
    .addMigrations(*ALL_MIGRATIONS)
    .build()
