package com.launchpoint.wavdrop.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class AutoBackupWorkScheduler @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val workManagerGateway: AutoBackupWorkManagerGateway,
) {
    suspend fun reconcile() {
        reconcile(appSettingsRepository.autoBackupInterval.first())
    }

    internal fun reconcile(interval: AutoBackupInterval) {
        if (interval == AutoBackupInterval.OFF) {
            workManagerGateway.cancelUniqueWork(UNIQUE_WORK_NAME)
        } else {
            workManagerGateway.enqueueUniquePeriodicWork(
                uniqueWorkName = UNIQUE_WORK_NAME,
                existingWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
                request = buildPeriodicRequest(),
            )
        }
    }

    private fun buildPeriodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<AutoBackupWorker>(
            CHECK_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()

    companion object {
        internal const val UNIQUE_WORK_NAME = "wavdrop_auto_backup_due_check"
        internal const val CHECK_INTERVAL_HOURS = 24L
    }
}

interface AutoBackupWorkManagerGateway {
    fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    )

    fun cancelUniqueWork(uniqueWorkName: String)
}

@Singleton
class WorkManagerAutoBackupGateway @Inject constructor(
    @ApplicationContext context: Context,
) : AutoBackupWorkManagerGateway {
    private val appContext = context.applicationContext

    override fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest,
    ) {
        WorkManager.getInstance(appContext)
            .enqueueUniquePeriodicWork(uniqueWorkName, existingWorkPolicy, request)
    }

    override fun cancelUniqueWork(uniqueWorkName: String) {
        WorkManager.getInstance(appContext).cancelUniqueWork(uniqueWorkName)
    }
}
