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
public final class NetworkScanner_Factory implements Factory<NetworkScanner> {
  @Override
  public NetworkScanner get() {
    return newInstance();
  }

  public static NetworkScanner_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static NetworkScanner newInstance() {
    return new NetworkScanner();
  }

  private static final class InstanceHolder {
    static final NetworkScanner_Factory INSTANCE = new NetworkScanner_Factory();
  }
}
