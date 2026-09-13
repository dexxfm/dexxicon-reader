package net.dexxicon.reader.shared.di

import android.content.Context
import net.dexxicon.reader.core.database.DexxiconDatabase

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
 * reached this container's `Flow`s. `SharedPreviewActivity` — the one caller with no Hilt
 * graph to pull a database from — calls [createAppContainer] directly instead of going
 * through this singleton, so it isn't affected by (or a fix target for) that bug.
 */
object AndroidAppContainer {
    @Volatile
    private var instance: AppContainer? = null

    fun get(context: Context, database: DexxiconDatabase): AppContainer =
        instance ?: synchronized(this) {
            instance ?: createAppContainer(PlatformContext(context.applicationContext), database)
                .also { instance = it }
        }
}
