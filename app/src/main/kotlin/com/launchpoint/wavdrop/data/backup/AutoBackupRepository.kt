package com.launchpoint.wavdrop.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.AutoBackupCheckResult
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
        val nowMs = System.currentTimeMillis()
        val interval = appSettingsRepository.autoBackupInterval.first()
        if (interval == AutoBackupInterval.OFF) {
            appSettingsRepository.setLastAutoBackupCheck(nowMs, AutoBackupCheckResult.OFF)
            return@withContext Result.Skipped
        }

        val folderUriString = appSettingsRepository.autoBackupFolderUri.first()
        if (folderUriString == null) {
            appSettingsRepository.setLastAutoBackupCheck(nowMs, AutoBackupCheckResult.NO_FOLDER_SELECTED)
            return@withContext Result.NoFolderSelected
        }

        val lastBackupAt = appSettingsRepository.lastAutoBackupAtMillis.first()

        if (nowMs - lastBackupAt < interval.toMillis()) {
            appSettingsRepository.setLastAutoBackupCheck(nowMs, AutoBackupCheckResult.NOT_DUE)
            return@withContext Result.NotDue
        }

        val result = performBackup(folderUriString)
        if (result is Result.Success) {
            appSettingsRepository.setLastAutoBackupAtMillis(nowMs)
        }
        appSettingsRepository.setLastAutoBackupCheck(nowMs, result.toAutoBackupCheckResult())
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
     * Writes the current backup JSON into [fileName] inside [folder] using an
     * atomic-style temp-then-final flow:
     *
     *  1. Write the JSON to a temp file ([fileName] + ".tmp").
     *  2. Read the temp file back and parse it with [WavdropBackupParser] — proves the
     *     JSON is complete and well-formed BEFORE the previous backup is touched.
     *  3. Write the final file with truncating mode and validate it the same way.
     *  4. Delete the temp file.
     *
     * SAF's renameDocument is not reliable across providers (and rename-over-existing
     * is not universally supported), so step 3 is a truncating overwrite of the final
     * file rather than a true rename. KNOWN RISK: a crash or power loss during step 3
     * itself can still corrupt the final file — but the window is now a single
     * validated-content write instead of the entire JSON build + write, and a failure
     * in any earlier step leaves the previous backup completely untouched.
     *
     * Success is returned only after the FINAL file parses successfully, so
     * lastAutoBackupAtMillis is never updated for a corrupt backup.
     *
     * Uses a [listFiles()] scan instead of [findFile()] because [DocumentFile.findFile]
     * is unreliable on many SAF providers — it can return null even when the file exists,
     * causing [createFile] to be called again. The provider then appends " (1)" to the new
     * filename, creating duplicates. The [listFiles()] scan bypasses the provider's
     * name-lookup path and directly inspects the directory listing.
     *
     * The temp name ends in ".tmp" (not ".json") so Backup Verification's
     * "wavdrop-backup*.json" discovery never picks up a leftover temp file.
     */
    private suspend fun writeJsonToFolder(folder: DocumentFile, fileName: String): Boolean {
        val backupFileMode = appSettingsRepository.backupFileMode.first()
        Log.d(TAG, "writeJsonToFolder: mode=$backupFileMode fileName=$fileName folderUri=${folder.uri}")

        val json = backupRepository.buildBackupJson()
        val tempName = "$fileName.tmp"

        // Stale temp from an earlier crashed run — remove before writing a fresh one.
        findExistingChildByName(folder, tempName)?.let { stale ->
            runCatching { stale.delete() }
        }

        // Step 1: write temp. Any failure here leaves the previous backup untouched.
        // octet-stream MIME so providers don't append ".json" to the temp name, which
        // would make it discoverable as a real backup.
        val temp = folder.createFile("application/octet-stream", tempName)
        if (temp == null) {
            Log.e(TAG, "writeJsonToFolder: could not create temp file")
            return false
        }
        if (!writeAndValidate(temp, json)) {
            Log.e(TAG, "writeJsonToFolder: temp write/validation failed — previous backup untouched")
            runCatching { temp.delete() }
            return false
        }

        // Step 3: write final with truncating mode, then validate it.
        val existing = findExistingChildByName(folder, fileName)
        val target = existing
            ?: folder.createFile("application/json", fileName)
        if (target == null) {
            Log.e(TAG, "writeJsonToFolder: could not create final file")
            runCatching { temp.delete() }
            return false
        }

        val finalOk = writeAndValidate(target, json)
        runCatching { temp.delete() }

        if (!finalOk) {
            Log.e(TAG, "writeJsonToFolder: final write/validation failed")
            return false
        }

        Log.d(TAG, "writeJsonToFolder: write succeeded and final file validated")
        return true
    }

    /**
     * Writes [json] to [target] ("wt" truncating mode, "w" fallback), reads the file
     * back, and confirms it parses as a valid Wavdrop backup. Returns false on any
     * write, read, or parse failure.
     */
    private fun writeAndValidate(target: DocumentFile, json: String): Boolean {
        val wrote = tryWriteStream(target, json, "wt")
            ?: tryWriteStream(target, json, "w")
        if (wrote == null) {
            Log.e(TAG, "writeAndValidate: openOutputStream failed with both 'wt' and 'w'")
            return false
        }

        val readBack = runCatching {
            context.contentResolver.openInputStream(target.uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }.getOrNull()
        if (readBack == null) {
            Log.e(TAG, "writeAndValidate: could not read file back for validation")
            return false
        }

        if (!BackupSaveValidator.isSavedBackupValid(readBack)) {
            Log.e(TAG, "writeAndValidate: written file failed saved-backup validation")
            return false
        }
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
