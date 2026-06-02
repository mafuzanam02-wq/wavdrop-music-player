package com.launchpoint.wavdrop.data.lyrics

interface LyricsExtractor {
    fun extract(uri: String): LyricsResult
}
