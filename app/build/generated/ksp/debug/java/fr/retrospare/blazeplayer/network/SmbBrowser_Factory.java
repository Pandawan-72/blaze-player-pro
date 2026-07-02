package fr.retrospare.blazeplayer.network;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
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
public final class SmbBrowser_Factory implements Factory<SmbBrowser> {
  @Override
  public SmbBrowser get() {
    return newInstance();
  }

  public static SmbBrowser_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static SmbBrowser newInstance() {
    return new SmbBrowser();
  }

  private static final class InstanceHolder {
    static final SmbBrowser_Factory INSTANCE = new SmbBrowser_Factory();
  }
}
