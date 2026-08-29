package com.launchpoint.wavdrop.data.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val autoBackupRepository: AutoBackupRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result =
        autoBackupRepository.runIfDue().toWorkerResult()
}

internal fun AutoBackupRepository.Result.toWorkerResult(): ListenableWorker.Result = when (this) {
    AutoBackupRepository.Result.Skipped,
    AutoBackupRepository.Result.NoFolderSelected,
    AutoBackupRepository.Result.NotDue,
    AutoBackupRepository.Result.Success,
    is AutoBackupRepository.Result.FolderUnavailable -> ListenableWorker.Result.success()
    is AutoBackupRepository.Result.Failure           -> ListenableWorker.Result.retry()
}
