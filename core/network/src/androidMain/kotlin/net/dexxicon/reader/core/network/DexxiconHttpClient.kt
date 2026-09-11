package net.dexxicon.reader.core.network

import javax.inject.Qualifier

/**
 * The shared client: auth + logging, generous timeouts for large downloads/streams. Kept
 * here (not alongside the Hilt `@Module` that provides it, now in `:app` — see
 * `NetworkModule`'s own doc comment) so the many modules that `@Inject` it don't need a
 * dependency on `:app`, which would be circular. Android-only, like Hilt/Dagger itself —
 * `javax.inject` isn't available on the iOS targets.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DexxiconHttpClient
