package net.dexxicon.reader.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineDispatcher
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.data.download.DownloadRepository
import net.dexxicon.reader.core.database.DexxiconDatabase
import net.dexxicon.reader.core.database.dao.BookmarkDao
import net.dexxicon.reader.core.database.dao.HighlightDao
import net.dexxicon.reader.core.database.dao.ReadingProgressDao
import net.dexxicon.reader.core.database.dao.ServerDao
import net.dexxicon.reader.core.network.AuthHeaderProvider
import net.dexxicon.reader.core.network.DexxiconHttpClient
import net.dexxicon.reader.core.network.createHttpClient
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.serverapi.user.NativeUserApi
import net.dexxicon.reader.shared.di.AndroidAppContainer
import okhttp3.OkHttpClient
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Provides the shared Ktor [HttpClient] every `:core:serverapi` API now uses (issues #52,
 * #56), plus [ServerRepository]. Both live in commonMain (`:core:serverapi`, `:core:data`)
 * and can't carry `@Inject` there — `javax.inject` isn't available on iOS — so, like
 * `NetworkModule`, this module supplies them explicitly instead of relying on constructor
 * injection. The other `:core:serverapi` APIs (browse/bookmark/kosync/annotation/progress/
 * user) are provided from [ServerApiModule] instead, which just injects this module's
 * [HttpClient].
 *
 * Phase 4 Stage J (issue #147) — `OidcApi`/`OidcClient`/`OidcAuthenticator`/`ServerProber`
 * (the sign-in-a-new-server orchestration) were removed from here: Stage G moved
 * Servers/Add-Edit Server entirely onto `:shared`'s own `AddServerState`, which builds its
 * own copy of this exact chain in `AppContainer` — nothing in `:app`'s Hilt graph has
 * constructor-injected any of the four since.
 *
 * issue #161 — `NativeAuthApi`/`NativeAuthClient` (the sign-in orchestration `TokenManager`
 * itself uses to talk to a server) are gone from here for the same reason `OidcApi` etc. are:
 * `AppContainer` already builds its own copy for every real sign-in and every real API call.
 * This module used to *also* build a second `TokenManager` from a second `NativeAuthClient`
 * — watched only by [SessionRefreshWorker][net.dexxicon.reader.core.data.auth.SessionRefreshWorker],
 * [SignInNotifier][net.dexxicon.reader.core.data.auth.SignInNotifier], and the in-app reauth
 * banner, never by any real traffic. Two independent in-memory session caches for the same
 * servers meant the background refresh worker's copy and the live copy could each rotate (and,
 * for Grimmory's single-use refresh tokens, invalidate) the other's — see [provideTokenManager].
 */
@Module
@InstallIn(SingletonComponent::class)
object ServerAuthModule {

    @Provides
    @Singleton
    fun provideServerHttpClient(
        @DexxiconHttpClient okHttpClient: OkHttpClient,
        // Provider<AuthHeaderProvider>, not the interface directly — AuthHeaderProviderImpl
        // depends on TokenManager (issue #161: AppContainer's own instance, see
        // provideTokenManager below), and AuthInterceptor (:core:network, OkHttp's equivalent
        // of this plugin) already defers the same way for its own copy — keep both deferred
        // so this client's construction order never depends on TokenManager's.
        authHeaderProvider: Provider<AuthHeaderProvider>,
    ): HttpClient {
        // Reuses the same OkHttpClient Retrofit and Readium already share — its cookie jar
        // (BookOrbit's HttpOnly refresh-token cookie) and logging interceptor apply here too,
        // with no separate wiring. Its AuthInterceptor is a no-op for every login/refresh/
        // oidc URL (same skip-list as Ktor's own auth plugin below), so nothing double-fires.
        val engine = OkHttp.create { preconfigured = okHttpClient }
        val deferred = object : AuthHeaderProvider {
            override fun authHeader(url: Url) = authHeaderProvider.get().authHeader(url)
            override fun refreshAuthHeader(url: Url) = authHeaderProvider.get().refreshAuthHeader(url)
        }
        return createHttpClient(engine, deferred)
    }

    /**
     * issue #161 — returns `:shared`'s own [net.dexxicon.reader.shared.di.AppContainer.tokenManager]
     * instead of building a second, independent [TokenManager] here. Every real sign-in
     * ([net.dexxicon.reader.core.data.auth.OidcAuthenticator]) and every real API call
     * ([net.dexxicon.reader.core.data.auth.AuthHeaderProviderImpl], via [DataModule]) already
     * goes through `AppContainer`'s copy exclusively (Stage G moved sign-in orchestration
     * there; nothing in this graph has constructor-injected a [TokenManager] built from scratch
     * since). The old second instance sat disconnected from all of that — seeded only by
     * [net.dexxicon.reader.core.data.auth.SessionRefreshWorker]'s periodic background refresh —
     * so its in-memory session cache and the live one could drift and each rotate (or, for
     * Grimmory's single-use refresh tokens, invalidate) the other's copy of the same server's
     * refresh token. Returning the same instance here means the worker, the reauth banner,
     * and every real request now all see one consistent, always-current session per server.
     */
    @Provides
    @Singleton
    fun provideTokenManager(
        @ApplicationContext context: Context,
        database: DexxiconDatabase,
        @DexxiconHttpClient okHttpClient: OkHttpClient,
    ): TokenManager = AndroidAppContainer.get(context, database, okHttpClient).tokenManager

    @Provides
    @Singleton
    fun provideServerRepository(
        serverDao: ServerDao,
        credentialStore: CredentialStore,
        tokenManager: TokenManager,
        nativeUserApi: NativeUserApi,
        @Dispatcher(DexxiconDispatcher.IO) io: CoroutineDispatcher,
        bookmarkDao: BookmarkDao,
        highlightDao: HighlightDao,
        readingProgressDao: ReadingProgressDao,
        downloadRepository: DownloadRepository,
    ): ServerRepository = ServerRepository(
        serverDao, credentialStore, tokenManager, nativeUserApi, io,
        bookmarkDao, highlightDao, readingProgressDao, downloadRepository,
    )
}
