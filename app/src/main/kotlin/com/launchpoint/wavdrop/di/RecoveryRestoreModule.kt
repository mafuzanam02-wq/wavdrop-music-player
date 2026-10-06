package com.launchpoint.wavdrop.di

import com.launchpoint.wavdrop.data.backup.RecoveryDatabaseApplier
import com.launchpoint.wavdrop.data.backup.RecoveryPreferenceApplier
import com.launchpoint.wavdrop.data.backup.RecoveryPreferenceApplierImpl
import com.launchpoint.wavdrop.data.backup.RecoveryRestoreRepository
import com.launchpoint.wavdrop.data.backup.RecoverySafetySnapshotService
import com.launchpoint.wavdrop.data.backup.SafetySnapshotProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface RecoveryRestoreModule {
    @Binds fun bindSafetySnapshotProvider(impl: RecoverySafetySnapshotService): SafetySnapshotProvider
    @Binds fun bindRecoveryDatabaseApplier(impl: RecoveryRestoreRepository): RecoveryDatabaseApplier
    @Binds fun bindRecoveryPreferenceApplier(impl: RecoveryPreferenceApplierImpl): RecoveryPreferenceApplier
}
