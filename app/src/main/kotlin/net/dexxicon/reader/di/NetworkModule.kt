package net.dexxicon.reader.di

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.dexxicon.reader.core.network.AuthInterceptor
import net.dexxicon.reader.core.network.DexxiconHttpClient
import net.dexxicon.reader.core.network.PersistentCookieJar
import net.dexxicon.reader.core.network.ReadiumHttpClient
import okhttp3.OkHttpClient
import org.readium.r2.shared.util.http.HttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Lives in `:app` (rather than `:core:network`) because Hilt modules are only compiled where
 * the components are, and `:core:network` is now a Kotlin Multiplatform module — the Hilt
 * Gradle plugin refuses to apply to it at all ("can only be applied to an Android project").
 * The bindings still satisfy `@Inject` in every module — Hilt aggregates modules at the app.
 * [DexxiconHttpClient] itself stays in `:core:network` — see its own doc comment.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideLoggingInterceptor(
        @ApplicationContext context: Context,
    ): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            // One line per request/response in a debuggable build; silent (and no per-call
            // string work or URL leakage to logcat) otherwise. Was BuildConfig.DEBUG before
            // this module went multiplatform — the `buildFeatures { buildConfig }` block
            // isn't available on the (deprecated, but still what AGP 9.4 ships)
            // `androidLibrary { }` KMP DSL, so this reads the same flag at runtime instead.
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            level = if (debuggable) {
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
