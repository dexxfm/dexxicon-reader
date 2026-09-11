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
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.ProgressSeeder
import net.dexxicon.reader.core.data.ServerProber
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.auth.AuthHeaderProviderImpl
import net.dexxicon.reader.core.data.auth.OidcAuthenticator
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.data.catalog.BookOrbitCatalogSource
import net.dexxicon.reader.core.data.catalog.GrimmoryCatalogSource
import net.dexxicon.reader.core.data.catalog.OpdsCatalogSource
import net.dexxicon.reader.core.database.DexxiconDatabase
import net.dexxicon.reader.core.network.AuthHeaderProvider
import net.dexxicon.reader.core.network.createHttpClient
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.serverapi.auth.NativeAuthApi
import net.dexxicon.reader.core.serverapi.auth.NativeAuthClient
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import net.dexxicon.reader.core.serverapi.oidc.OidcApi
import net.dexxicon.reader.core.serverapi.oidc.OidcClient
import net.dexxicon.reader.core.serverapi.progress.NativeProgressApi
import net.dexxicon.reader.core.serverapi.user.NativeUserApi
import net.dexxicon.reader.shared.catalog.ReadingStatusActions

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
 * for one): [httpClient] is built against [deferredAuthHeaderProvider], which forwards to
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
 * [ProgressSeeder] gets a no-op stub here (Slice 2, issue #70) — its one real implementation,
 * `ReadingProgressRepository`, stays androidMain-only (blocked by `KoSyncRepository`'s real
 * Android dependencies, same as ever). `OidcAuthenticator` needs *some* `ProgressSeeder` to
 * construct, and Slice 2 doesn't need the "seed continue-reading rows right after sign-in"
 * behavior to work for SSO sign-in itself to work — just a real gap to close before shared UI
 * needs actual continue-reading data.
 *
 * [imageLoader] (issue #78) is Coil 3's [ImageLoader], built against this same [httpClient]
 * via [KtorNetworkFetcherFactory] rather than a second, unauthenticated client — cover images
 * on private catalogs need the same `Authorization` header everything else does. Needs one
 * more platform-supplied value, [coilPlatformContext] (Coil's own `PlatformContext` — aliased
 * to [CoilPlatformContext] on the import here to avoid colliding with this file's own
 * [PlatformContext]): Android's is `android.content.Context` itself (a typealias), iOS's is
 * `coil3.PlatformContext.INSTANCE`, a singleton with nothing to configure.
 *
 * [readingStatusActions] (issue #84) is deliberately narrower than the native app's
 * `BookActions` — see [ReadingStatusActions]'s own doc comment for why. Built with [scope],
 * not a screen's own `rememberCoroutineScope()`, so a status push outlives the screen that
 * started it.
 */
class AppContainer(
    engine: HttpClientEngine,
    credentialStore: CredentialStore,
    database: DexxiconDatabase,
    io: CoroutineDispatcher,
    coilPlatformContext: CoilPlatformContext,
) {
    /** Process-lifetime scope for [AuthHeaderProviderImpl]'s server-list collector — mirrors
     * `:app`'s `@ApplicationScope` (`CoroutineScope(SupervisorJob() + Dispatchers.Default)`)
     * closely enough for this one purpose without needing a second platform-supplied
     * dispatcher; [io] already differs correctly per platform (see this class's doc comment). */
    private val scope = CoroutineScope(SupervisorJob() + io)

    private lateinit var realAuthHeaderProvider: AuthHeaderProvider
    private val deferredAuthHeaderProvider = object : AuthHeaderProvider {
        override fun authHeader(url: Url) = realAuthHeaderProvider.authHeader(url)
        override fun refreshAuthHeader(url: Url) = realAuthHeaderProvider.refreshAuthHeader(url)
    }
    private val httpClient: HttpClient = createHttpClient(engine, deferredAuthHeaderProvider)

    private val nativeAuthApi = NativeAuthApi(httpClient)
    private val nativeAuthClient = NativeAuthClient(nativeAuthApi)
    private val nativeUserApi = NativeUserApi(httpClient)
    private val oidcApi = OidcApi(httpClient)
    private val oidcClient = OidcClient(oidcApi)
    private val bookOrbitBrowseApi = BookOrbitBrowseApi(httpClient)
    private val grimmoryBrowseApi = GrimmoryBrowseApi(httpClient)
    private val nativeProgressApi = NativeProgressApi(httpClient)

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
    val oidcAuthenticator: OidcAuthenticator = OidcAuthenticator(
        oidcClient = oidcClient,
        serverRepository = serverRepository,
        progressSeeder = NoOpProgressSeeder,
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
    val readingStatusActions: ReadingStatusActions = ReadingStatusActions(
        api = nativeProgressApi,
        serverRepository = serverRepository,
        scope = scope,
    )

    init {
        realAuthHeaderProvider =
            AuthHeaderProviderImpl(database.serverDao(), credentialStore, tokenManager, scope)
    }
}

private object NoOpProgressSeeder : ProgressSeeder {
    override fun seedFromServerAsync(serverId: String) = Unit
}

/** Opaque per-platform handle [createAppContainer] needs — an `android.content.Context` on
 * Android, nothing on iOS (there's no equivalent object to thread through). */
expect class PlatformContext

expect fun createAppContainer(context: PlatformContext): AppContainer
