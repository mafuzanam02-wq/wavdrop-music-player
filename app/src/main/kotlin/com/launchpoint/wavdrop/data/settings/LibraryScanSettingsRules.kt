package com.launchpoint.wavdrop.data.settings

import com.launchpoint.wavdrop.data.model.Song
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object LibraryScanSettingsRules {
    const val MINIMUM_TRACK_DURATION_SECONDS_MIN = 1
    const val MINIMUM_TRACK_DURATION_SECONDS_MAX = 60
    const val DEFAULT_MINIMUM_TRACK_DURATION_SECONDS = 30

    fun normalize(settings: LibraryScanSettings): LibraryScanSettings =
        settings.copy(
            selectedFolderUris = normalizeSelectedFolderUris(settings.selectedFolderUris),
            minimumTrackDurationSeconds = clampMinimumTrackDurationSeconds(
                settings.minimumTrackDurationSeconds,
            ),
        )

    fun clampMinimumTrackDurationSeconds(seconds: Int): Int =
        seconds.coerceIn(
            MINIMUM_TRACK_DURATION_SECONDS_MIN,
            MINIMUM_TRACK_DURATION_SECONDS_MAX,
        )

    fun minimumDurationMs(settings: LibraryScanSettings): Long =
        clampMinimumTrackDurationSeconds(settings.minimumTrackDurationSeconds) * 1_000L

    fun withScanMode(
        settings: LibraryScanSettings,
        scanMode: LibraryScanMode,
    ): LibraryScanSettings =
        normalize(settings.copy(scanMode = scanMode))

    fun withMinimumTrackDurationSeconds(
        settings: LibraryScanSettings,
        seconds: Int,
    ): LibraryScanSettings =
        normalize(settings.copy(minimumTrackDurationSeconds = seconds))

    fun withAddedFolderUri(
        settings: LibraryScanSettings,
        folderUri: String,
    ): LibraryScanSettings =
        normalize(
            settings.copy(
                selectedFolderUris = settings.selectedFolderUris + folderUri,
            ),
        )

    fun withRemovedFolderUri(
        settings: LibraryScanSettings,
        folderUri: String,
    ): LibraryScanSettings =
        normalize(
            settings.copy(
                selectedFolderUris = settings.selectedFolderUris.filterNot {
                    it.trim() == folderUri.trim()
                },
            ),
        )

    fun filterSongsForScanSettings(
        songs: List<Song>,
        settings: LibraryScanSettings,
    ): List<Song> {
        val normalized = normalize(settings)
        return songs.filter { isSongAllowedByScanSettings(it, normalized) }
    }

    fun isSongAllowedByScanSettings(
        song: Song,
        settings: LibraryScanSettings,
    ): Boolean {
        val normalized = normalize(settings)
        if (song.duration < minimumDurationMs(normalized)) return false
        return when (normalized.scanMode) {
            LibraryScanMode.WHOLE_DEVICE -> true
            LibraryScanMode.SELECTED_FOLDERS ->
                matchesSelectedFolder(song, normalized.selectedFolderUris)
        }
    }

    fun matchesSelectedFolder(
        song: Song,
        selectedFolderUris: List<String>,
    ): Boolean {
        val selectedTokens = normalizeSelectedFolderUris(selectedFolderUris)
            .flatMap(::folderCandidates)
            .filter { it.isNotBlank() }
            .distinct()
        if (selectedTokens.isEmpty()) return false

        val songTokens = listOfNotNull(song.folderPath, song.folderName)
            .flatMap(::folderCandidates)
            .filter { it.isNotBlank() }
            .distinct()
        if (songTokens.isEmpty()) return false

        return songTokens.any { songToken ->
            selectedTokens.any { selectedToken ->
                songToken == selectedToken ||
                    songToken.startsWith("$selectedToken/") ||
                    selectedToken.endsWith("/$songToken") ||
                    selectedToken.endsWith(":$songToken") ||
                    selectedToken.contains("/$songToken/")
            }
        }
    }

    private fun normalizeSelectedFolderUris(folderUris: List<String>): List<String> {
        val unique = linkedSetOf<String>()
        folderUris
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { unique += it }
        return unique.toList()
    }

    private fun folderCandidates(value: String): List<String> {
        val normalized = normalizePathToken(value)
        if (normalized.isBlank()) return emptyList()

        val afterTree = normalized.substringAfter("/tree/", normalized)
        val afterColon = afterTree.substringAfter(":", afterTree)
        val afterStorageRoot = normalized.substringAfter("storage/emulated/0/", normalized)

        return listOf(normalized, afterTree, afterColon, afterStorageRoot)
            .map(::normalizePathToken)
            .filter { it.isNotBlank() }
    }

    private fun normalizePathToken(value: String): String =
        decode(value)
            .replace('\\', '/')
            .trim()
            .trim('/')
            .lowercase(Locale.US)

    private fun decode(value: String): String =
        runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
}
