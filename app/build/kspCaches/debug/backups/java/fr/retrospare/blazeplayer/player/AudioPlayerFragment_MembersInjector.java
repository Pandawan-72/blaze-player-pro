package fr.retrospare.blazeplayer.player;

import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import fr.retrospare.blazeplayer.data.repository.MediaRepository;
import javax.annotation.processing.Generated;

@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class AudioPlayerFragment_MembersInjector implements MembersInjector<AudioPlayerFragment> {
  private final Provider<MediaRepository> mediaRepositoryProvider;

  private final Provider<DataStore<Preferences>> dataStoreProvider;

  private AudioPlayerFragment_MembersInjector(Provider<MediaRepository> mediaRepositoryProvider,
      Provider<DataStore<Preferences>> dataStoreProvider) {
    this.mediaRepositoryProvider = mediaRepositoryProvider;
    this.dataStoreProvider = dataStoreProvider;
  }

  @Override
  public void injectMembers(AudioPlayerFragment instance) {
    injectMediaRepository(instance, mediaRepositoryProvider.get());
    injectDataStore(instance, dataStoreProvider.get());
  }

  public static MembersInjector<AudioPlayerFragment> create(
      Provider<MediaRepository> mediaRepositoryProvider,
      Provider<DataStore<Preferences>> dataStoreProvider) {
    return new AudioPlayerFragment_MembersInjector(mediaRepositoryProvider, dataStoreProvider);
  }

  @InjectedFieldSignature("fr.retrospare.blazeplayer.player.AudioPlayerFragment.mediaRepository")
  public static void injectMediaRepository(AudioPlayerFragment instance,
      MediaRepository mediaRepository) {
    instance.mediaRepository = mediaRepository;
  }

  @InjectedFieldSignature("fr.retrospare.blazeplayer.player.AudioPlayerFragment.dataStore")
  public static void injectDataStore(AudioPlayerFragment instance,
      DataStore<Preferences> dataStore) {
    instance.dataStore = dataStore;
  }
}
