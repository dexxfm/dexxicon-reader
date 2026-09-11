package net.dexxicon.reader.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import net.dexxicon.reader.core.common.di.ApplicationScope
import net.dexxicon.reader.core.data.ProgressSeeder
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.auth.AuthHeaderProviderImpl
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.data.media.MediaLibraryContentSourceImpl
import net.dexxicon.reader.core.data.media.PlaybackProgressSinkImpl
import net.dexxicon.reader.core.database.dao.ServerDao
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
    // need ReadingProgressRepository's full Android-specific dependency graph (KoSyncRepository)
    // just to trigger this one thing after sign-in.
    @Binds
    @Singleton
    abstract fun bindProgressSeeder(impl: ReadingProgressRepository): ProgressSeeder

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
    }
}
