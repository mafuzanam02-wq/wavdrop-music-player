package com.launchpoint.wavdrop.data.backup

object BackupSaveValidator {
    const val VALIDATION_FAILED_MESSAGE =
        "Backup could not be verified after saving. Try a different location."

    fun isSavedBackupValid(content: String?): Boolean {
        if (content.isNullOrBlank()) return false
        return WavdropBackupParser.parse(content).backup != null
    }
}
