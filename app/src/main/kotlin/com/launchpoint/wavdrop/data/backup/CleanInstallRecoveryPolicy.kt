package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.settings.LibraryScanMode

object CleanInstallRecoveryPolicy {
    fun requiresFolderReselection(preferences: BackupPreferences?): Boolean =
        preferences?.scanMode == LibraryScanMode.SELECTED_FOLDERS.name ||
            preferences?.selectedFolderUris?.any { it.isNotBlank() } == true
}
