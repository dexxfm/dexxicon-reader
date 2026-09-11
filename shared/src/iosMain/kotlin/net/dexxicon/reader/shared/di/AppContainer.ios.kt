package net.dexxicon.reader.shared.di

import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.Dispatchers
import net.dexxicon.reader.core.database.finish
import net.dexxicon.reader.core.database.getDatabaseBuilder
import net.dexxicon.reader.core.security.CredentialStore

/** iOS [actual]: no platform handle is needed — [getDatabaseBuilder] resolves the app's own
 * Documents directory, and [CredentialStore]'s iOS actual takes no constructor args. */
actual class PlatformContext

actual fun createAppContainer(context: PlatformContext): AppContainer {
    val database = getDatabaseBuilder().finish(Dispatchers.IO)
    val credentialStore = CredentialStore()
    return AppContainer(
        engine = Darwin.create(),
        credentialStore = credentialStore,
        database = database,
    )
}
