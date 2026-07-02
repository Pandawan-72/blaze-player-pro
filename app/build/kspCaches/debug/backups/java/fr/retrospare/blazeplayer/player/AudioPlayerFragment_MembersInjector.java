package fr.retrospare.blazeplayer.player;

import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import fr.retrospare.blazeplayer.data.repository.MediaRepository;
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
public final class AudioPlayerFragment_MembersInjector implements MembersInjector<AudioPlayerFragment> {
  private final Provider<MediaRepository> mediaRepositoryProvider;

  private AudioPlayerFragment_MembersInjector(Provider<MediaRepository> mediaRepositoryProvider) {
    this.mediaRepositoryProvider = mediaRepositoryProvider;
  }

  @Override
  public void injectMembers(AudioPlayerFragment instance) {
    injectMediaRepository(instance, mediaRepositoryProvider.get());
  }

  public static MembersInjector<AudioPlayerFragment> create(
      Provider<MediaRepository> mediaRepositoryProvider) {
    return new AudioPlayerFragment_MembersInjector(mediaRepositoryProvider);
  }

  @InjectedFieldSignature("fr.retrospare.blazeplayer.player.AudioPlayerFragment.mediaRepository")
  public static void injectMediaRepository(AudioPlayerFragment instance,
      MediaRepository mediaRepository) {
    instance.mediaRepository = mediaRepository;
  }
}
