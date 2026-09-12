package net.dexxicon.reader.shared.di

import coil3.ImageLoader
import coil3.PlatformContext as CoilPlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import net.dexxicon.reader.core.data.BookActions
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.ProgressSeeder
import net.dexxicon.reader.core.data.ReadingProgressRepository
import net.dexxicon.reader.core.data.ServerProber
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.auth.AuthHeaderProviderImpl
import net.dexxicon.reader.core.data.auth.OidcAuthenticator
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.data.catalog.BookOrbitCatalogSource
import net.dexxicon.reader.core.data.catalog.GrimmoryCatalogSource
import net.dexxicon.reader.core.data.catalog.OpdsCatalogSource
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.data.sync.KoSyncRepository
import net.dexxicon.reader.core.data.sync.LibrarySeeder
import net.dexxicon.reader.core.data.sync.NativeProgressSync
import net.dexxicon.reader.core.database.DexxiconDatabase
import net.dexxicon.reader.core.datastore.AppPreferencesStore
import net.dexxicon.reader.core.datastore.SyncStateStore
import net.dexxicon.reader.core.network.AuthHeaderProvider
import net.dexxicon.reader.core.network.createHttpClient
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.serverapi.auth.NativeAuthApi
import net.dexxicon.reader.core.serverapi.auth.NativeAuthClient
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import net.dexxicon.reader.core.serverapi.kosync.KoSyncApi
import net.dexxicon.reader.core.serverapi.oidc.OidcApi
import net.dexxicon.reader.core.serverapi.oidc.OidcClient
import net.dexxicon.reader.core.serverapi.progress.NativeProgressApi
import net.dexxicon.reader.core.serverapi.user.NativeUserApi
import net.dexxicon.reader.shared.reader.AudiobookProgressSync

/**
 * Manual (non-Hilt) composition root for `:shared`'s commonMain UI. Every `:core:*` module
 * Phase 1 ported to KMP has the same constraint documented on its own `di` module in `:app`
 * (`ServerAuthModule`, `DatabaseModule`, …): the Hilt Gradle plugin refuses to apply to a
 * Kotlin Multiplatform module at all, and `javax.inject` doesn't exist on iOS to begin with —
 * so `:shared` can't reuse `:app`'s Hilt graph and wires its own, smaller one here instead.
 * [createAppContainer] (expect, one `actual` per platform) supplies the handful of pieces
 * that genuinely differ per platform — the Ktor engine, [CredentialStore], and the Room
 * database builder; everything else is identical and lives in this constructor.
 *
 * The shared [HttpClient] uses the real [AuthHeaderProviderImpl] (issue #74 — commonMain since
 * this class; Slice 1/2 used [net.dexxicon.reader.core.network.NoAuthHeaderProvider], since
 * login and SSO discovery are the only calls that don't need a resolved `Authorization`
 * header). Building it hits the same real Dagger-shaped cycle `:app`'s `ServerAuthModule`
 * already solves for the same reason — `AuthHeaderProviderImpl` needs [TokenManager], which
 * needs [NativeAuthClient], which needs this very [httpClient] — broken here with a plain
 * deferred adapter object instead of Dagger's `Provider<T>` (there's no DI container to ask
 * for one): [httpClient] is built against [authHeaderProvider], which forwards to
 * [realAuthHeaderProvider] — set once, after every other `val` below has finished
 * constructing — rather than against the real implementation directly.
 *
 * [io] has no commonMain default (unlike `:app`'s Hilt providers, which can default to
 * `@Dispatcher(IO)`) — `kotlinx.coroutines.Dispatchers.IO` is `internal` on Kotlin/Native
 * (public on Android/JVM only), so a `= Dispatchers.IO` default living in commonMain code
 * fails to compile for the iOS target. Each platform's `createAppContainer` actual supplies
 * its own: `Dispatchers.IO` on Android, `Dispatchers.Default` on iOS (no Native equivalent
 * of the JVM's large-pool blocking-IO dispatcher; `Default`'s core-sized pool is the
 * standard KMP substitute here).
 *
 * [ProgressSeeder] is [progressRepository] itself (Phase 4 restructure, issue #126) —
 * `ReadingProgressRepository` moved to commonMain once `KoSyncRepository`/`DownloadRepository`
 * did, closing the gap the Slice 2 (issue #70) doc comment used to describe here (a
 * `NoOpProgressSeeder` stub, since removed). `OidcAuthenticator` now gets the real thing, so
 * signing in a new server actually seeds its "continue reading" rows on `:shared` too.
 *
 * [imageLoader] (issue #78) is Coil 3's [ImageLoader], built against this same [httpClient]
 * via [KtorNetworkFetcherFactory] rather than a second, unauthenticated client — cover images
 * on private catalogs need the same `Authorization` header everything else does. Needs one
 * more platform-supplied value, [coilPlatformContext] (Coil's own `PlatformContext` — aliased
 * to [CoilPlatformContext] on the import here to avoid colliding with this file's own
 * [PlatformContext]): Android's is `android.content.Context` itself (a typealias), iOS's is
 * `coil3.PlatformContext.INSTANCE`, a singleton with nothing to configure.
 *
 * [bookActions] (issue #84, superseding the narrower `ReadingStatusActions` this class used to
 * expose before issue #126 made the real `BookActions` commonMain) is built with [scope], not
 * a screen's own `rememberCoroutineScope()`, so a status push outlives the screen that started
 * it.
 *
 * [audiobookProgressSync] (issue #114) is iOS's native audiobook player's path to resume
 * position + local/remote progress sync — same [scope]-outlives-the-screen reasoning as
 * [bookActions], and the same "deliberately narrower than the full native-app class" shape;
 * see [AudiobookProgressSync]'s own doc comment for exactly what it reuses vs. reimplements.
 *
 * [downloadRepository] and [koSyncRawDeviceId]/[koSyncDeviceModel] (issue #126) are the three
 * remaining genuinely platform-specific pieces `createAppContainer` supplies: the real
 * `WorkManager`-backed implementation on Android vs. an honest "not supported yet" stub on
 * iOS, and the one-time device id/model each platform resolves with its own native API
 * (`Settings.Secure`/`Build.MODEL` vs. `UIDevice`) — see [DownloadRepository]'s and
 * [KoSyncRepository]'s own doc comments. Everything else `:core:data`'s progress-sync stack
 * needs ([SyncStateStore], [NativeProgressSync], [LibrarySeeder], [KoSyncRepository],
 * [progressRepository], [bookActions]) is built here from those three plus what this class
 * already has.
 *
 * [appPreferences] (issue #133) is likewise platform-supplied — Android's `createAppContainer`
 * reuses the same instance [downloadRepository] already needed one of; iOS builds a fresh one.
 * See [AppPreferencesStore]'s own doc comment for why constructing more than once is safe.
 */
class AppContainer(
    engine: HttpClientEngine,
    credentialStore: CredentialStore,
    database: DexxiconDatabase,
    io: CoroutineDispatcher,
    coilPlatformContext: CoilPlatformContext,
    val downloadRepository: DownloadRepository,
    syncStateStore: SyncStateStore,
    koSyncRawDeviceId: String,
    koSyncDeviceModel: String,
    /** Phase 4 Stage D (issue #133) — Library's view-mode toggle and cover-tap-action need
     * this directly, the same way [progressRepository]/[bookActions] are exposed publicly. */
    val appPreferences: AppPreferencesStore,
    /** Phase 4 Stage E1 (issue #136) — Settings' About row. Resolved once per platform, same
     * "plain value, no expect/actual needed" shape as [koSyncRawDeviceId]/[koSyncDeviceModel]:
     * Android reads it via `PackageManager` (the same call
     * [net.dexxicon.reader.core.common.crash.CrashReporter.deviceBlock] already makes for the
     * same value), iOS via `NSBundle.mainBundle`'s `CFBundleShortVersionString`. */
    val appVersionName: String,
) {
    /** Process-lifetime scope for [AuthHeaderProviderImpl]'s server-list collector — mirrors
     * `:app`'s `@ApplicationScope` (`CoroutineScope(SupervisorJob() + Dispatchers.Default)`)
     * closely enough for this one purpose without needing a second platform-supplied
     * dispatcher; [io] already differs correctly per platform (see this class's doc comment). */
    private val scope = CoroutineScope(SupervisorJob() + io)

    private lateinit var realAuthHeaderProvider: AuthHeaderProvider

    /** Forwards to [realAuthHeaderProvider] once it exists (see this class's doc comment for
     * why the cycle needs this indirection). Exposed publicly (issue #99) — the reader-launch
     * hand-off needs a fresh `Authorization` header for a book's acquisition URL, and this is
     * the same provider [httpClient] itself uses, so the header always matches what a real
     * network call would have sent. */
    val authHeaderProvider: AuthHeaderProvider = object : AuthHeaderProvider {
        override fun authHeader(url: Url) = realAuthHeaderProvider.authHeader(url)
        override fun refreshAuthHeader(url: Url) = realAuthHeaderProvider.refreshAuthHeader(url)
    }
    private val httpClient: HttpClient = createHttpClient(engine, authHeaderProvider)

    private val nativeAuthApi = NativeAuthApi(httpClient)
    private val nativeAuthClient = NativeAuthClient(nativeAuthApi)
    private val nativeUserApi = NativeUserApi(httpClient)
    private val oidcApi = OidcApi(httpClient)
    private val oidcClient = OidcClient(oidcApi)
    private val bookOrbitBrowseApi = BookOrbitBrowseApi(httpClient)
    private val grimmoryBrowseApi = GrimmoryBrowseApi(httpClient)
    private val nativeProgressApi = NativeProgressApi(httpClient)
    private val koSyncApi = KoSyncApi(httpClient)

    @OptIn(ExperimentalCoilApi::class)
    val imageLoader: ImageLoader = ImageLoader.Builder(coilPlatformContext)
        .components { add(KtorNetworkFetcherFactory(httpClient = httpClient)) }
        .build()

    val tokenManager: TokenManager = TokenManager(nativeAuthClient, credentialStore)
    val serverProber: ServerProber = ServerProber(nativeAuthClient, io)
    val serverRepository: ServerRepository = ServerRepository(
        serverDao = database.serverDao(),
        credentialStore = credentialStore,
        tokenManager = tokenManager,
        nativeUserApi = nativeUserApi,
        io = io,
    )

    private val nativeProgressSync = NativeProgressSync(nativeProgressApi, syncStateStore, io)
    private val librarySeeder = LibrarySeeder(grimmoryBrowseApi, bookOrbitBrowseApi, io)
    private val koSyncRepository = KoSyncRepository(
        api = koSyncApi,
        credentialStore = credentialStore,
        syncStateStore = syncStateStore,
        httpClient = httpClient,
        io = io,
        rawDeviceId = koSyncRawDeviceId,
        deviceModel = koSyncDeviceModel,
    )
    val progressRepository: ReadingProgressRepository = ReadingProgressRepository(
        dao = database.readingProgressDao(),
        koSync = koSyncRepository,
        nativeSync = nativeProgressSync,
        librarySeeder = librarySeeder,
        serverRepository = serverRepository,
        tokenManager = tokenManager,
        downloadRepository = downloadRepository,
        appScope = scope,
        io = io,
    )

    val oidcAuthenticator: OidcAuthenticator = OidcAuthenticator(
        oidcClient = oidcClient,
        serverRepository = serverRepository,
        progressSeeder = progressRepository,
        tokenManager = tokenManager,
        io = io,
    )
    val catalogRepository: CatalogRepository = CatalogRepository(
        serverRepository = serverRepository,
        grimmorySource = GrimmoryCatalogSource(grimmoryBrowseApi),
        bookOrbitSource = BookOrbitCatalogSource(bookOrbitBrowseApi),
        opdsSource = OpdsCatalogSource(),
        io = io,
    )
    val bookActions: BookActions = BookActions(
        catalogRepository = catalogRepository,
        downloadRepository = downloadRepository,
        progressRepository = progressRepository,
        serverRepository = serverRepository,
        nativeProgressSync = nativeProgressSync,
        scope = scope,
    )
    val audiobookProgressSync: AudiobookProgressSync = AudiobookProgressSync(
        api = nativeProgressApi,
        serverRepository = serverRepository,
        progressDao = database.readingProgressDao(),
        scope = scope,
    )

    init {
        realAuthHeaderProvider =
            AuthHeaderProviderImpl(database.serverDao(), credentialStore, tokenManager, scope)
    }
}

/** Opaque per-platform handle [createAppContainer] needs — an `android.content.Context` on
 * Android, nothing on iOS (there's no equivalent object to thread through). */
expect class PlatformContext

expect fun createAppContainer(context: PlatformContext): AppContainer
