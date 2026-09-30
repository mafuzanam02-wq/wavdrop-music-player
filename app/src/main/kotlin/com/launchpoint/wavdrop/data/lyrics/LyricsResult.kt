package com.launchpoint.wavdrop.data.lyrics

sealed interface LyricsResult {
    data object Loading                     : LyricsResult
    data class  Available(val text: String) : LyricsResult
    /**
     * Time-synchronized lyrics (currently only from same-folder `.lrc` sidecars). [plainText] is the
     * same lyric without timestamps, for every surface that shows or edits static text.
     */
    data class  Synced(
        val lines: List<SyncedLyricsLine>,
        val plainText: String,
    ) : LyricsResult
    data object NotFound                    : LyricsResult
    data class  Error(val message: String)  : LyricsResult
}

/** One timed lyric entry. [text] may be empty: a timed gap (e.g. instrumental) in the timeline. */
data class SyncedLyricsLine(
    val timeMs: Long,
    val text: String,
)

/** Static text for [LyricsResult.Available] / [LyricsResult.Synced]; null for every other state. */
val LyricsResult.staticText: String?
    get() = when (this) {
        is LyricsResult.Available -> text
        is LyricsResult.Synced -> plainText
        LyricsResult.Loading,
        LyricsResult.NotFound,
        is LyricsResult.Error -> null
    }

/** True for results that carry usable lyrics (plain or synced). */
internal val LyricsResult.hasLyrics: Boolean
    get() = this is LyricsResult.Available || this is LyricsResult.Synced
