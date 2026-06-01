package com.launchpoint.wavdrop.ui.screen.home;

import com.launchpoint.wavdrop.data.repository.SongRepository;
import com.launchpoint.wavdrop.playback.PlayerController;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

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
    "deprecation"
})
public final class HomeViewModel_Factory implements Factory<HomeViewModel> {
  private final Provider<SongRepository> repositoryProvider;

  private final Provider<PlayerController> playerControllerProvider;

  public HomeViewModel_Factory(Provider<SongRepository> repositoryProvider,
      Provider<PlayerController> playerControllerProvider) {
    this.repositoryProvider = repositoryProvider;
    this.playerControllerProvider = playerControllerProvider;
  }

  @Override
  public HomeViewModel get() {
    return newInstance(repositoryProvider.get(), playerControllerProvider.get());
  }

  public static HomeViewModel_Factory create(Provider<SongRepository> repositoryProvider,
      Provider<PlayerController> playerControllerProvider) {
    return new HomeViewModel_Factory(repositoryProvider, playerControllerProvider);
  }

  public static HomeViewModel newInstance(SongRepository repository,
      PlayerController playerController) {
    return new HomeViewModel(repository, playerController);
  }
}
