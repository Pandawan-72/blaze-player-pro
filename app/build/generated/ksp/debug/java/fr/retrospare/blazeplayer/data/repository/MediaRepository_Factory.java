package fr.retrospare.blazeplayer.data.repository;

import android.content.Context;
import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class MediaRepository_Factory implements Factory<MediaRepository> {
  private final Provider<Context> contextProvider;

  private final Provider<DataStore<Preferences>> dataStoreProvider;

  private MediaRepository_Factory(Provider<Context> contextProvider,
      Provider<DataStore<Preferences>> dataStoreProvider) {
    this.contextProvider = contextProvider;
    this.dataStoreProvider = dataStoreProvider;
  }

  @Override
  public MediaRepository get() {
    return newInstance(contextProvider.get(), dataStoreProvider.get());
  }

  public static MediaRepository_Factory create(Provider<Context> contextProvider,
      Provider<DataStore<Preferences>> dataStoreProvider) {
    return new MediaRepository_Factory(contextProvider, dataStoreProvider);
  }

  public static MediaRepository newInstance(Context context, DataStore<Preferences> dataStore) {
    return new MediaRepository(context, dataStore);
  }
}
