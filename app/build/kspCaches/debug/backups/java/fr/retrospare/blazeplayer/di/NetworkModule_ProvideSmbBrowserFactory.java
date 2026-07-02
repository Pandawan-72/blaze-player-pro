package fr.retrospare.blazeplayer.di;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import fr.retrospare.blazeplayer.network.SmbBrowser;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
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
public final class NetworkModule_ProvideSmbBrowserFactory implements Factory<SmbBrowser> {
  @Override
  public SmbBrowser get() {
    return provideSmbBrowser();
  }

  public static NetworkModule_ProvideSmbBrowserFactory create() {
    return InstanceHolder.INSTANCE;
  }

  public static SmbBrowser provideSmbBrowser() {
    return Preconditions.checkNotNullFromProvides(NetworkModule.INSTANCE.provideSmbBrowser());
  }

  private static final class InstanceHolder {
    static final NetworkModule_ProvideSmbBrowserFactory INSTANCE = new NetworkModule_ProvideSmbBrowserFactory();
  }
}
