package net.dexxicon.reader.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import net.dexxicon.reader.core.serverapi.annotation.AnnotationApi
import net.dexxicon.reader.core.serverapi.bookmark.BookmarkApi
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import net.dexxicon.reader.core.serverapi.kosync.KoSyncApi
import net.dexxicon.reader.core.serverapi.progress.NativeProgressApi
import net.dexxicon.reader.core.serverapi.user.NativeUserApi
import javax.inject.Singleton

/**
 * Lives in `:app` (rather than `:core:serverapi`) because Hilt modules are only compiled
 * where the components are, and `:core:serverapi` is now a Kotlin Multiplatform module — see
 * `NetworkModule`'s own doc comment for the full reasoning (same one applies here).
 *
 * Every `:core:serverapi` API is Ktor-backed now (issues #52, #56), sharing the one
 * [HttpClient] [ServerAuthModule] builds — these classes can't carry `@Inject` in commonMain
 * (`javax.inject` isn't available on iOS), so, like [ServerAuthModule], this module supplies
 * them explicitly instead of relying on constructor injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServerApiModule {

    @Provides
    @Singleton
    fun provideGrimmoryBrowseApi(client: HttpClient): GrimmoryBrowseApi = GrimmoryBrowseApi(client)

    @Provides
    @Singleton
    fun provideBookOrbitBrowseApi(client: HttpClient): BookOrbitBrowseApi = BookOrbitBrowseApi(client)

    @Provides
    @Singleton
    fun provideKoSyncApi(client: HttpClient): KoSyncApi = KoSyncApi(client)

    @Provides
    @Singleton
    fun provideAnnotationApi(client: HttpClient): AnnotationApi = AnnotationApi(client)

    @Provides
    @Singleton
    fun provideBookmarkApi(client: HttpClient): BookmarkApi = BookmarkApi(client)

    @Provides
    @Singleton
    fun provideNativeProgressApi(client: HttpClient): NativeProgressApi = NativeProgressApi(client)

    @Provides
    @Singleton
    fun provideNativeUserApi(client: HttpClient): NativeUserApi = NativeUserApi(client)
}
