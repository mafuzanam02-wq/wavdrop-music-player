package com.launchpoint.wavdrop.data.backup

data class WavdropBackupImportResult(
    val backup: WavdropBackup?,
    val error: String?,
    val integrityStatus: BackupIntegrityStatus,
    /**
     * Non-fatal warnings from parsing — currently used to surface unknown optional
     * capabilities declared by a v2 backup. Does not block restore.
     */
    val warnings: List<String> = emptyList(),
)
