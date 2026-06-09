package com.launchpoint.wavdrop.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import com.launchpoint.wavdrop.data.settings.BackupFileMode
import dagger.hilt.android.qualifiers.ApplicationContext
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
        /** Backup JSON generation or write failed. */
        data class Failure(val message: String) : Result
    }

    /**
     * Runs a backup only if the selected interval is due.
     * Call once per app session from the root navigation layer.
     */
    suspend fun runIfDue(): Result = withContext(Dispatchers.IO) {
        val interval = appSettingsRepository.autoBackupInterval.first()
        if (interval == AutoBackupInterval.OFF) return@withContext Result.Skipped

        val folderUriString = appSettingsRepository.autoBackupFolderUri.first()
            ?: return@withContext Result.NoFolderSelected

        val lastBackupAt = appSettingsRepository.lastAutoBackupAtMillis.first()
        val nowMs        = System.currentTimeMillis()

        if (nowMs - lastBackupAt < interval.toMillis()) return@withContext Result.NotDue

        val result = performBackup(folderUriString)
        if (result is Result.Success) {
            appSettingsRepository.setLastAutoBackupAtMillis(nowMs)
        }
        result
    }

    /**
     * Runs a backup immediately, ignoring the interval. Used by the "Back up now" button.
     * Requires a folder to be selected; returns [Result.NoFolderSelected] otherwise.
     */
    suspend fun runNow(): Result = withContext(Dispatchers.IO) {
        val folderUriString = appSettingsRepository.autoBackupFolderUri.first()
            ?: return@withContext Result.NoFolderSelected
        val result = performBackup(folderUriString)
        if (result is Result.Success) {
            appSettingsRepository.setLastAutoBackupAtMillis(System.currentTimeMillis())
        }
        result
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
                BackupFileMode.DATED            -> "wavdrop-backup-${LocalDate.now()}.json"
                BackupFileMode.REPLACE_PREVIOUS -> "wavdrop-backup.json"
            }

            val success = writeJsonToFolder(folder, fileName)
            if (success) Result.Success
            else Result.Failure("Could not write backup file to the selected folder.")
        } catch (e: SecurityException) {
            Result.FolderUnavailable(FOLDER_UNAVAILABLE_MSG)
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Backup failed.")
        }
    }

    /**
     * Writes the current backup JSON into [fileName] inside [folder].
     *
     * Uses [find-or-create + openOutputStream("wt")] so that an existing file is
     * overwritten in place rather than deleted and recreated. This prevents Android
     * from appending " (1)" to the filename when the provider refuses deletion.
     */
    private suspend fun writeJsonToFolder(folder: DocumentFile, fileName: String): Boolean {
        val target = folder.findFile(fileName)
            ?: folder.createFile("application/json", fileName)
            ?: return false

        val json = backupRepository.buildBackupJson()
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
        } ?: return false

        return true
    }

    private companion object {
        const val FOLDER_UNAVAILABLE_MSG =
            "Automatic backup couldn't access the selected folder. Choose a backup folder again."
    }
}
