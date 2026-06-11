package com.launchpoint.wavdrop.data.backup

object BackupRestoreWarnings {
    const val SETTINGS_PARTIAL =
        "Some settings could not be restored. Your music data was restored."

    const val LIBRARY_FOLDERS_MAY_NEED_RESELECT =
        "Some library folders may need to be selected again on this device."

    fun selectedFolderPermissionWarning(preferences: BackupPreferences?): String? {
        val hasSelectedFolders = preferences
            ?.selectedFolderUris
            ?.any { it.isNotBlank() }
            ?: false
        return if (hasSelectedFolders) LIBRARY_FOLDERS_MAY_NEED_RESELECT else null
    }
}
