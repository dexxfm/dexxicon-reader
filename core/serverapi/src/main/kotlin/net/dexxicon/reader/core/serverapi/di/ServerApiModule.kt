package net.dexxicon.reader.core.serverapi.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import net.dexxicon.reader.core.network.di.DexxiconHttpClient
import net.dexxicon.reader.core.serverapi.auth.NativeAuthApi
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import net.dexxicon.reader.core.serverapi.oidc.OidcApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

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
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideNativeAuthApi(retrofit: Retrofit): NativeAuthApi =
        retrofit.create(NativeAuthApi::class.java)

    @Provides
    @Singleton
    fun provideOidcApi(retrofit: Retrofit): OidcApi =
        retrofit.create(OidcApi::class.java)

    @Provides
    @Singleton
    fun provideGrimmoryBrowseApi(retrofit: Retrofit): GrimmoryBrowseApi =
        retrofit.create(GrimmoryBrowseApi::class.java)

    @Provides
    @Singleton
    fun provideBookOrbitBrowseApi(retrofit: Retrofit): BookOrbitBrowseApi =
        retrofit.create(BookOrbitBrowseApi::class.java)
}
