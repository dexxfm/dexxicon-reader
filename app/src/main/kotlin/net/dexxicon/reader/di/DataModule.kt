package net.dexxicon.reader.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.dexxicon.reader.core.data.auth.AuthHeaderProviderImpl
import net.dexxicon.reader.core.data.media.MediaLibraryContentSourceImpl
import net.dexxicon.reader.core.data.media.PlaybackProgressSinkImpl
import net.dexxicon.reader.core.media.MediaLibraryContentSource
import net.dexxicon.reader.core.media.PlaybackProgressSink
import net.dexxicon.reader.core.network.AuthHeaderProvider
import javax.inject.Singleton

/**
 * Lives in `:app` (rather than `:core:data`) because Hilt modules are only compiled where
 * the components are, and `:core:data` is now a Kotlin Multiplatform module — see
 * `NetworkModule`'s own doc comment for the full reasoning (same one applies here).
 */
@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Binds
    @Singleton
    fun bindAuthHeaderProvider(impl: AuthHeaderProviderImpl): AuthHeaderProvider

    @Binds
    @Singleton
    fun bindPlaybackProgressSink(impl: PlaybackProgressSinkImpl): PlaybackProgressSink

    @Binds
    @Singleton
    fun bindMediaLibraryContentSource(impl: MediaLibraryContentSourceImpl): MediaLibraryContentSource
}
