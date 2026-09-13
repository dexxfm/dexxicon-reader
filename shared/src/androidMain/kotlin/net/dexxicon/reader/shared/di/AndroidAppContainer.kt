package net.dexxicon.reader.shared.di

import android.content.Context
import net.dexxicon.reader.core.database.DexxiconDatabase
import okhttp3.OkHttpClient

/**
 * Process-lifetime [AppContainer] singleton for native `:app` (issue #126) — mirrors iOS's
 * `MainViewController.kt`'s own `private val appContainer: AppContainer by lazy {
 * createAppContainer(PlatformContext()) }` exactly; a thread-safe holder object is the
 * equivalent on Android, which has no single natural "this file IS the process-lifetime
 * singleton" entry point the way `MainViewController` is on iOS.
 *
 * Lives here (in `:shared`, not `:app`) so any Android module that already depends on
 * `:shared` — `feature:catalog`, for Book Detail — can reach it directly, without `:app`
 * needing to expose anything back down to a module it is itself the consumer of.
 *
 * [get] takes `:app`'s already-built Hilt [DexxiconDatabase] rather than building a second
 * one (issue #156): a separate `RoomDatabase` instance on the same file compiles and reads
 * fine, but its `InvalidationTracker` never sees writes made through the other instance, so
 * `DownloadWorker`'s progress updates (through `:app`'s Hilt-provided instance) never
 * reached this container's `Flow`s.
 *
 * [get] also takes `:app`'s Hilt-provided [OkHttpClient] (issue #161) for the same reason:
 * without it, this container's `TokenManager` — the one every real sign-in and every real API
 * call actually goes through — ran on a bare engine with no cookie jar, silently dropping
 * BookOrbit's session-refresh cookie, and `:app`'s own separate `TokenManager` (built by
 * `ServerAuthModule` from scratch) sat unused by any real traffic, watched only by the
 * background refresh worker/reauth banner — two independent in-memory session caches that
 * could each refresh (and, for Grimmory's single-use rotating refresh tokens, invalidate) the
 * other's copy. `ServerAuthModule.provideTokenManager` now returns *this* container's
 * `tokenManager` instead of building a second one, closing that gap.
 *
 * `SharedPreviewActivity` — the one caller with no Hilt graph to pull either from — calls
 * [createAppContainer] directly instead of going through this singleton, so it isn't affected
 * by (or a fix target for) either bug.
 */
object AndroidAppContainer {
    @Volatile
    private var instance: AppContainer? = null

    fun get(context: Context, database: DexxiconDatabase, okHttpClient: OkHttpClient): AppContainer =
        instance ?: synchronized(this) {
            instance ?: createAppContainer(PlatformContext(context.applicationContext), database, okHttpClient)
                .also { instance = it }
        }
}
