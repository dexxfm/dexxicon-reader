package net.dexxicon.reader.di

import net.dexxicon.reader.core.data.catalog.KavitaCatalogSource
import net.dexxicon.reader.core.serverapi.browse.KavitaBrowseApi
import net.dexxicon.reader.core.data.catalog.KomgaCatalogSource
import net.dexxicon.reader.core.serverapi.browse.KomgaBrowseApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.data.CatalogRepository
import net.dexxicon.reader.core.data.ServerRepository
import net.dexxicon.reader.core.data.catalog.BookOrbitCatalogSource
import net.dexxicon.reader.core.data.catalog.GrimmoryCatalogSource
import net.dexxicon.reader.core.data.catalog.OpdsCatalogSource
import net.dexxicon.reader.core.serverapi.opds.OpdsClient
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import javax.inject.Singleton

/**
 * Lives in `:app` (rather than `:core:data`) — same reason as every other Phase 1/2 module:
 * the Hilt Gradle plugin refuses to apply to a KMP module, and these classes (issue #76)
 * dropped `@Inject`/`@Singleton` when they moved to commonMain (`javax.inject` doesn't exist
 * on iOS), so they need explicit `@Provides` here instead of constructor injection —
 * `:shared`'s `AppContainer` builds the identical graph by hand for the same reason.
 */
@Module
@InstallIn(SingletonComponent::class)
object CatalogModule {

    @Provides
    @Singleton
    fun provideBookOrbitCatalogSource(api: BookOrbitBrowseApi): BookOrbitCatalogSource =
        BookOrbitCatalogSource(api)

    @Provides
    @Singleton
    fun provideGrimmoryCatalogSource(api: GrimmoryBrowseApi): GrimmoryCatalogSource =
        GrimmoryCatalogSource(api)

    /** issue #327 */
    @Provides
    @Singleton
    fun provideKomgaCatalogSource(api: KomgaBrowseApi): KomgaCatalogSource = KomgaCatalogSource(api)

    /** issue #328 */
    @Provides
    @Singleton
    fun provideKavitaCatalogSource(api: KavitaBrowseApi): KavitaCatalogSource = KavitaCatalogSource(api)

    @Provides
    @Singleton
    fun provideOpdsCatalogSource(client: OpdsClient): OpdsCatalogSource = OpdsCatalogSource(client)

    @Provides
    @Singleton
    fun provideCatalogRepository(
        serverRepository: ServerRepository,
        grimmorySource: GrimmoryCatalogSource,
        bookOrbitSource: BookOrbitCatalogSource,
        komgaSource: KomgaCatalogSource,
        kavitaSource: KavitaCatalogSource,
        opdsSource: OpdsCatalogSource,
        @Dispatcher(DexxiconDispatcher.IO) io: CoroutineDispatcher,
    ): CatalogRepository =
        CatalogRepository(serverRepository, grimmorySource, bookOrbitSource, komgaSource, kavitaSource, opdsSource, io)
}
