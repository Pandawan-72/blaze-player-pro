package fr.retrospare.blazeplayer.network;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import fr.retrospare.blazeplayer.data.repository.NetworkRepository;
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
public final class NetworkSharesViewModel_Factory implements Factory<NetworkSharesViewModel> {
  private final Provider<NetworkRepository> networkRepositoryProvider;

  private final Provider<NetworkScanner> networkScannerProvider;

  private NetworkSharesViewModel_Factory(Provider<NetworkRepository> networkRepositoryProvider,
      Provider<NetworkScanner> networkScannerProvider) {
    this.networkRepositoryProvider = networkRepositoryProvider;
    this.networkScannerProvider = networkScannerProvider;
  }

  @Override
  public NetworkSharesViewModel get() {
    return newInstance(networkRepositoryProvider.get(), networkScannerProvider.get());
  }

  public static NetworkSharesViewModel_Factory create(
      Provider<NetworkRepository> networkRepositoryProvider,
      Provider<NetworkScanner> networkScannerProvider) {
    return new NetworkSharesViewModel_Factory(networkRepositoryProvider, networkScannerProvider);
  }

  public static NetworkSharesViewModel newInstance(NetworkRepository networkRepository,
      NetworkScanner networkScanner) {
    return new NetworkSharesViewModel(networkRepository, networkScanner);
  }
}
