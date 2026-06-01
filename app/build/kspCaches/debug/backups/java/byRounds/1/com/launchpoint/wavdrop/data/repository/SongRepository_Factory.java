package com.launchpoint.wavdrop.data.repository;

import com.launchpoint.wavdrop.data.local.dao.SongDao;
import com.launchpoint.wavdrop.data.mediastore.MediaStoreScanner;
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
public final class SongRepository_Factory implements Factory<SongRepository> {
  private final Provider<SongDao> daoProvider;

  private final Provider<MediaStoreScanner> scannerProvider;

  public SongRepository_Factory(Provider<SongDao> daoProvider,
      Provider<MediaStoreScanner> scannerProvider) {
    this.daoProvider = daoProvider;
    this.scannerProvider = scannerProvider;
  }

  @Override
  public SongRepository get() {
    return newInstance(daoProvider.get(), scannerProvider.get());
  }

  public static SongRepository_Factory create(Provider<SongDao> daoProvider,
      Provider<MediaStoreScanner> scannerProvider) {
    return new SongRepository_Factory(daoProvider, scannerProvider);
  }

  public static SongRepository newInstance(SongDao dao, MediaStoreScanner scanner) {
    return new SongRepository(dao, scanner);
  }
}
