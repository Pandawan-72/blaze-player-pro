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
public final class PlayerActivity_MembersInjector implements MembersInjector<PlayerActivity> {
  private final Provider<DataStore<Preferences>> dataStoreProvider;

  private final Provider<MediaRepository> mediaRepositoryProvider;

  private PlayerActivity_MembersInjector(Provider<DataStore<Preferences>> dataStoreProvider,
      Provider<MediaRepository> mediaRepositoryProvider) {
    this.dataStoreProvider = dataStoreProvider;
    this.mediaRepositoryProvider = mediaRepositoryProvider;
  }

  @Override
  public void injectMembers(PlayerActivity instance) {
    injectDataStore(instance, dataStoreProvider.get());
    injectMediaRepository(instance, mediaRepositoryProvider.get());
  }

  public static MembersInjector<PlayerActivity> create(
      Provider<DataStore<Preferences>> dataStoreProvider,
      Provider<MediaRepository> mediaRepositoryProvider) {
    return new PlayerActivity_MembersInjector(dataStoreProvider, mediaRepositoryProvider);
  }

  @InjectedFieldSignature("fr.retrospare.blazeplayer.player.PlayerActivity.dataStore")
  public static void injectDataStore(PlayerActivity instance, DataStore<Preferences> dataStore) {
    instance.dataStore = dataStore;
  }

  @InjectedFieldSignature("fr.retrospare.blazeplayer.player.PlayerActivity.mediaRepository")
  public static void injectMediaRepository(PlayerActivity instance,
      MediaRepository mediaRepository) {
    instance.mediaRepository = mediaRepository;
  }
}
