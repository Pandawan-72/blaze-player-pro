package fr.retrospare.blazeplayer.player;

import android.app.Application;
import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
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
public final class MiniPlayerViewModel_Factory implements Factory<MiniPlayerViewModel> {
  private final Provider<Application> applicationProvider;

  private final Provider<DataStore<Preferences>> dataStoreProvider;

  private MiniPlayerViewModel_Factory(Provider<Application> applicationProvider,
      Provider<DataStore<Preferences>> dataStoreProvider) {
    this.applicationProvider = applicationProvider;
    this.dataStoreProvider = dataStoreProvider;
  }

  @Override
  public MiniPlayerViewModel get() {
    return newInstance(applicationProvider.get(), dataStoreProvider.get());
  }

  public static MiniPlayerViewModel_Factory create(Provider<Application> applicationProvider,
      Provider<DataStore<Preferences>> dataStoreProvider) {
    return new MiniPlayerViewModel_Factory(applicationProvider, dataStoreProvider);
  }

  public static MiniPlayerViewModel newInstance(Application application,
      DataStore<Preferences> dataStore) {
    return new MiniPlayerViewModel(application, dataStore);
  }
}
