package fr.retrospare.blazeplayer.player;

import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import fr.retrospare.blazeplayer.data.repository.NetworkRepository;
import fr.retrospare.blazeplayer.network.SmbBrowser;
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
public final class NetworkVideoBrowserActivity_MembersInjector implements MembersInjector<NetworkVideoBrowserActivity> {
  private final Provider<SmbBrowser> smbBrowserProvider;

  private final Provider<NetworkRepository> networkRepositoryProvider;

  private NetworkVideoBrowserActivity_MembersInjector(Provider<SmbBrowser> smbBrowserProvider,
      Provider<NetworkRepository> networkRepositoryProvider) {
    this.smbBrowserProvider = smbBrowserProvider;
    this.networkRepositoryProvider = networkRepositoryProvider;
  }

  @Override
  public void injectMembers(NetworkVideoBrowserActivity instance) {
    injectSmbBrowser(instance, smbBrowserProvider.get());
    injectNetworkRepository(instance, networkRepositoryProvider.get());
  }

  public static MembersInjector<NetworkVideoBrowserActivity> create(
      Provider<SmbBrowser> smbBrowserProvider,
      Provider<NetworkRepository> networkRepositoryProvider) {
    return new NetworkVideoBrowserActivity_MembersInjector(smbBrowserProvider, networkRepositoryProvider);
  }

  @InjectedFieldSignature("fr.retrospare.blazeplayer.player.NetworkVideoBrowserActivity.smbBrowser")
  public static void injectSmbBrowser(NetworkVideoBrowserActivity instance, SmbBrowser smbBrowser) {
    instance.smbBrowser = smbBrowser;
  }

  @InjectedFieldSignature("fr.retrospare.blazeplayer.player.NetworkVideoBrowserActivity.networkRepository")
  public static void injectNetworkRepository(NetworkVideoBrowserActivity instance,
      NetworkRepository networkRepository) {
    instance.networkRepository = networkRepository;
  }
}
