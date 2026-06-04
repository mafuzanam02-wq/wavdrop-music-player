package com.launchpoint.wavdrop.data.lyrics

import com.launchpoint.wavdrop.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsRepository @Inject constructor(
    private val embeddedExtractor: LyricsExtractor,
    private val sidecarExtractor: SidecarLyricsExtractor,
    private val overrideRepository: LyricsOverrideRepository? = null,
) {
    private val cache = ConcurrentHashMap<Long, LyricsLookupOutcome>()

    suspend fun getLyrics(song: Song): LyricsResult {
        return getLyricsLookup(song).result
    }

    suspend fun getLyricsLookup(song: Song): LyricsLookupOutcome {
        cache[song.id]?.let { return it }
        val outcome = withContext(Dispatchers.IO) { resolveLyrics(song) }
        cache[song.id] = outcome
        return outcome
    }

    fun observeOverride(songId: Long) =
        overrideRepository?.observeOverride(songId) ?: flowOf(null)

    /**
     * Reactive lyrics stream for a single song.
     *
     * Emits immediately whenever the user override changes:
     * - Override present  → [LyricsResult.Available] with the override text.
     * - No override       → [LyricsResult.Loading] followed by the result from embedded /
     *                       sidecar lookup (runs on [Dispatchers.IO]).
     *
     * Use this in ViewModels instead of the one-shot [getLyrics] so that saving or clearing
     * custom lyrics updates the displayed lyrics without requiring a screen reload.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeLyrics(song: Song): Flow<LyricsResult> {
        val overrideFlow = overrideRepository?.observeOverride(song.id) ?: flowOf(null)
        return overrideFlow.flatMapLatest { override ->
            if (override != null) {
                flowOf<LyricsResult>(LyricsResult.Available(override.lyrics))
            } else {
                flow<LyricsResult> {
                    emit(LyricsResult.Loading)
                    val result = withContext(Dispatchers.IO) { resolveFileLyrics(song) }
                    emit(result)
                }
            }
        }
    }

    suspend fun saveCustomLyrics(song: Song, lyrics: String) {
        overrideRepository?.saveOverride(song, lyrics)
        cache.remove(song.id)
    }

    suspend fun clearCustomLyrics(song: Song) {
        overrideRepository?.clearOverride(song)
        cache.remove(song.id)
    }

    private suspend fun resolveLyrics(song: Song): LyricsLookupOutcome {
        val override = overrideRepository?.getOverride(song)
        if (override != null) {
            return LyricsLookupOutcome(
                result = LyricsResult.Available(override.lyrics),
                diagnostics = LyricsDiagnostics(
                    embeddedMetadata = LyricsLookupStatus.NOT_FOUND,
                    sameFolderLrc = LyricsLookupStatus.NOT_FOUND,
                    sameFolderTxt = LyricsLookupStatus.NOT_FOUND,
                    folderPathUsed = null,
                    candidateFilenames = emptyList(),
                    songContentUri = song.uri,
                ),
            )
        }

        val embedded = embeddedExtractor.extract(song.uri)
        if (embedded is LyricsResult.Available) {
            return LyricsLookupOutcome(
                result = embedded,
                diagnostics = LyricsDiagnostics(
                    embeddedMetadata = LyricsLookupStatus.FOUND,
                    sameFolderLrc = LyricsLookupStatus.NOT_FOUND,
                    sameFolderTxt = LyricsLookupStatus.NOT_FOUND,
                    folderPathUsed = null,
                    candidateFilenames = emptyList(),
                    songContentUri = song.uri,
                ),
            )
        }

        val sidecar = sidecarExtractor.lookup(song)
        val result = when {
            sidecar.result is LyricsResult.Available -> sidecar.result
            embedded is LyricsResult.Error && sidecar.result is LyricsResult.Error ->
                LyricsResult.Error("${embedded.message}; ${sidecar.result.message}")
            else -> LyricsResult.NotFound
        }

        return LyricsLookupOutcome(
            result = result,
            diagnostics = LyricsDiagnostics(
                embeddedMetadata = embedded.toLookupStatus(),
                sameFolderLrc = sidecar.sameFolderLrc,
                sameFolderTxt = sidecar.sameFolderTxt,
                folderPathUsed = sidecar.folderPathUsed,
                candidateFilenames = sidecar.candidateFilenames,
                songContentUri = song.uri,
            ),
        )
    }

    /**
     * Embedded + sidecar lookup with no override check. Called from [observeLyrics] when
     * no user override exists. Must be called from a background dispatcher.
     */
    private fun resolveFileLyrics(song: Song): LyricsResult {
        val embedded = embeddedExtractor.extract(song.uri)
        if (embedded is LyricsResult.Available) return embedded
        val sidecar = sidecarExtractor.lookup(song)
        return when {
            sidecar.result is LyricsResult.Available -> sidecar.result
            embedded is LyricsResult.Error && sidecar.result is LyricsResult.Error ->
                LyricsResult.Error("${embedded.message}; ${sidecar.result.message}")
            else -> LyricsResult.NotFound
        }
    }

    private fun LyricsResult.toLookupStatus(): LyricsLookupStatus = when (this) {
        is LyricsResult.Available -> LyricsLookupStatus.FOUND
        is LyricsResult.Error -> LyricsLookupStatus.ERROR
        LyricsResult.Loading,
        LyricsResult.NotFound -> LyricsLookupStatus.NOT_FOUND
    }
}
