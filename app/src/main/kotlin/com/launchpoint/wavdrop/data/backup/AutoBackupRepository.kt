package com.launchpoint.wavdrop.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
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
     * Uses a [listFiles()] scan instead of [findFile()] because [DocumentFile.findFile]
     * is unreliable on many SAF providers — it can return null even when the file exists,
     * causing [createFile] to be called again. The provider then appends " (1)" to the new
     * filename, creating duplicates. The [listFiles()] scan bypasses the provider's
     * name-lookup path and directly inspects the directory listing.
     *
     * Writes via [openOutputStream] with mode "wt" (truncate-and-write). Falls back to
     * mode "w" if "wt" is not supported by the provider.
     */
    private suspend fun writeJsonToFolder(folder: DocumentFile, fileName: String): Boolean {
        val backupFileMode = appSettingsRepository.backupFileMode.first()
        Log.d(TAG, "writeJsonToFolder: mode=$backupFileMode fileName=$fileName folderUri=${folder.uri}")

        val existing = findExistingChildByName(folder, fileName)
        Log.d(TAG, "writeJsonToFolder: existingFile=${existing?.uri}")

        val target = if (existing != null) {
            existing
        } else {
            Log.d(TAG, "writeJsonToFolder: file not found — calling createFile")
            folder.createFile("application/json", fileName) ?: return false
        }
        Log.d(TAG, "writeJsonToFolder: targetUri=${target.uri}")

        val json = backupRepository.buildBackupJson()

        val wrote = tryWriteStream(target, json, "wt")
            ?: tryWriteStream(target, json, "w")

        if (wrote == null) {
            Log.e(TAG, "writeJsonToFolder: openOutputStream failed with both 'wt' and 'w'")
            return false
        }

        Log.d(TAG, "writeJsonToFolder: write succeeded")
        return true
    }

    /**
     * Scans [folder]'s direct children via [DocumentFile.listFiles] and returns the
     * first child whose [DocumentFile.getName] matches [name] exactly, or null if not found.
     *
     * This is more reliable than [DocumentFile.findFile], which delegates to the SAF
     * provider's COLUMN_DISPLAY_NAME query — a query that some providers answer incorrectly.
     */
    private fun findExistingChildByName(folder: DocumentFile, name: String): DocumentFile? =
        folder.listFiles().firstOrNull { it.name == name }

    /**
     * Attempts to open an output stream for [target] using [mode] and write [json] to it.
     * Returns [Unit] on success, or null if [openOutputStream] returns null or throws.
     */
    private fun tryWriteStream(target: DocumentFile, json: String, mode: String): Unit? {
        return runCatching {
            context.contentResolver.openOutputStream(target.uri, mode)?.use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "WavdropBackup"
        const val FOLDER_UNAVAILABLE_MSG =
            "Automatic backup couldn't access the selected folder. Choose a backup folder again."
    }
}
