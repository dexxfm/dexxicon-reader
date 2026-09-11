package net.dexxicon.reader.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import net.dexxicon.reader.core.network.DexxiconHttpClient
import net.dexxicon.reader.core.serverapi.annotation.AnnotationApi
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import net.dexxicon.reader.core.serverapi.kosync.KoSyncApi
import net.dexxicon.reader.core.serverapi.NullableBodyConverterFactory
import net.dexxicon.reader.core.serverapi.progress.NativeProgressApi
import net.dexxicon.reader.core.serverapi.user.NativeUserApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

/**
 * Lives in `:app` (rather than `:core:serverapi`) because Hilt modules are only compiled
 * where the components are, and `:core:serverapi` is now a Kotlin Multiplatform module — see
 * `NetworkModule`'s own doc comment for the full reasoning (same one applies here).
 *
 * Provides the Retrofit-based bindings for every API `:core:serverapi` hasn't ported to Ktor
 * yet (issue #52) — browse/bookmark/kosync/annotation/progress/user. The sign-in path
 * (`NativeAuthApi`/`OidcApi`, now Ktor + commonMain) is provided by [ServerAuthModule]
 * instead.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServerApiModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        @DexxiconHttpClient client: OkHttpClient,
        json: Json,
    ): Retrofit = Retrofit.Builder()
        // Placeholder — every call passes an absolute @Url.
        .baseUrl("http://localhost/")
        .client(client)
        .addConverterFactory(
            NullableBodyConverterFactory(
                json.asConverterFactory("application/json".toMediaType()),
            ),
        )
        .build()

    @Provides
    @Singleton
    fun provideGrimmoryBrowseApi(retrofit: Retrofit): GrimmoryBrowseApi =
        retrofit.create(GrimmoryBrowseApi::class.java)

    @Provides
    @Singleton
    fun provideBookOrbitBrowseApi(retrofit: Retrofit): BookOrbitBrowseApi =
        retrofit.create(BookOrbitBrowseApi::class.java)

    @Provides
    @Singleton
    fun provideKoSyncApi(retrofit: Retrofit): KoSyncApi =
        retrofit.create(KoSyncApi::class.java)

    @Provides
    @Singleton
    fun provideAnnotationApi(retrofit: Retrofit): AnnotationApi =
        retrofit.create(AnnotationApi::class.java)

    @Provides
    @Singleton
    fun provideBookmarkApi(retrofit: Retrofit): net.dexxicon.reader.core.serverapi.bookmark.BookmarkApi =
        retrofit.create(net.dexxicon.reader.core.serverapi.bookmark.BookmarkApi::class.java)

    @Provides
    @Singleton
    fun provideNativeProgressApi(retrofit: Retrofit): NativeProgressApi =
        retrofit.create(NativeProgressApi::class.java)

    @Provides
    @Singleton
    fun provideNativeUserApi(retrofit: Retrofit): NativeUserApi =
        retrofit.create(NativeUserApi::class.java)
}
