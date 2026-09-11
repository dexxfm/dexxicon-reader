package net.dexxicon.reader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import net.dexxicon.reader.core.common.crash.CrashReporter
import net.dexxicon.reader.core.data.auth.SessionRefreshWorker
import net.dexxicon.reader.core.data.auth.SignInNotifier
import net.dexxicon.reader.core.network.DexxiconHttpClient
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import javax.inject.Inject
import kotlin.concurrent.thread

@HiltAndroidApp
class DexxiconApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    @DexxiconHttpClient
    lateinit var okHttpClient: Lazy<OkHttpClient>

    /** Injected eagerly so it starts watching for expired OIDC sessions from launch. */
    @Inject
    lateinit var signInNotifier: SignInNotifier

    @Inject
    lateinit var crashReporter: CrashReporter

    override fun onCreate() {
        super.onCreate()
        crashReporter.install()
        // WorkManager.getInstance() + a periodic enqueue does disk I/O; keep it off the
        // startup path — the 6-hour cadence doesn't care about a few ms of delay.
        thread(name = "session-refresh-schedule", isDaemon = true) {
            SessionRefreshWorker.schedule(this)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient.get() }))
            }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
