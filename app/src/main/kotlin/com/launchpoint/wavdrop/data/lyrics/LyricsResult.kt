package com.launchpoint.wavdrop.data.lyrics

sealed interface LyricsResult {
    data object Loading                     : LyricsResult
    data class  Available(val text: String) : LyricsResult
    data object NotFound                    : LyricsResult
    data class  Error(val message: String)  : LyricsResult
}
