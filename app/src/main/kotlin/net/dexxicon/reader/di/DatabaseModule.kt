package net.dexxicon.reader.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.database.DexxiconDatabase
import net.dexxicon.reader.core.database.dao.BookmarkDao
import net.dexxicon.reader.core.database.dao.DownloadDao
import net.dexxicon.reader.core.database.dao.HighlightDao
import net.dexxicon.reader.core.database.dao.ReadingProgressDao
import net.dexxicon.reader.core.database.dao.ServerDao
import net.dexxicon.reader.core.database.finish
import net.dexxicon.reader.core.database.getDatabaseBuilder
import javax.inject.Singleton

/**
 * Lives in `:app` (rather than `:core:database`) because Hilt modules are only compiled
 * where the components are, and `:core:database` is now a Kotlin Multiplatform module — see
 * `NetworkModule`'s own doc comment for the full reasoning (same one applies here). The
 * migrations themselves stay in `:core:database`'s commonMain (`DexxiconMigrations.kt`) —
 * this module only builds the platform-specific builder and finishes it.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        @Dispatcher(DexxiconDispatcher.IO) io: CoroutineDispatcher,
    ): DexxiconDatabase = getDatabaseBuilder(context).finish(io)

    @Provides
    fun provideServerDao(database: DexxiconDatabase): ServerDao = database.serverDao()

    @Provides
    fun provideReadingProgressDao(database: DexxiconDatabase): ReadingProgressDao =
        database.readingProgressDao()

    @Provides
    fun provideDownloadDao(database: DexxiconDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun provideHighlightDao(database: DexxiconDatabase): HighlightDao = database.highlightDao()

    @Provides
    fun provideBookmarkDao(database: DexxiconDatabase): BookmarkDao = database.bookmarkDao()
}
