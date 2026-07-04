package fr.retrospare.blazeplayer.player;

import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import fr.retrospare.blazeplayer.data.repository.NetworkRepository;
import fr.retrospare.blazeplayer.network.SmbBrowser;
import fr.retrospare.blazeplayer.network.UpnpBrowser;
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
public final class AudioBrowserActivity_MembersInjector implements MembersInjector<AudioBrowserActivity> {
  private final Provider<NetworkRepository> networkRepositoryProvider;

  private final Provider<SmbBrowser> smbBrowserProvider;

  private final Provider<UpnpBrowser> upnpBrowserProvider;

  private AudioBrowserActivity_MembersInjector(
      Provider<NetworkRepository> networkRepositoryProvider,
      Provider<SmbBrowser> smbBrowserProvider, Provider<UpnpBrowser> upnpBrowserProvider) {
    this.networkRepositoryProvider = networkRepositoryProvider;
    this.smbBrowserProvider = smbBrowserProvider;
    this.upnpBrowserProvider = upnpBrowserProvider;
  }

  @Override
  public void injectMembers(AudioBrowserActivity instance) {
    injectNetworkRepository(instance, networkRepositoryProvider.get());
    injectSmbBrowser(instance, smbBrowserProvider.get());
    injectUpnpBrowser(instance, upnpBrowserProvider.get());
  }

  public static MembersInjector<AudioBrowserActivity> create(
      Provider<NetworkRepository> networkRepositoryProvider,
      Provider<SmbBrowser> smbBrowserProvider, Provider<UpnpBrowser> upnpBrowserProvider) {
    return new AudioBrowserActivity_MembersInjector(networkRepositoryProvider, smbBrowserProvider, upnpBrowserProvider);
  }

  @InjectedFieldSignature("fr.retrospare.blazeplayer.player.AudioBrowserActivity.networkRepository")
  public static void injectNetworkRepository(AudioBrowserActivity instance,
      NetworkRepository networkRepository) {
    instance.networkRepository = networkRepository;
  }

  @InjectedFieldSignature("fr.retrospare.blazeplayer.player.AudioBrowserActivity.smbBrowser")
  public static void injectSmbBrowser(AudioBrowserActivity instance, SmbBrowser smbBrowser) {
    instance.smbBrowser = smbBrowser;
  }

  @InjectedFieldSignature("fr.retrospare.blazeplayer.player.AudioBrowserActivity.upnpBrowser")
  public static void injectUpnpBrowser(AudioBrowserActivity instance, UpnpBrowser upnpBrowser) {
    instance.upnpBrowser = upnpBrowser;
  }
}
