package com.launchpoint.wavdrop.data.lyrics

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaMetadataLyricsExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) : LyricsExtractor {

    override fun extract(uri: String): LyricsResult {
        var metadataError: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uri))
            // METADATA_KEY_LYRIC = 17 (public in API 29+; raw value works on API 26+)
            val raw = retriever.extractMetadata(METADATA_KEY_LYRIC)
            val metadataResult = processRaw(raw)
            if (metadataResult is LyricsResult.Available) return metadataResult
        } catch (e: Exception) {
            metadataError = e.message ?: "Failed to read lyrics metadata"
        } finally {
            runCatching { retriever.release() }
        }

        val id3Result = runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                val bytes = input.readBytes()
                processRaw(Id3LyricsParser.parseUnsynchronisedLyrics(bytes))
            } ?: LyricsResult.NotFound
        }.getOrElse { error ->
            LyricsResult.Error(error.message ?: "Failed to read embedded lyrics")
        }

        return when {
            id3Result is LyricsResult.Available -> id3Result
            metadataError != null && id3Result is LyricsResult.Error ->
                LyricsResult.Error("$metadataError; ${id3Result.message}")
            metadataError != null -> LyricsResult.Error(metadataError)
            else -> id3Result
        }
    }

    companion object {
        private const val METADATA_KEY_LYRIC = 17

        internal fun processRaw(raw: String?): LyricsResult = when {
            raw.isNullOrBlank() -> LyricsResult.NotFound
            else                -> LyricsResult.Available(raw.trim())
        }
    }
}
