package com.launchpoint.wavdrop.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Read-only health check of the latest Wavdrop backup in the selected backup folder.
 *
 * Answers "if I lose my phone right now, can I recover my Wavdrop data?" by locating the
 * most recent backup file, parsing it with the same parser used by restore, and summarising
 * what it contains. Never throws — every failure mode maps to a [BackupVerificationResult].
 */
@Singleton
class BackupVerificationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettingsRepository: AppSettingsRepository,
) {

    suspend fun verifyLatestBackup(): BackupVerificationResult = withContext(Dispatchers.IO) {
        runCatching { verify() }.getOrElse { e ->
            Log.e(TAG, "Backup verification failed unexpectedly", e)
            BackupVerificationResult(
                status = VerificationStatus.FAILED,
                errors = listOf("The backup could not be checked. Try verifying again."),
            )
        }
    }

    private suspend fun verify(): BackupVerificationResult {
        val folderUriString = appSettingsRepository.autoBackupFolderUri.first()
            ?: return BackupVerificationResult(
                status   = VerificationStatus.NO_BACKUP_FOUND,
                warnings = listOf("No backup folder is selected. Choose a folder so Wavdrop can save backups."),
            )

        val folder = runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(folderUriString))
        }.getOrNull()
        if (folder == null || !folder.canRead()) {
            return BackupVerificationResult(
                status   = VerificationStatus.NO_BACKUP_FOUND,
                warnings = listOf("The backup folder is unavailable. Choose a backup folder again."),
            )
        }

        val latest = runCatching {
            folder.listFiles()
                .filter { it.isFile && isBackupFileName(it.name) }
                .maxByOrNull { it.lastModified() }
        }.getOrNull()
            ?: return BackupVerificationResult(
                status   = VerificationStatus.NO_BACKUP_FOUND,
                warnings = listOf("No backup file was found in the selected folder. Use Back Up Now to create one."),
            )

        val fileName  = latest.name
        val fileSize  = latest.length().takeIf { it > 0 }
        val fileTime  = latest.lastModified().takeIf { it > 0 }

        // Content decides the parser, never the file name: legacy JSON and WDBK go through the same unified reader
        // that real restore uses.
        val parseResult = when (val doc = WavdropBackupDocumentReader.readUri(context, latest.uri)) {
            is BackupDocumentReadResult.Rejected -> return failed(
                fileName, fileSize, fileTime,
                when (doc.reason) {
                    BackupRejectReason.TOO_LARGE -> BackupInputReader.TOO_LARGE_MESSAGE
                    BackupRejectReason.UNREADABLE -> "The backup file could not be read."
                    BackupRejectReason.EMPTY -> "The backup file is empty."
                    BackupRejectReason.NOT_A_BACKUP -> "This file does not look like a Wavdrop backup."
                },
            )
            // A Desktop JSON file is not an Android backup; the Android parser rejects it as before.
            is BackupDocumentReadResult.DesktopJson -> WavdropBackupParser.parse(doc.text)
            is BackupDocumentReadResult.Wavdrop -> doc.result
        }
        val backup = parseResult.backup
            ?: return failed(fileName, fileSize, fileTime, verificationMessageFor(parseResult.error))

        return summarise(backup, fileName, fileSize, fileTime)
    }

    /** Maps parser errors to plain-language verification messages. */
    private fun verificationMessageFor(error: String?): String = when {
        error == null -> "The backup file could not be read."
        error.startsWith("Unsupported backup version") || error == WavdropBackupParser.NEWER_VERSION_ERROR ->
            "This backup was created by a newer version of Wavdrop. Update the app to use it."
        error.startsWith("Backup integrity check failed") -> WavdropBackupParser.INTEGRITY_ERROR
        error == BackupInputReader.TOO_LARGE_MESSAGE -> BackupInputReader.TOO_LARGE_MESSAGE
        else -> "The backup file is damaged and cannot be restored."
    }

    private fun summarise(
        backup: WavdropBackup,
        fileName: String?,
        fileSizeBytes: Long?,
        fileModifiedMillis: Long?,
    ): BackupVerificationResult {
        val warnings = mutableListOf<String>()

        // Embedded export time wins; the file's modified time is only an explicitly flagged fallback (never the export date).
        val embedded = BackupPresentation.exportTimestampMillis(backup)
        val timestamp = embedded ?: fileModifiedMillis
        if (timestamp == null) {
            warnings += "The backup has no readable date, so its age cannot be checked."
        }

        if (backup.songs.isEmpty()) {
            warnings += "The backup contains no tracks. Your library details may not be recoverable."
        }
        if (backup.trackStats.isEmpty() && backup.songs.isNotEmpty()) {
            warnings += "The backup contains no play statistics."
        }
        if (backup.listenEvents.isEmpty()) {
            warnings += "Listening history is missing. Monthly Reports and Wrapped cannot be rebuilt from this backup."
        }
        if (backup.preferences == null) {
            warnings += "App settings are not included in this backup."
        }
        // Only genuine v1 backups are "older"; v2 JSON and WDBK are integrity-verified by their own mechanisms.
        BackupPresentation.legacyFormatWarning(backup)?.let { warnings += it }

        val coreSectionsPresent =
            backup.songs.isNotEmpty() &&
                backup.trackStats.isNotEmpty() &&
                backup.listenEvents.isNotEmpty() &&
                timestamp != null

        return BackupVerificationResult(
            status                = if (coreSectionsPresent) VerificationStatus.VERIFIED else VerificationStatus.PARTIAL,
            fileName              = fileName,
            fileSizeBytes         = fileSizeBytes,
            backupTimestampMillis = timestamp,
            timestampIsFileTime   = embedded == null && timestamp != null,
            schemaVersion        = WavdropBackupParser.SUPPORTED_VERSION,
            trackCount            = backup.songs.size,
            playlistCount         = backup.playlists.size,
            favoriteCount         = backup.trackStats.count { it.isFavorite },
            statsCount            = backup.trackStats.size,
            eventCount            = backup.listenEvents.size,
            lyricsCount           = backup.lyricsOverrides.size,
            hasPreferences        = backup.preferences != null,
            warnings              = warnings,
        )
    }

    private fun failed(
        fileName: String?,
        fileSizeBytes: Long?,
        fileTimeMillis: Long?,
        message: String,
    ) = BackupVerificationResult(
        status                = VerificationStatus.FAILED,
        fileName              = fileName,
        fileSizeBytes         = fileSizeBytes,
        backupTimestampMillis = fileTimeMillis,
        errors                = listOf(message),
    )

    internal companion object {
        private const val TAG = "WavdropBackup"

        internal fun isBackupFileName(name: String?): Boolean {
            val lower = name?.lowercase() ?: return false
            // Accepts the current ".wdbk" container and legacy ".json" backups (the extension only selects CANDIDATES;
            // the unified reader decides by content). ".tmp" exclusion: auto-backup writes a temp file alongside the real backup;
            // a leftover temp must never be verified as the latest backup.
            return lower.startsWith("wavdrop-backup") &&
                (lower.endsWith(".wdbk") || lower.endsWith(".json")) &&
                !lower.contains(".tmp")
        }

    }
}
