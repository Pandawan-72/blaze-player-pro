package fr.retrospare.blazeplayer.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.retrospare.blazeplayer.data.repository.NetworkRepository
import fr.retrospare.blazeplayer.network.SmbBrowser
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideSmbBrowser(): SmbBrowser = SmbBrowser()

    @Provides
    @Singleton
    fun provideNetworkRepository(
        @ApplicationContext context: Context,
        dataStore: DataStore<Preferences>
    ): NetworkRepository = NetworkRepository(context, dataStore)
}
