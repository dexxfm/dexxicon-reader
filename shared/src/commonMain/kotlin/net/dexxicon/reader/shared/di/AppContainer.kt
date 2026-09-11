package net.dexxicon.reader.shared.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.dexxicon.reader.core.data.ServerProber
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.auth.TokenManager
import net.dexxicon.reader.core.database.DexxiconDatabase
import net.dexxicon.reader.core.network.NoAuthHeaderProvider
import net.dexxicon.reader.core.network.createHttpClient
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.serverapi.auth.NativeAuthApi
import net.dexxicon.reader.core.serverapi.auth.NativeAuthClient
import net.dexxicon.reader.core.serverapi.user.NativeUserApi

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
 * The shared [HttpClient] uses [NoAuthHeaderProvider]: Slice 1 (issue #62) only exercises the
 * sign-in path (`ServerProber.probe()`, a login call Ktor's auth plugin already skips) and a
 * local DB write, neither of which needs a resolved `Authorization` header. Real per-server
 * auth headers need `AuthHeaderProviderImpl` (`:core:data`, androidMain-only today) ported to
 * commonMain — left for a follow-up once shared UI makes an authenticated call (browse,
 * progress sync, etc).
 */
class AppContainer(
    engine: HttpClientEngine,
    credentialStore: CredentialStore,
    database: DexxiconDatabase,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val httpClient: HttpClient = createHttpClient(engine, NoAuthHeaderProvider)

    private val nativeAuthApi = NativeAuthApi(httpClient)
    private val nativeAuthClient = NativeAuthClient(nativeAuthApi)
    private val nativeUserApi = NativeUserApi(httpClient)

    val tokenManager: TokenManager = TokenManager(nativeAuthClient, credentialStore)
    val serverProber: ServerProber = ServerProber(nativeAuthClient, io)
    val serverRepository: ServerRepository = ServerRepository(
        serverDao = database.serverDao(),
        credentialStore = credentialStore,
        tokenManager = tokenManager,
        nativeUserApi = nativeUserApi,
        io = io,
    )
}

/** Opaque per-platform handle [createAppContainer] needs — an `android.content.Context` on
 * Android, nothing on iOS (there's no equivalent object to thread through). */
expect class PlatformContext

expect fun createAppContainer(context: PlatformContext): AppContainer
