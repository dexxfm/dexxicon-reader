package net.dexxicon.reader.shared.di

import android.content.Context
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import net.dexxicon.reader.core.database.finish
import net.dexxicon.reader.core.database.getDatabaseBuilder
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.security.CryptoStore

/**
 * Android [actual]: wraps the plain [Context] the DB path and [CredentialStore]'s DataStore
 * need. A wrapper class rather than `actual typealias PlatformContext = Context` — a
 * typealias to Android's `Context` (itself an abstract class) makes `PlatformContext`
 * abstract too, which then conflicts with the commonMain `expect class PlatformContext`
 * (implicitly final) and, worse, wouldn't let the iOS actual stay a plain concrete class.
 */
actual class PlatformContext(val context: Context)

/**
 * A plain OkHttp engine (not `:app`'s Hilt-provided [okhttp3.OkHttpClient] with its cookie
 * jar/logging) — `:shared` has no access to `:app`'s Hilt graph, and Slice 1 doesn't need
 * cookie-based session persistence (see [AppContainer]'s doc comment on [NoAuthHeaderProvider][net.dexxicon.reader.core.network.NoAuthHeaderProvider]).
 */
actual fun createAppContainer(context: PlatformContext): AppContainer {
    val appContext = context.context.applicationContext
    val database = getDatabaseBuilder(appContext).finish(Dispatchers.IO)
    val credentialStore = CredentialStore(appContext, CryptoStore())
    return AppContainer(
        engine = OkHttp.create(),
        credentialStore = credentialStore,
        database = database,
    )
}
