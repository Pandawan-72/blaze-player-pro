package fr.retrospare.blazeplayer.di;

import android.content.Context;
import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import fr.retrospare.blazeplayer.data.repository.NetworkRepository;
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
public final class NetworkModule_ProvideNetworkRepositoryFactory implements Factory<NetworkRepository> {
  private final Provider<Context> contextProvider;

  private final Provider<DataStore<Preferences>> dataStoreProvider;

  private NetworkModule_ProvideNetworkRepositoryFactory(Provider<Context> contextProvider,
      Provider<DataStore<Preferences>> dataStoreProvider) {
    this.contextProvider = contextProvider;
    this.dataStoreProvider = dataStoreProvider;
  }

  @Override
  public NetworkRepository get() {
    return provideNetworkRepository(contextProvider.get(), dataStoreProvider.get());
  }

  public static NetworkModule_ProvideNetworkRepositoryFactory create(
      Provider<Context> contextProvider, Provider<DataStore<Preferences>> dataStoreProvider) {
    return new NetworkModule_ProvideNetworkRepositoryFactory(contextProvider, dataStoreProvider);
  }

  public static NetworkRepository provideNetworkRepository(Context context,
      DataStore<Preferences> dataStore) {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideNetworkRepository(context, dataStore));
  }
}
