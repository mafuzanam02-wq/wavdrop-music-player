package com.launchpoint.wavdrop.data.model

data class FolderSummary(
    val folderKey: String,
    val displayName: String,
    val songCount: Int,
    val totalDurationMs: Long,
)
