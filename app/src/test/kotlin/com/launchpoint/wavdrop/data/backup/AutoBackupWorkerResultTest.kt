package com.launchpoint.wavdrop.data.backup

import androidx.work.ListenableWorker
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupWorkerResultTest {
    @Test
    fun `permanent configuration and not-due states complete successfully`() {
        val permanentResults = listOf(
            AutoBackupRepository.Result.Skipped,
            AutoBackupRepository.Result.NoFolderSelected,
            AutoBackupRepository.Result.NotDue,
            AutoBackupRepository.Result.Success,
            AutoBackupRepository.Result.FolderUnavailable("folder unavailable"),
        )

        permanentResults.forEach { result ->
            assertTrue(result.toWorkerResult() is ListenableWorker.Result.Success)
        }
    }

    @Test
    fun `generic backup failures are retryable`() {
        assertTrue(
            AutoBackupRepository.Result.Failure("write failed").toWorkerResult()
                is ListenableWorker.Result.Retry,
        )
    }
}
