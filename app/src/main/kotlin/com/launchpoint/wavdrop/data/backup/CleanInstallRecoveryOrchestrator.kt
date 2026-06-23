package com.launchpoint.wavdrop.data.backup

object CleanInstallRecoveryOrchestrator {
    enum class NextStep {
        REQUEST_MUSIC_PERMISSION,
        REQUEST_FOLDER_SELECTION,
        SCAN_LIBRARY,
    }

    fun nextStep(
        hasMusicPermission: Boolean,
        needsFolderReselection: Boolean,
    ): NextStep = when {
        !hasMusicPermission -> NextStep.REQUEST_MUSIC_PERMISSION
        needsFolderReselection -> NextStep.REQUEST_FOLDER_SELECTION
        else -> NextStep.SCAN_LIBRARY
    }

    /**
     * The ordering guarantee that fixes the clean-install "import twice" bug:
     * the import apply callback cannot run until the scan callback has completed.
     */
    suspend fun <T> scanThenApply(
        scanLibrary: suspend () -> Unit,
        applyImport: suspend () -> T,
    ): T {
        scanLibrary()
        return applyImport()
    }

    val notRestoredOnThisDevice = listOf(
        "Equalizer",
        "Shuffle and repeat",
        "Queue and playback position",
        "Previous music-folder permissions",
        "Automatic-backup destination",
    )
}

object CleanInstallRecoveryUiText {
    const val SETTINGS_RESTORED = "Settings restored"
    const val FOLDERS_NEED_RESELECTION = "Music folders need to be reselected"
    const val SCAN_IN_PROGRESS = "Library scan in progress…"
    const val HISTORY_MERGED = "History merged with current library"
    const val HISTORY_PRESERVED = "History preserved for later matching"
    const val NOT_RESTORED = "Not restored on this device"
}
