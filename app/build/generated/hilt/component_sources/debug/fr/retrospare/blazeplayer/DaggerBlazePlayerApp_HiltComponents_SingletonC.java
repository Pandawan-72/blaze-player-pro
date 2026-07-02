package fr.retrospare.blazeplayer;

import android.app.Activity;
import android.app.Service;
import android.view.View;
import androidx.datastore.core.DataStore;
import androidx.datastore.preferences.core.Preferences;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dagger.hilt.android.ActivityRetainedLifecycle;
import dagger.hilt.android.ViewModelLifecycle;
import dagger.hilt.android.internal.builders.ActivityComponentBuilder;
import dagger.hilt.android.internal.builders.ActivityRetainedComponentBuilder;
import dagger.hilt.android.internal.builders.FragmentComponentBuilder;
import dagger.hilt.android.internal.builders.ServiceComponentBuilder;
import dagger.hilt.android.internal.builders.ViewComponentBuilder;
import dagger.hilt.android.internal.builders.ViewModelComponentBuilder;
import dagger.hilt.android.internal.builders.ViewWithFragmentComponentBuilder;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories;
import dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories_InternalFactoryFactory_Factory;
import dagger.hilt.android.internal.managers.ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory;
import dagger.hilt.android.internal.managers.SavedStateHandleHolder;
import dagger.hilt.android.internal.modules.ApplicationContextModule;
import dagger.hilt.android.internal.modules.ApplicationContextModule_ProvideApplicationFactory;
import dagger.hilt.android.internal.modules.ApplicationContextModule_ProvideContextFactory;
import dagger.internal.DaggerGenerated;
import dagger.internal.DoubleCheck;
import dagger.internal.LazyClassKeyMap;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import fr.retrospare.blazeplayer.auth.AuthViewModel;
import fr.retrospare.blazeplayer.auth.AuthViewModel_HiltModules;
import fr.retrospare.blazeplayer.auth.AuthViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import fr.retrospare.blazeplayer.auth.AuthViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import fr.retrospare.blazeplayer.browser.BrowserFragment;
import fr.retrospare.blazeplayer.browser.BrowserViewModel;
import fr.retrospare.blazeplayer.browser.BrowserViewModel_HiltModules;
import fr.retrospare.blazeplayer.browser.BrowserViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import fr.retrospare.blazeplayer.browser.BrowserViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import fr.retrospare.blazeplayer.data.repository.MediaRepository;
import fr.retrospare.blazeplayer.data.repository.NetworkRepository;
import fr.retrospare.blazeplayer.data.repository.UserRepository;
import fr.retrospare.blazeplayer.di.AppModule_ProvideDataStoreFactory;
import fr.retrospare.blazeplayer.di.NetworkModule_ProvideNetworkRepositoryFactory;
import fr.retrospare.blazeplayer.di.NetworkModule_ProvideSmbBrowserFactory;
import fr.retrospare.blazeplayer.home.HomeFragment;
import fr.retrospare.blazeplayer.home.HomeViewModel;
import fr.retrospare.blazeplayer.home.HomeViewModel_HiltModules;
import fr.retrospare.blazeplayer.home.HomeViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import fr.retrospare.blazeplayer.home.HomeViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import fr.retrospare.blazeplayer.network.NetworkScanner;
import fr.retrospare.blazeplayer.network.NetworkSharesFragment;
import fr.retrospare.blazeplayer.network.NetworkSharesViewModel;
import fr.retrospare.blazeplayer.network.NetworkSharesViewModel_HiltModules;
import fr.retrospare.blazeplayer.network.NetworkSharesViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import fr.retrospare.blazeplayer.network.NetworkSharesViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import fr.retrospare.blazeplayer.network.SmbBrowser;
import fr.retrospare.blazeplayer.paywall.PaywallFragment;
import fr.retrospare.blazeplayer.paywall.PaywallViewModel;
import fr.retrospare.blazeplayer.paywall.PaywallViewModel_HiltModules;
import fr.retrospare.blazeplayer.paywall.PaywallViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import fr.retrospare.blazeplayer.paywall.PaywallViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import fr.retrospare.blazeplayer.player.AudioBrowserActivity;
import fr.retrospare.blazeplayer.player.AudioBrowserActivity_MembersInjector;
import fr.retrospare.blazeplayer.player.AudioPlayerFragment;
import fr.retrospare.blazeplayer.player.AudioPlayerFragment_MembersInjector;
import fr.retrospare.blazeplayer.player.MiniPlayerViewModel;
import fr.retrospare.blazeplayer.player.MiniPlayerViewModel_HiltModules;
import fr.retrospare.blazeplayer.player.MiniPlayerViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import fr.retrospare.blazeplayer.player.MiniPlayerViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import fr.retrospare.blazeplayer.player.NetworkVideoBrowserActivity;
import fr.retrospare.blazeplayer.player.NetworkVideoBrowserActivity_MembersInjector;
import fr.retrospare.blazeplayer.player.PlayerActivity;
import fr.retrospare.blazeplayer.player.PlayerActivity_MembersInjector;
import fr.retrospare.blazeplayer.settings.SettingsFragment;
import fr.retrospare.blazeplayer.settings.SettingsViewModel;
import fr.retrospare.blazeplayer.settings.SettingsViewModel_HiltModules;
import fr.retrospare.blazeplayer.settings.SettingsViewModel_HiltModules_BindsModule_Binds_LazyMapKey;
import fr.retrospare.blazeplayer.settings.SettingsViewModel_HiltModules_KeyModule_Provide_LazyMapKey;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

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
public final class DaggerBlazePlayerApp_HiltComponents_SingletonC {
  private DaggerBlazePlayerApp_HiltComponents_SingletonC() {
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private ApplicationContextModule applicationContextModule;

    private Builder() {
    }

    public Builder applicationContextModule(ApplicationContextModule applicationContextModule) {
      this.applicationContextModule = Preconditions.checkNotNull(applicationContextModule);
      return this;
    }

    public BlazePlayerApp_HiltComponents.SingletonC build() {
      Preconditions.checkBuilderRequirement(applicationContextModule, ApplicationContextModule.class);
      return new SingletonCImpl(applicationContextModule);
    }
  }

  private static final class ActivityRetainedCBuilder implements BlazePlayerApp_HiltComponents.ActivityRetainedC.Builder {
    private final SingletonCImpl singletonCImpl;

    private SavedStateHandleHolder savedStateHandleHolder;

    private ActivityRetainedCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ActivityRetainedCBuilder savedStateHandleHolder(
        SavedStateHandleHolder savedStateHandleHolder) {
      this.savedStateHandleHolder = Preconditions.checkNotNull(savedStateHandleHolder);
      return this;
    }

    @Override
    public BlazePlayerApp_HiltComponents.ActivityRetainedC build() {
      Preconditions.checkBuilderRequirement(savedStateHandleHolder, SavedStateHandleHolder.class);
      return new ActivityRetainedCImpl(singletonCImpl, savedStateHandleHolder);
    }
  }

  private static final class ActivityCBuilder implements BlazePlayerApp_HiltComponents.ActivityC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private Activity activity;

    private ActivityCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ActivityCBuilder activity(Activity activity) {
      this.activity = Preconditions.checkNotNull(activity);
      return this;
    }

    @Override
    public BlazePlayerApp_HiltComponents.ActivityC build() {
      Preconditions.checkBuilderRequirement(activity, Activity.class);
      return new ActivityCImpl(singletonCImpl, activityRetainedCImpl, activity);
    }
  }

  private static final class FragmentCBuilder implements BlazePlayerApp_HiltComponents.FragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private Fragment fragment;

    private FragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public FragmentCBuilder fragment(Fragment fragment) {
      this.fragment = Preconditions.checkNotNull(fragment);
      return this;
    }

    @Override
    public BlazePlayerApp_HiltComponents.FragmentC build() {
      Preconditions.checkBuilderRequirement(fragment, Fragment.class);
      return new FragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragment);
    }
  }

  private static final class ViewWithFragmentCBuilder implements BlazePlayerApp_HiltComponents.ViewWithFragmentC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private View view;

    private ViewWithFragmentCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;
    }

    @Override
    public ViewWithFragmentCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public BlazePlayerApp_HiltComponents.ViewWithFragmentC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewWithFragmentCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl, view);
    }
  }

  private static final class ViewCBuilder implements BlazePlayerApp_HiltComponents.ViewC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private View view;

    private ViewCBuilder(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
    }

    @Override
    public ViewCBuilder view(View view) {
      this.view = Preconditions.checkNotNull(view);
      return this;
    }

    @Override
    public BlazePlayerApp_HiltComponents.ViewC build() {
      Preconditions.checkBuilderRequirement(view, View.class);
      return new ViewCImpl(singletonCImpl, activityRetainedCImpl, activityCImpl, view);
    }
  }

  private static final class ViewModelCBuilder implements BlazePlayerApp_HiltComponents.ViewModelC.Builder {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private SavedStateHandle savedStateHandle;

    private ViewModelLifecycle viewModelLifecycle;

    private ViewModelCBuilder(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
    }

    @Override
    public ViewModelCBuilder savedStateHandle(SavedStateHandle handle) {
      this.savedStateHandle = Preconditions.checkNotNull(handle);
      return this;
    }

    @Override
    public ViewModelCBuilder viewModelLifecycle(ViewModelLifecycle viewModelLifecycle) {
      this.viewModelLifecycle = Preconditions.checkNotNull(viewModelLifecycle);
      return this;
    }

    @Override
    public BlazePlayerApp_HiltComponents.ViewModelC build() {
      Preconditions.checkBuilderRequirement(savedStateHandle, SavedStateHandle.class);
      Preconditions.checkBuilderRequirement(viewModelLifecycle, ViewModelLifecycle.class);
      return new ViewModelCImpl(singletonCImpl, activityRetainedCImpl, savedStateHandle, viewModelLifecycle);
    }
  }

  private static final class ServiceCBuilder implements BlazePlayerApp_HiltComponents.ServiceC.Builder {
    private final SingletonCImpl singletonCImpl;

    private Service service;

    private ServiceCBuilder(SingletonCImpl singletonCImpl) {
      this.singletonCImpl = singletonCImpl;
    }

    @Override
    public ServiceCBuilder service(Service service) {
      this.service = Preconditions.checkNotNull(service);
      return this;
    }

    @Override
    public BlazePlayerApp_HiltComponents.ServiceC build() {
      Preconditions.checkBuilderRequirement(service, Service.class);
      return new ServiceCImpl(singletonCImpl, service);
    }
  }

  private static final class ViewWithFragmentCImpl extends BlazePlayerApp_HiltComponents.ViewWithFragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl;

    private final ViewWithFragmentCImpl viewWithFragmentCImpl = this;

    ViewWithFragmentCImpl(SingletonCImpl singletonCImpl,
        ActivityRetainedCImpl activityRetainedCImpl, ActivityCImpl activityCImpl,
        FragmentCImpl fragmentCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;
      this.fragmentCImpl = fragmentCImpl;


    }
  }

  private static final class FragmentCImpl extends BlazePlayerApp_HiltComponents.FragmentC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final FragmentCImpl fragmentCImpl = this;

    FragmentCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl, Fragment fragmentParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return activityCImpl.getHiltInternalFactoryFactory();
    }

    @Override
    public ViewWithFragmentComponentBuilder viewWithFragmentComponentBuilder() {
      return new ViewWithFragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl, fragmentCImpl);
    }

    @Override
    public void injectBrowserFragment(BrowserFragment arg0) {
    }

    @Override
    public void injectHomeFragment(HomeFragment arg0) {
    }

    @Override
    public void injectNetworkSharesFragment(NetworkSharesFragment arg0) {
    }

    @Override
    public void injectPaywallFragment(PaywallFragment arg0) {
    }

    @Override
    public void injectAudioPlayerFragment(AudioPlayerFragment arg0) {
      injectAudioPlayerFragment2(arg0);
    }

    @Override
    public void injectSettingsFragment(SettingsFragment arg0) {
    }

    @CanIgnoreReturnValue
    private AudioPlayerFragment injectAudioPlayerFragment2(AudioPlayerFragment instance) {
      AudioPlayerFragment_MembersInjector.injectMediaRepository(instance, singletonCImpl.mediaRepositoryProvider.get());
      return instance;
    }
  }

  private static final class ViewCImpl extends BlazePlayerApp_HiltComponents.ViewC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl;

    private final ViewCImpl viewCImpl = this;

    ViewCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        ActivityCImpl activityCImpl, View viewParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;
      this.activityCImpl = activityCImpl;


    }
  }

  private static final class ActivityCImpl extends BlazePlayerApp_HiltComponents.ActivityC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ActivityCImpl activityCImpl = this;

    ActivityCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        Activity activityParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;


    }

    ImmutableMap keySetMapOfClassOfAndBooleanBuilder() {
      ImmutableMap.Builder mapBuilder = ImmutableMap.<String, Boolean>builderWithExpectedSize(7);
      mapBuilder.put(AuthViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, AuthViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(BrowserViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, BrowserViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(HomeViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, HomeViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(MiniPlayerViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, MiniPlayerViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(NetworkSharesViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, NetworkSharesViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(PaywallViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, PaywallViewModel_HiltModules.KeyModule.provide());
      mapBuilder.put(SettingsViewModel_HiltModules_KeyModule_Provide_LazyMapKey.lazyClassKeyName, SettingsViewModel_HiltModules.KeyModule.provide());
      return mapBuilder.build();
    }

    @Override
    public DefaultViewModelFactories.InternalFactoryFactory getHiltInternalFactoryFactory() {
      return DefaultViewModelFactories_InternalFactoryFactory_Factory.newInstance(getViewModelKeys(), new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl));
    }

    @Override
    public Map<Class<?>, Boolean> getViewModelKeys() {
      return LazyClassKeyMap.<Boolean>of(keySetMapOfClassOfAndBooleanBuilder());
    }

    @Override
    public ViewModelComponentBuilder getViewModelComponentBuilder() {
      return new ViewModelCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public FragmentComponentBuilder fragmentComponentBuilder() {
      return new FragmentCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }

    @Override
    public ViewComponentBuilder viewComponentBuilder() {
      return new ViewCBuilder(singletonCImpl, activityRetainedCImpl, activityCImpl);
    }

    @Override
    public void injectMainActivity(MainActivity arg0) {
    }

    @Override
    public void injectAudioBrowserActivity(AudioBrowserActivity arg0) {
      injectAudioBrowserActivity2(arg0);
    }

    @Override
    public void injectNetworkVideoBrowserActivity(NetworkVideoBrowserActivity arg0) {
      injectNetworkVideoBrowserActivity2(arg0);
    }

    @Override
    public void injectPlayerActivity(PlayerActivity arg0) {
      injectPlayerActivity2(arg0);
    }

    @CanIgnoreReturnValue
    private AudioBrowserActivity injectAudioBrowserActivity2(AudioBrowserActivity instance) {
      AudioBrowserActivity_MembersInjector.injectNetworkRepository(instance, singletonCImpl.provideNetworkRepositoryProvider.get());
      AudioBrowserActivity_MembersInjector.injectSmbBrowser(instance, singletonCImpl.provideSmbBrowserProvider.get());
      return instance;
    }

    @CanIgnoreReturnValue
    private NetworkVideoBrowserActivity injectNetworkVideoBrowserActivity2(
        NetworkVideoBrowserActivity instance2) {
      NetworkVideoBrowserActivity_MembersInjector.injectSmbBrowser(instance2, singletonCImpl.provideSmbBrowserProvider.get());
      NetworkVideoBrowserActivity_MembersInjector.injectNetworkRepository(instance2, singletonCImpl.provideNetworkRepositoryProvider.get());
      return instance2;
    }

    @CanIgnoreReturnValue
    private PlayerActivity injectPlayerActivity2(PlayerActivity instance3) {
      PlayerActivity_MembersInjector.injectDataStore(instance3, singletonCImpl.provideDataStoreProvider.get());
      PlayerActivity_MembersInjector.injectMediaRepository(instance3, singletonCImpl.mediaRepositoryProvider.get());
      return instance3;
    }
  }

  private static final class ViewModelCImpl extends BlazePlayerApp_HiltComponents.ViewModelC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl;

    private final ViewModelCImpl viewModelCImpl = this;

    Provider<AuthViewModel> authViewModelProvider;

    Provider<BrowserViewModel> browserViewModelProvider;

    Provider<HomeViewModel> homeViewModelProvider;

    Provider<MiniPlayerViewModel> miniPlayerViewModelProvider;

    Provider<NetworkSharesViewModel> networkSharesViewModelProvider;

    Provider<PaywallViewModel> paywallViewModelProvider;

    Provider<SettingsViewModel> settingsViewModelProvider;

    ViewModelCImpl(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
        SavedStateHandle savedStateHandleParam, ViewModelLifecycle viewModelLifecycleParam) {
      this.singletonCImpl = singletonCImpl;
      this.activityRetainedCImpl = activityRetainedCImpl;

      initialize(savedStateHandleParam, viewModelLifecycleParam);

    }

    ImmutableMap hiltViewModelMapMapOfClassOfAndProviderOfViewModelBuilder() {
      ImmutableMap.Builder mapBuilder = ImmutableMap.<String, javax.inject.Provider<ViewModel>>builderWithExpectedSize(7);
      mapBuilder.put(AuthViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (authViewModelProvider)));
      mapBuilder.put(BrowserViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (browserViewModelProvider)));
      mapBuilder.put(HomeViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (homeViewModelProvider)));
      mapBuilder.put(MiniPlayerViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (miniPlayerViewModelProvider)));
      mapBuilder.put(NetworkSharesViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (networkSharesViewModelProvider)));
      mapBuilder.put(PaywallViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (paywallViewModelProvider)));
      mapBuilder.put(SettingsViewModel_HiltModules_BindsModule_Binds_LazyMapKey.lazyClassKeyName, ((Provider) (settingsViewModelProvider)));
      return mapBuilder.build();
    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandle savedStateHandleParam,
        final ViewModelLifecycle viewModelLifecycleParam) {
      this.authViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 0);
      this.browserViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 1);
      this.homeViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 2);
      this.miniPlayerViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 3);
      this.networkSharesViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 4);
      this.paywallViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 5);
      this.settingsViewModelProvider = new SwitchingProvider<>(singletonCImpl, activityRetainedCImpl, viewModelCImpl, 6);
    }

    @Override
    public Map<Class<?>, javax.inject.Provider<ViewModel>> getHiltViewModelMap() {
      return LazyClassKeyMap.<javax.inject.Provider<ViewModel>>of(hiltViewModelMapMapOfClassOfAndProviderOfViewModelBuilder());
    }

    @Override
    public Map<Class<?>, Object> getHiltViewModelAssistedMap() {
      return ImmutableMap.<Class<?>, Object>of();
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final ViewModelCImpl viewModelCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          ViewModelCImpl viewModelCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.viewModelCImpl = viewModelCImpl;
        this.id = id;
      }

      @Override
      @SuppressWarnings("unchecked")
      public T get() {
        switch (id) {
          case 0: // fr.retrospare.blazeplayer.auth.AuthViewModel
          return (T) new AuthViewModel();

          case 1: // fr.retrospare.blazeplayer.browser.BrowserViewModel
          return (T) new BrowserViewModel(singletonCImpl.provideDataStoreProvider.get(), ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.mediaRepositoryProvider.get(), singletonCImpl.provideSmbBrowserProvider.get(), singletonCImpl.provideNetworkRepositoryProvider.get());

          case 2: // fr.retrospare.blazeplayer.home.HomeViewModel
          return (T) new HomeViewModel(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.mediaRepositoryProvider.get());

          case 3: // fr.retrospare.blazeplayer.player.MiniPlayerViewModel
          return (T) new MiniPlayerViewModel(ApplicationContextModule_ProvideApplicationFactory.provideApplication(singletonCImpl.applicationContextModule), singletonCImpl.provideDataStoreProvider.get());

          case 4: // fr.retrospare.blazeplayer.network.NetworkSharesViewModel
          return (T) new NetworkSharesViewModel(singletonCImpl.provideNetworkRepositoryProvider.get(), singletonCImpl.networkScannerProvider.get());

          case 5: // fr.retrospare.blazeplayer.paywall.PaywallViewModel
          return (T) new PaywallViewModel(singletonCImpl.userRepositoryProvider.get());

          case 6: // fr.retrospare.blazeplayer.settings.SettingsViewModel
          return (T) new SettingsViewModel(singletonCImpl.provideDataStoreProvider.get(), singletonCImpl.mediaRepositoryProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ActivityRetainedCImpl extends BlazePlayerApp_HiltComponents.ActivityRetainedC {
    private final SingletonCImpl singletonCImpl;

    private final ActivityRetainedCImpl activityRetainedCImpl = this;

    Provider<ActivityRetainedLifecycle> provideActivityRetainedLifecycleProvider;

    ActivityRetainedCImpl(SingletonCImpl singletonCImpl,
        SavedStateHandleHolder savedStateHandleHolderParam) {
      this.singletonCImpl = singletonCImpl;

      initialize(savedStateHandleHolderParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final SavedStateHandleHolder savedStateHandleHolderParam) {
      this.provideActivityRetainedLifecycleProvider = DoubleCheck.provider(new SwitchingProvider<ActivityRetainedLifecycle>(singletonCImpl, activityRetainedCImpl, 0));
    }

    @Override
    public ActivityComponentBuilder activityComponentBuilder() {
      return new ActivityCBuilder(singletonCImpl, activityRetainedCImpl);
    }

    @Override
    public ActivityRetainedLifecycle getActivityRetainedLifecycle() {
      return provideActivityRetainedLifecycleProvider.get();
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final ActivityRetainedCImpl activityRetainedCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, ActivityRetainedCImpl activityRetainedCImpl,
          int id) {
        this.singletonCImpl = singletonCImpl;
        this.activityRetainedCImpl = activityRetainedCImpl;
        this.id = id;
      }

      @Override
      @SuppressWarnings("unchecked")
      public T get() {
        switch (id) {
          case 0: // dagger.hilt.android.ActivityRetainedLifecycle
          return (T) ActivityRetainedComponentManager_LifecycleModule_ProvideActivityRetainedLifecycleFactory.provideActivityRetainedLifecycle();

          default: throw new AssertionError(id);
        }
      }
    }
  }

  private static final class ServiceCImpl extends BlazePlayerApp_HiltComponents.ServiceC {
    private final SingletonCImpl singletonCImpl;

    private final ServiceCImpl serviceCImpl = this;

    ServiceCImpl(SingletonCImpl singletonCImpl, Service serviceParam) {
      this.singletonCImpl = singletonCImpl;


    }
  }

  private static final class SingletonCImpl extends BlazePlayerApp_HiltComponents.SingletonC {
    private final ApplicationContextModule applicationContextModule;

    private final SingletonCImpl singletonCImpl = this;

    Provider<DataStore<Preferences>> provideDataStoreProvider;

    Provider<NetworkRepository> provideNetworkRepositoryProvider;

    Provider<SmbBrowser> provideSmbBrowserProvider;

    Provider<MediaRepository> mediaRepositoryProvider;

    Provider<NetworkScanner> networkScannerProvider;

    Provider<UserRepository> userRepositoryProvider;

    SingletonCImpl(ApplicationContextModule applicationContextModuleParam) {
      this.applicationContextModule = applicationContextModuleParam;
      initialize(applicationContextModuleParam);

    }

    @SuppressWarnings("unchecked")
    private void initialize(final ApplicationContextModule applicationContextModuleParam) {
      this.provideDataStoreProvider = DoubleCheck.provider(new SwitchingProvider<DataStore<Preferences>>(singletonCImpl, 1));
      this.provideNetworkRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<NetworkRepository>(singletonCImpl, 0));
      this.provideSmbBrowserProvider = DoubleCheck.provider(new SwitchingProvider<SmbBrowser>(singletonCImpl, 2));
      this.mediaRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<MediaRepository>(singletonCImpl, 3));
      this.networkScannerProvider = DoubleCheck.provider(new SwitchingProvider<NetworkScanner>(singletonCImpl, 4));
      this.userRepositoryProvider = DoubleCheck.provider(new SwitchingProvider<UserRepository>(singletonCImpl, 5));
    }

    @Override
    public Set<Boolean> getDisableFragmentGetContextFix() {
      return ImmutableSet.<Boolean>of();
    }

    @Override
    public ActivityRetainedComponentBuilder retainedComponentBuilder() {
      return new ActivityRetainedCBuilder(singletonCImpl);
    }

    @Override
    public ServiceComponentBuilder serviceComponentBuilder() {
      return new ServiceCBuilder(singletonCImpl);
    }

    @Override
    public void injectBlazePlayerApp(BlazePlayerApp arg0) {
    }

    private static final class SwitchingProvider<T> implements Provider<T> {
      private final SingletonCImpl singletonCImpl;

      private final int id;

      SwitchingProvider(SingletonCImpl singletonCImpl, int id) {
        this.singletonCImpl = singletonCImpl;
        this.id = id;
      }

      @Override
      @SuppressWarnings("unchecked")
      public T get() {
        switch (id) {
          case 0: // fr.retrospare.blazeplayer.data.repository.NetworkRepository
          return (T) NetworkModule_ProvideNetworkRepositoryFactory.provideNetworkRepository(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.provideDataStoreProvider.get());

          case 1: // androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
          return (T) AppModule_ProvideDataStoreFactory.provideDataStore(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule));

          case 2: // fr.retrospare.blazeplayer.network.SmbBrowser
          return (T) NetworkModule_ProvideSmbBrowserFactory.provideSmbBrowser();

          case 3: // fr.retrospare.blazeplayer.data.repository.MediaRepository
          return (T) new MediaRepository(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.provideDataStoreProvider.get());

          case 4: // fr.retrospare.blazeplayer.network.NetworkScanner
          return (T) new NetworkScanner();

          case 5: // fr.retrospare.blazeplayer.data.repository.UserRepository
          return (T) new UserRepository(ApplicationContextModule_ProvideContextFactory.provideContext(singletonCImpl.applicationContextModule), singletonCImpl.provideDataStoreProvider.get());

          default: throw new AssertionError(id);
        }
      }
    }
  }
}
