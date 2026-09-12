package net.dexxicon.reader.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import net.dexxicon.reader.core.common.di.ApplicationScope
import net.dexxicon.reader.core.data.ProgressSeeder
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.auth.AuthHeaderProviderImpl
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.data.download.AndroidDownloadRepository
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.data.media.MediaLibraryContentSourceImpl
import net.dexxicon.reader.core.data.media.PlaybackProgressSinkImpl
import net.dexxicon.reader.core.database.dao.ServerDao
import net.dexxicon.reader.core.datastore.AppPreferencesStore
import net.dexxicon.reader.core.datastore.PlatformStorageContext
import net.dexxicon.reader.core.media.MediaLibraryContentSource
import net.dexxicon.reader.core.media.PlaybackProgressSink
import net.dexxicon.reader.core.network.AuthHeaderProvider
import net.dexxicon.reader.core.security.CredentialStore
import javax.inject.Singleton

/**
 * Lives in `:app` (rather than `:core:data`) because Hilt modules are only compiled where
 * the components are, and `:core:data` is now a Kotlin Multiplatform module — see
 * `NetworkModule`'s own doc comment for the full reasoning (same one applies here).
 *
 * Abstract class rather than interface (issue #74): `AuthHeaderProviderImpl` moved to
 * commonMain and, like every other Phase 1/2 commonMain class, dropped `@Inject`/`@Singleton`
 * — `javax.inject` doesn't exist on iOS, so `:shared` builds it with a plain constructor call
 * in `AppContainer` instead. Here on Android it needs an explicit `@Provides` (a concrete
 * function body, which `@Binds`-only interface modules can't hold) rather than constructor
 * injection — a `companion object` alongside the `@Binds` methods is the standard Dagger way
 * to mix both in one module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindAuthHeaderProvider(impl: AuthHeaderProviderImpl): AuthHeaderProvider

    @Binds
    @Singleton
    abstract fun bindPlaybackProgressSink(impl: PlaybackProgressSinkImpl): PlaybackProgressSink

    @Binds
    @Singleton
    abstract fun bindMediaLibraryContentSource(impl: MediaLibraryContentSourceImpl): MediaLibraryContentSource

    // ProgressSeeder (:core:data commonMain, issue #60) exists so OidcAuthenticator doesn't
    // need ReadingProgressRepository's full dependency graph (ProgressSyncModule's whole sync
    // stack) just to trigger this one thing after sign-in.
    @Binds
    @Singleton
    abstract fun bindProgressSeeder(impl: ReadingProgressRepository): ProgressSeeder

    // DownloadRepository (issue #126) is a commonMain interface now; AndroidDownloadRepository
    // is the real WorkManager-backed implementation, unchanged and still Hilt-constructible
    // (it never left androidMain) — this is the only binding it needs.
    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: AndroidDownloadRepository): DownloadRepository

    companion object {
        @Provides
        @Singleton
        fun provideAuthHeaderProviderImpl(
            serverDao: ServerDao,
            credentialStore: CredentialStore,
            tokenManager: TokenManager,
            @ApplicationScope scope: CoroutineScope,
        ): AuthHeaderProviderImpl =
            AuthHeaderProviderImpl(serverDao, credentialStore, tokenManager, scope)

        // AppPreferencesStore (issue #133) moved to commonMain the same way SyncStateStore
        // did in Stage B1 — @Inject/@Singleton dropped, so it needs an explicit @Provides here.
        @Provides
        @Singleton
        fun provideAppPreferencesStore(@ApplicationContext context: Context): AppPreferencesStore =
            AppPreferencesStore(PlatformStorageContext(context))
    }
}
