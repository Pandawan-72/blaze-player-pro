package fr.retrospare.blazeplayer.home;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import fr.retrospare.blazeplayer.data.repository.MediaRepository;
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
public final class HomeViewModel_Factory implements Factory<HomeViewModel> {
  private final Provider<Context> contextProvider;

  private final Provider<MediaRepository> mediaRepositoryProvider;

  private HomeViewModel_Factory(Provider<Context> contextProvider,
      Provider<MediaRepository> mediaRepositoryProvider) {
    this.contextProvider = contextProvider;
    this.mediaRepositoryProvider = mediaRepositoryProvider;
  }

  @Override
  public HomeViewModel get() {
    return newInstance(contextProvider.get(), mediaRepositoryProvider.get());
  }

  public static HomeViewModel_Factory create(Provider<Context> contextProvider,
      Provider<MediaRepository> mediaRepositoryProvider) {
    return new HomeViewModel_Factory(contextProvider, mediaRepositoryProvider);
  }

  public static HomeViewModel newInstance(Context context, MediaRepository mediaRepository) {
    return new HomeViewModel(context, mediaRepository);
  }
}
