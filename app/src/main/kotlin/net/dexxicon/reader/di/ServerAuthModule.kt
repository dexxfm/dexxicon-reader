package net.dexxicon.reader.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineDispatcher
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.data.ServerProber
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.network.AuthHeaderProvider
import net.dexxicon.reader.core.network.DexxiconHttpClient
import net.dexxicon.reader.core.network.createHttpClient
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.serverapi.auth.NativeAuthApi
import net.dexxicon.reader.core.serverapi.auth.NativeAuthClient
import net.dexxicon.reader.core.serverapi.oidc.OidcApi
import net.dexxicon.reader.core.serverapi.oidc.OidcClient
import okhttp3.OkHttpClient
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Provides the sign-in path's Ktor client + API/orchestration classes (issues #52, #54).
 * `NativeAuthApi`/`NativeAuthClient`/`OidcApi`/`OidcClient`/`TokenManager`/`ServerProber`
 * live in commonMain (`:core:serverapi`, `:core:data`) and can't carry `@Inject` there —
 * `javax.inject` isn't available on iOS — so, like `NetworkModule`, this module supplies
 * them explicitly instead of relying on constructor injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServerAuthModule {

    @Provides
    @Singleton
    fun provideAuthHttpClient(
        @DexxiconHttpClient okHttpClient: OkHttpClient,
        // Provider<AuthHeaderProvider>, not the interface directly — AuthHeaderProviderImpl
        // depends on TokenManager, which depends (via NativeAuthClient) on this very client,
        // so a direct AuthHeaderProvider edge here is a real Dagger cycle. AuthInterceptor
        // (:core:network, OkHttp's own equivalent of this plugin) hits the identical
        // situation and breaks it the same way — defer resolution until a request is
        // actually sent, by which point the graph has long since finished constructing.
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

    @Provides
    @Singleton
    fun provideNativeAuthApi(client: HttpClient): NativeAuthApi = NativeAuthApi(client)

    @Provides
    @Singleton
    fun provideNativeAuthClient(api: NativeAuthApi): NativeAuthClient = NativeAuthClient(api)

    @Provides
    @Singleton
    fun provideOidcApi(client: HttpClient): OidcApi = OidcApi(client)

    @Provides
    @Singleton
    fun provideOidcClient(api: OidcApi): OidcClient = OidcClient(api)

    @Provides
    @Singleton
    fun provideTokenManager(
        authClient: NativeAuthClient,
        credentialStore: CredentialStore,
    ): TokenManager = TokenManager(authClient, credentialStore)

    @Provides
    @Singleton
    fun provideServerProber(
        authClient: NativeAuthClient,
        // ServerProber's commonMain constructor takes a plain CoroutineDispatcher — the
        // @Dispatcher qualifier is javax.inject-based (Hilt/androidMain-only) and can't live
        // on a commonMain constructor, so the qualified binding is resolved here instead.
        @Dispatcher(DexxiconDispatcher.IO) io: CoroutineDispatcher,
    ): ServerProber = ServerProber(authClient, io)
}
