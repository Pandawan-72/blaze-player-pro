package fr.retrospare.blazeplayer.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    // SMB and DLNA managers are injected directly via @Inject constructors
}
