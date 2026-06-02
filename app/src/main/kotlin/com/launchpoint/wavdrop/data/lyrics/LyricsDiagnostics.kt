package com.launchpoint.wavdrop.data.lyrics

enum class LyricsLookupStatus {
    FOUND,
    NOT_FOUND,
    ERROR,
}

data class LyricsDiagnostics(
    val embeddedMetadata: LyricsLookupStatus,
    val sameFolderLrc: LyricsLookupStatus,
    val sameFolderTxt: LyricsLookupStatus,
    val folderPathUsed: String?,
    val candidateFilenames: List<String>,
    val songContentUri: String,
)

data class LyricsLookupOutcome(
    val result: LyricsResult,
    val diagnostics: LyricsDiagnostics,
)

data class SidecarLyricsLookup(
    val result: LyricsResult,
    val sameFolderLrc: LyricsLookupStatus,
    val sameFolderTxt: LyricsLookupStatus,
    val folderPathUsed: String?,
    val candidateFilenames: List<String>,
)
