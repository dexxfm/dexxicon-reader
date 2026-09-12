package net.dexxicon.reader.di

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.common.di.ApplicationScope
import net.dexxicon.reader.core.data.BookActions
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.data.sync.KoSyncRepository
import net.dexxicon.reader.core.data.sync.LibrarySeeder
import net.dexxicon.reader.core.data.sync.NativeProgressSync
import net.dexxicon.reader.core.database.dao.ReadingProgressDao
import net.dexxicon.reader.core.datastore.PlatformStorageContext
import net.dexxicon.reader.core.datastore.SyncStateStore
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import net.dexxicon.reader.core.serverapi.kosync.KoSyncApi
import net.dexxicon.reader.core.serverapi.progress.NativeProgressApi
import javax.inject.Singleton

/**
 * Lives in `:app` (rather than `:core:data`/`:core:datastore`) — same reason as every other
 * Phase 1/2/4 module: the Hilt Gradle plugin refuses to apply to a KMP module, and these
 * classes (issue #126) dropped `@Inject`/`@Singleton` when they moved to commonMain
 * (`javax.inject` doesn't exist on iOS), so they need explicit `@Provides` here instead of
 * constructor injection — `:shared`'s `AppContainer` builds the identical graph by hand for
 * the same reason.
 *
 * [KoSyncRepository]'s raw device id/model are resolved here directly (`Settings.Secure`/
 * `Build.MODEL`) rather than behind an expect/actual in `:core:data` — both are one-time
 * values this composition root already has everything needed to compute, so there's no reason
 * to route a `Context` through commonMain just to get them (`:shared`'s `AppContainer.android.kt`
 * does the equivalent on its own side).
 */
@Module
@InstallIn(SingletonComponent::class)
object ProgressSyncModule {

    @Provides
    @Singleton
    fun provideSyncStateStore(@ApplicationContext context: Context): SyncStateStore =
        SyncStateStore(PlatformStorageContext(context))

    @Provides
    @Singleton
    fun provideNativeProgressSync(
        api: NativeProgressApi,
        syncStateStore: SyncStateStore,
        @Dispatcher(DexxiconDispatcher.IO) io: CoroutineDispatcher,
    ): NativeProgressSync = NativeProgressSync(api, syncStateStore, io)

    @Provides
    @Singleton
    fun provideLibrarySeeder(
        grimmory: GrimmoryBrowseApi,
        bookOrbit: BookOrbitBrowseApi,
        @Dispatcher(DexxiconDispatcher.IO) io: CoroutineDispatcher,
    ): LibrarySeeder = LibrarySeeder(grimmory, bookOrbit, io)

    @Provides
    @Singleton
    fun provideKoSyncRepository(
        api: KoSyncApi,
        credentialStore: CredentialStore,
        syncStateStore: SyncStateStore,
        client: HttpClient,
        @Dispatcher(DexxiconDispatcher.IO) io: CoroutineDispatcher,
        @ApplicationContext context: Context,
    ): KoSyncRepository {
        @SuppressLint("HardwareIds")
        val rawDeviceId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "dexxicon"
        return KoSyncRepository(
            api = api,
            credentialStore = credentialStore,
            syncStateStore = syncStateStore,
            httpClient = client,
            io = io,
            rawDeviceId = rawDeviceId,
            deviceModel = Build.MODEL ?: "Android",
        )
    }

    @Provides
    @Singleton
    fun provideReadingProgressRepository(
        dao: ReadingProgressDao,
        koSync: KoSyncRepository,
        nativeSync: NativeProgressSync,
        librarySeeder: LibrarySeeder,
        serverRepository: ServerRepository,
        tokenManager: TokenManager,
        downloadRepository: DownloadRepository,
        @ApplicationScope appScope: CoroutineScope,
        @Dispatcher(DexxiconDispatcher.IO) io: CoroutineDispatcher,
    ): ReadingProgressRepository = ReadingProgressRepository(
        dao = dao,
        koSync = koSync,
        nativeSync = nativeSync,
        librarySeeder = librarySeeder,
        serverRepository = serverRepository,
        tokenManager = tokenManager,
        downloadRepository = downloadRepository,
        appScope = appScope,
        io = io,
    )

    @Provides
    @Singleton
    fun provideBookActions(
        catalogRepository: CatalogRepository,
        downloadRepository: DownloadRepository,
        progressRepository: ReadingProgressRepository,
        serverRepository: ServerRepository,
        nativeProgressSync: NativeProgressSync,
        @ApplicationScope scope: CoroutineScope,
    ): BookActions = BookActions(
        catalogRepository = catalogRepository,
        downloadRepository = downloadRepository,
        progressRepository = progressRepository,
        serverRepository = serverRepository,
        nativeProgressSync = nativeProgressSync,
        scope = scope,
    )
}
