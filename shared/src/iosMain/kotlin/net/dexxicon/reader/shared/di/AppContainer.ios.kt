package net.dexxicon.reader.shared.di

import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.Dispatchers
import net.dexxicon.reader.core.database.finish
import net.dexxicon.reader.core.database.getDatabaseBuilder
import net.dexxicon.reader.core.security.CredentialStore

/** iOS [actual]: no platform handle is needed — [getDatabaseBuilder] resolves the app's own
 * Documents directory, and [CredentialStore]'s iOS actual takes no constructor args. */
actual class PlatformContext

/**
 * Uses [Dispatchers.Default] where the Android actual uses `Dispatchers.IO` — Kotlin/Native
 * has no equivalent of the JVM's large-pool blocking-IO dispatcher, and
 * `kotlinx.coroutines.Dispatchers.IO` itself is `internal` (not public API) on Native; a
 * core-sized `Default` pool is the standard KMP substitute (see [AppContainer]'s doc comment
 * on [io][AppContainer]).
 */
actual fun createAppContainer(context: PlatformContext): AppContainer {
    val database = getDatabaseBuilder().finish(Dispatchers.Default)
    val credentialStore = CredentialStore()
    return AppContainer(
        engine = Darwin.create(),
        credentialStore = credentialStore,
        database = database,
        io = Dispatchers.Default,
    )
}
