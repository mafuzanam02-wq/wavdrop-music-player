package com.launchpoint.wavdrop.data.backup

enum class VerificationStatus {
    /** Backup parsed cleanly and all core recoverable sections are present. */
    VERIFIED,
    /** Backup is valid but missing important non-fatal sections (see warnings). */
    PARTIAL,
    /** A backup file exists but is unreadable, corrupt, or not a Wavdrop backup. */
    FAILED,
    /** No backup folder is selected, the folder is unavailable, or it contains no backup file. */
    NO_BACKUP_FOUND,
}

data class BackupVerificationResult(
    val status: VerificationStatus,
    val fileName: String? = null,
    val fileSizeBytes: Long? = null,
    val backupTimestampMillis: Long? = null,
    val schemaVersion: Int? = null,
    val trackCount: Int = 0,
    val playlistCount: Int = 0,
    val favoriteCount: Int = 0,
    val statsCount: Int = 0,
    val eventCount: Int = 0,
    val lyricsCount: Int = 0,
    val hasPreferences: Boolean = false,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)
