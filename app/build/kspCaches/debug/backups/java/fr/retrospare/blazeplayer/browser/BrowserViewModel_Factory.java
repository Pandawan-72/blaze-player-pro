package fr.retrospare.blazeplayer.browser;

import android.content.Context;
import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import fr.retrospare.blazeplayer.data.repository.MediaRepository;
import fr.retrospare.blazeplayer.data.repository.NetworkRepository;
import fr.retrospare.blazeplayer.network.SmbBrowser;
import fr.retrospare.blazeplayer.network.UpnpBrowser;
import javax.annotation.processing.Generated;

@ScopeMetadata
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
public final class BrowserViewModel_Factory implements Factory<BrowserViewModel> {
  private final Provider<DataStore<Preferences>> dataStoreProvider;

  private final Provider<Context> contextProvider;

  private final Provider<MediaRepository> mediaRepositoryProvider;

  private final Provider<SmbBrowser> smbBrowserProvider;

  private final Provider<UpnpBrowser> upnpBrowserProvider;

  private final Provider<NetworkRepository> networkRepositoryProvider;

  private BrowserViewModel_Factory(Provider<DataStore<Preferences>> dataStoreProvider,
      Provider<Context> contextProvider, Provider<MediaRepository> mediaRepositoryProvider,
      Provider<SmbBrowser> smbBrowserProvider, Provider<UpnpBrowser> upnpBrowserProvider,
      Provider<NetworkRepository> networkRepositoryProvider) {
    this.dataStoreProvider = dataStoreProvider;
    this.contextProvider = contextProvider;
    this.mediaRepositoryProvider = mediaRepositoryProvider;
    this.smbBrowserProvider = smbBrowserProvider;
    this.upnpBrowserProvider = upnpBrowserProvider;
    this.networkRepositoryProvider = networkRepositoryProvider;
  }

  @Override
  public BrowserViewModel get() {
    return newInstance(dataStoreProvider.get(), contextProvider.get(), mediaRepositoryProvider.get(), smbBrowserProvider.get(), upnpBrowserProvider.get(), networkRepositoryProvider.get());
  }

  public static BrowserViewModel_Factory create(Provider<DataStore<Preferences>> dataStoreProvider,
      Provider<Context> contextProvider, Provider<MediaRepository> mediaRepositoryProvider,
      Provider<SmbBrowser> smbBrowserProvider, Provider<UpnpBrowser> upnpBrowserProvider,
      Provider<NetworkRepository> networkRepositoryProvider) {
    return new BrowserViewModel_Factory(dataStoreProvider, contextProvider, mediaRepositoryProvider, smbBrowserProvider, upnpBrowserProvider, networkRepositoryProvider);
  }

  public static BrowserViewModel newInstance(DataStore<Preferences> dataStore, Context context,
      MediaRepository mediaRepository, SmbBrowser smbBrowser, UpnpBrowser upnpBrowser,
      NetworkRepository networkRepository) {
    return new BrowserViewModel(dataStore, context, mediaRepository, smbBrowser, upnpBrowser, networkRepository);
  }
}
