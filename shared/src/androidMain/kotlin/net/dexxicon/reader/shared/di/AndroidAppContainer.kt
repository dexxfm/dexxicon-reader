package net.dexxicon.reader.shared.di

import android.content.Context

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
 * `SharedPreviewActivity` already proves a second, independent `AppContainer` built the same
 * way is safe to run alongside native `:app`'s Hilt-based screens, reading/writing the same
 * SQLite file and credential store — this is that exact pattern, just promoted from a
 * debug-only preview to `:app`'s own Book Detail screen.
 */
object AndroidAppContainer {
    @Volatile
    private var instance: AppContainer? = null

    fun get(context: Context): AppContainer =
        instance ?: synchronized(this) {
            instance ?: createAppContainer(PlatformContext(context.applicationContext)).also { instance = it }
        }
}
