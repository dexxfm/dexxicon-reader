package net.dexxicon.reader

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import net.dexxicon.reader.core.data.auth.SessionRefreshWorker
import net.dexxicon.reader.core.data.auth.SignInNotifier
import net.dexxicon.reader.core.network.di.DexxiconHttpClient
import okhttp3.OkHttpClient
import javax.inject.Inject

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

    override fun onCreate() {
        super.onCreate()
        SessionRefreshWorker.schedule(this)
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
            .crossfade(true)
            .build()
}
