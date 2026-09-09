package net.dexxicon.reader.core.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.dexxicon.reader.core.network.AuthInterceptor
import net.dexxicon.reader.core.network.BuildConfig
import net.dexxicon.reader.core.network.PersistentCookieJar
import net.dexxicon.reader.core.network.ReadiumHttpClient
import okhttp3.OkHttpClient
import org.readium.r2.shared.util.http.HttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The shared client: auth + logging, generous timeouts for large downloads/streams. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DexxiconHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            // One line per request/response in debug; silent (and no per-call string work
            // or URL leakage to logcat) in release.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

    @Provides
    @Singleton
    @DexxiconHttpClient
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        loggingInterceptor: HttpLoggingInterceptor,
        cookieJar: PersistentCookieJar,
    ): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Readium's HTTP stack, delegating to the shared authenticated client. */
    @Provides
    @Singleton
    fun provideReadiumHttpClient(
        @DexxiconHttpClient client: OkHttpClient,
    ): HttpClient = ReadiumHttpClient(client)
}
