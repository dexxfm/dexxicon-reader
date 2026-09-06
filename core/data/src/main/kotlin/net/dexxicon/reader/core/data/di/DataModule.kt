package net.dexxicon.reader.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.dexxicon.reader.core.data.auth.AuthHeaderProviderImpl
import net.dexxicon.reader.core.network.AuthHeaderProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Binds
    @Singleton
    fun bindAuthHeaderProvider(impl: AuthHeaderProviderImpl): AuthHeaderProvider
}
