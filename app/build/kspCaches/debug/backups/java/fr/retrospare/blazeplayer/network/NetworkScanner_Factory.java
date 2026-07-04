package fr.retrospare.blazeplayer.network;

import android.content.Context;
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
public final class NetworkScanner_Factory implements Factory<NetworkScanner> {
  private final Provider<Context> contextProvider;

  private NetworkScanner_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public NetworkScanner get() {
    return newInstance(contextProvider.get());
  }

  public static NetworkScanner_Factory create(Provider<Context> contextProvider) {
    return new NetworkScanner_Factory(contextProvider);
  }

  public static NetworkScanner newInstance(Context context) {
    return new NetworkScanner(context);
  }
}
