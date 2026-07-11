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

  private final Provider<SmbBrowser> smbBrowserProvider;

  private final Provider<UpnpBrowser> upnpBrowserProvider;

  private NetworkSharesViewModel_Factory(Provider<NetworkRepository> networkRepositoryProvider,
      Provider<NetworkScanner> networkScannerProvider, Provider<SmbBrowser> smbBrowserProvider,
      Provider<UpnpBrowser> upnpBrowserProvider) {
    this.networkRepositoryProvider = networkRepositoryProvider;
    this.networkScannerProvider = networkScannerProvider;
    this.smbBrowserProvider = smbBrowserProvider;
    this.upnpBrowserProvider = upnpBrowserProvider;
  }

  @Override
  public NetworkSharesViewModel get() {
    return newInstance(networkRepositoryProvider.get(), networkScannerProvider.get(), smbBrowserProvider.get(), upnpBrowserProvider.get());
  }

  public static NetworkSharesViewModel_Factory create(
      Provider<NetworkRepository> networkRepositoryProvider,
      Provider<NetworkScanner> networkScannerProvider, Provider<SmbBrowser> smbBrowserProvider,
      Provider<UpnpBrowser> upnpBrowserProvider) {
    return new NetworkSharesViewModel_Factory(networkRepositoryProvider, networkScannerProvider, smbBrowserProvider, upnpBrowserProvider);
  }

  public static NetworkSharesViewModel newInstance(NetworkRepository networkRepository,
      NetworkScanner networkScanner, SmbBrowser smbBrowser, UpnpBrowser upnpBrowser) {
    return new NetworkSharesViewModel(networkRepository, networkScanner, smbBrowser, upnpBrowser);
  }
}
