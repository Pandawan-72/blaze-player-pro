package fr.retrospare.blazeplayer.paywall;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import fr.retrospare.blazeplayer.data.repository.UserRepository;
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
public final class PaywallViewModel_Factory implements Factory<PaywallViewModel> {
  private final Provider<UserRepository> userRepositoryProvider;

  private PaywallViewModel_Factory(Provider<UserRepository> userRepositoryProvider) {
    this.userRepositoryProvider = userRepositoryProvider;
  }

  @Override
  public PaywallViewModel get() {
    return newInstance(userRepositoryProvider.get());
  }

  public static PaywallViewModel_Factory create(Provider<UserRepository> userRepositoryProvider) {
    return new PaywallViewModel_Factory(userRepositoryProvider);
  }

  public static PaywallViewModel newInstance(UserRepository userRepository) {
    return new PaywallViewModel(userRepository);
  }
}
