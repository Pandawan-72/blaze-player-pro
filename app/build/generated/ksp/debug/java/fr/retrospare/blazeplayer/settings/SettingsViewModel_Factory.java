package fr.retrospare.blazeplayer.settings;

import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import fr.retrospare.blazeplayer.data.repository.MediaRepository;
import javax.annotation.processing.Generated;

@ScopeMetadata
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
public final class SettingsViewModel_Factory implements Factory<SettingsViewModel> {
  private final Provider<DataStore<Preferences>> dataStoreProvider;

  private final Provider<MediaRepository> mediaRepositoryProvider;

  private SettingsViewModel_Factory(Provider<DataStore<Preferences>> dataStoreProvider,
      Provider<MediaRepository> mediaRepositoryProvider) {
    this.dataStoreProvider = dataStoreProvider;
    this.mediaRepositoryProvider = mediaRepositoryProvider;
  }

  @Override
  public SettingsViewModel get() {
    return newInstance(dataStoreProvider.get(), mediaRepositoryProvider.get());
  }

  public static SettingsViewModel_Factory create(Provider<DataStore<Preferences>> dataStoreProvider,
      Provider<MediaRepository> mediaRepositoryProvider) {
    return new SettingsViewModel_Factory(dataStoreProvider, mediaRepositoryProvider);
  }

  public static SettingsViewModel newInstance(DataStore<Preferences> dataStore,
      MediaRepository mediaRepository) {
    return new SettingsViewModel(dataStore, mediaRepository);
  }
}
