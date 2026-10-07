package com.launchpoint.wavdrop.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.AutoBackupCheckResult
import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import com.launchpoint.wavdrop.data.settings.BackupFileMode
import com.launchpoint.wavdrop.data.backup.wdbk.DocumentFileBackupFolder
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkBackupSaver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class AutoBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettingsRepository: AppSettingsRepository,
    private val backupRepository: WavdropBackupRepository,
    private val backupExecutionSerializer: BackupExecutionSerializer,
) {
    sealed interface Result {
        /** Interval is OFF — nothing to do. */
        data object Skipped : Result
        /** No backup folder has been selected. */
        data object NoFolderSelected : Result
        /** Interval is not yet due. */
        data object NotDue : Result
        /** Backup was created successfully. */
        data object Success : Result
        /** The folder permission was revoked or the folder is unavailable. */
        data class FolderUnavailable(val message: String) : Result
        /** Backup generation, write or verification failed. */
        data class Failure(val message: String) : Result
    }

    /**
     * Runs a backup only if the selected interval is due.
     * Used by durable background work and any explicit due-check callers.
     */
    suspend fun runIfDue(): Result = withContext(Dispatchers.IO) {
        backupExecutionSerializer.withSerializedBackup {
            runIfDueSerialized()
        }
    }

    private suspend fun runIfDueSerialized(): Result {
        val nowMs = System.currentTimeMillis()
        val interval = appSettingsRepository.autoBackupInterval.first()
        if (interval == AutoBackupInterval.OFF) {
            appSettingsRepository.setLastAutoBackupCheck(nowMs, AutoBackupCheckResult.OFF)
            return Result.Skipped
        }

        val folderUriString = appSettingsRepository.autoBackupFolderUri.first()
        if (folderUriString == null) {
            appSettingsRepository.setLastAutoBackupCheck(nowMs, AutoBackupCheckResult.NO_FOLDER_SELECTED)
            return Result.NoFolderSelected
        }

        val lastBackupAt = appSettingsRepository.lastAutoBackupAtMillis.first()

        if (!AutoBackupDueRules.shouldRun(interval, lastBackupAt, nowMs)) {
            appSettingsRepository.setLastAutoBackupCheck(nowMs, AutoBackupCheckResult.NOT_DUE)
            return Result.NotDue
        }

        val result = performBackup(folderUriString)
        AutoBackupDueRules.successfulBackupTimestamp(result, nowMs)?.let { timestamp ->
            appSettingsRepository.setLastAutoBackupAtMillis(timestamp)
        }
        appSettingsRepository.setLastAutoBackupCheck(nowMs, result.toAutoBackupCheckResult())
        return result
    }

    /**
     * Runs a backup immediately, ignoring the interval. Used by the "Back up now" button.
     * Requires a folder to be selected; returns [Result.NoFolderSelected] otherwise.
     */
    suspend fun runNow(): Result = withContext(Dispatchers.IO) {
        backupExecutionSerializer.withSerializedBackup {
            val folderUriString = appSettingsRepository.autoBackupFolderUri.first()
                ?: return@withSerializedBackup Result.NoFolderSelected
            val nowMs = System.currentTimeMillis()
            val result = performBackup(folderUriString)
            AutoBackupDueRules.successfulBackupTimestamp(result, nowMs)?.let { timestamp ->
                appSettingsRepository.setLastAutoBackupAtMillis(timestamp)
            }
            result
        }
    }

    private suspend fun performBackup(folderUriString: String): Result {
        return try {
            val treeUri = Uri.parse(folderUriString)
            val folder  = DocumentFile.fromTreeUri(context, treeUri)
                ?: return Result.FolderUnavailable(FOLDER_UNAVAILABLE_MSG)

            if (!folder.canWrite()) {
                return Result.FolderUnavailable(FOLDER_UNAVAILABLE_MSG)
            }

            val backupFileMode = appSettingsRepository.backupFileMode.first()
            val fileName = when (backupFileMode) {
                BackupFileMode.DATED            -> "wavdrop-backup-${LocalDate.now()}.wdbk"
                BackupFileMode.REPLACE_PREVIOUS -> "wavdrop-backup.wdbk"
            }

            val success = writeWdbkToFolder(folder, fileName)
            if (success) Result.Success
            else Result.Failure("Could not write backup file to the selected folder.")
        } catch (e: SecurityException) {
            Result.FolderUnavailable(FOLDER_UNAVAILABLE_MSG)
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Backup failed.")
        }
    }

    /**
     * Writes the current backup as a WDBK container into [fileName] inside [folder] using an
     * atomic-style temp-then-final flow ([WdbkBackupSaver.saveToFolderAndVerify]):
     *
     *  1. Stream the container to a temp file ([fileName] + ".tmp").
     *  2. Re-open the temp file and decode it with the production [com.launchpoint.wavdrop.data.backup.wdbk.WdbkReader]
     *     (per-entry SHA-256 + semantic fingerprint) — proves the container is complete BEFORE the previous
     *     backup is touched.
     *  3. Stream-copy those exact verified bytes into the final file (truncating mode) — the backup is never rebuilt,
     *     so the final file cannot differ in backupId/timestamp/content from what was verified — and verify the final
     *     file the same way.
     *  4. Delete the temp file.
     *
     * SAF's renameDocument is not reliable across providers (and rename-over-existing is not universally
     * supported), so step 3 is a truncating overwrite of the final file rather than a true rename. KNOWN RISK: a
     * crash or power loss during step 3 itself can still corrupt the final file — but the window is a single
     * already-verified copy instead of the entire build + write, and a failure in any earlier step leaves the
     * previous backup completely untouched.
     *
     * Success is returned only after the FINAL file decodes and matches, so lastAutoBackupAtMillis is never updated
     * for a corrupt backup.
     *
     * Child lookup scans the directory listing ([DocumentFileBackupFolder]) because [DocumentFile.findFile] is
     * unreliable on many SAF providers; a miss makes the provider append " (1)" and create duplicates.
     *
     * The temp name ends in ".tmp" (not ".wdbk"/".json") so Backup Verification's discovery never picks up a
     * leftover temp file.
     */
    private suspend fun writeWdbkToFolder(folder: DocumentFile, fileName: String): Boolean {
        val backupFileMode = appSettingsRepository.backupFileMode.first()
        // Contains the SAF destination URI and backup filename — gated so it never
        // executes outside debug, independent of R8 stripping.
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "writeWdbkToFolder: mode=$backupFileMode fileName=$fileName folderUri=${folder.uri}")
        }

        val snapshot = backupRepository.buildWdbkExportSnapshot()
        return try {
            WdbkBackupSaver().saveToFolderAndVerify(DocumentFileBackupFolder(context, folder), fileName, snapshot)
            Log.d(TAG, "writeWdbkToFolder: write succeeded and final file verified")
            true
        } catch (e: IOException) {
            Log.e(TAG, "writeWdbkToFolder: write/verification failed: ${e.message}")
            false
        }
    }

    private fun Result.toAutoBackupCheckResult(): AutoBackupCheckResult = when (this) {
        Result.Skipped              -> AutoBackupCheckResult.OFF
        Result.NoFolderSelected     -> AutoBackupCheckResult.NO_FOLDER_SELECTED
        Result.NotDue               -> AutoBackupCheckResult.NOT_DUE
        Result.Success              -> AutoBackupCheckResult.SUCCESS
        is Result.FolderUnavailable -> AutoBackupCheckResult.FOLDER_UNAVAILABLE
        is Result.Failure           -> AutoBackupCheckResult.FAILURE
    }

    private companion object {
        const val TAG = "WavdropBackup"
        const val FOLDER_UNAVAILABLE_MSG =
            "Automatic backup couldn't access the selected folder. Choose a backup folder again."
    }
}
