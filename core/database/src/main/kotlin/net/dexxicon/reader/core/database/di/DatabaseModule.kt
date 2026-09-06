package net.dexxicon.reader.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.dexxicon.reader.core.database.DexxiconDatabase
import net.dexxicon.reader.core.database.dao.ServerDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DexxiconDatabase =
        Room.databaseBuilder(context, DexxiconDatabase::class.java, DexxiconDatabase.NAME)
            .build()

    @Provides
    fun provideServerDao(database: DexxiconDatabase): ServerDao = database.serverDao()
}
