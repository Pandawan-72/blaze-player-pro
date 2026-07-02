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
public final class NetworkRepository_Factory implements Factory<NetworkRepository> {
  private final Provider<Context> contextProvider;

  private final Provider<DataStore<Preferences>> dataStoreProvider;

  private NetworkRepository_Factory(Provider<Context> contextProvider,
      Provider<DataStore<Preferences>> dataStoreProvider) {
    this.contextProvider = contextProvider;
    this.dataStoreProvider = dataStoreProvider;
  }

  @Override
  public NetworkRepository get() {
    return newInstance(contextProvider.get(), dataStoreProvider.get());
  }

  public static NetworkRepository_Factory create(Provider<Context> contextProvider,
      Provider<DataStore<Preferences>> dataStoreProvider) {
    return new NetworkRepository_Factory(contextProvider, dataStoreProvider);
  }

  public static NetworkRepository newInstance(Context context, DataStore<Preferences> dataStore) {
    return new NetworkRepository(context, dataStore);
  }
}
