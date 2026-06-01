package com.launchpoint.wavdrop.di;

import com.launchpoint.wavdrop.data.local.WavdropDatabase;
import com.launchpoint.wavdrop.data.local.dao.SongDao;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
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
public final class AppModule_ProvideSongDaoFactory implements Factory<SongDao> {
  private final Provider<WavdropDatabase> dbProvider;

  public AppModule_ProvideSongDaoFactory(Provider<WavdropDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public SongDao get() {
    return provideSongDao(dbProvider.get());
  }

  public static AppModule_ProvideSongDaoFactory create(Provider<WavdropDatabase> dbProvider) {
    return new AppModule_ProvideSongDaoFactory(dbProvider);
  }

  public static SongDao provideSongDao(WavdropDatabase db) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideSongDao(db));
  }
}
