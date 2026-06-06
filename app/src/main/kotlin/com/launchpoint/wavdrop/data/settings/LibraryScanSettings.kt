package com.launchpoint.wavdrop.data.settings

data class LibraryScanSettings(
    val scanMode: LibraryScanMode = LibraryScanMode.WHOLE_DEVICE,
    val selectedFolderUris: List<String> = emptyList(),
    val minimumTrackDurationSeconds: Int = 30,
    val includeWhatsAppVoiceNotes: Boolean = false,
)
