package com.launchpoint.wavdrop.data.settings

import com.launchpoint.wavdrop.data.model.Song
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object LibraryScanSettingsRules {
    const val MINIMUM_TRACK_DURATION_SECONDS_MIN = 1
    const val MINIMUM_TRACK_DURATION_SECONDS_MAX = 60
    const val DEFAULT_MINIMUM_TRACK_DURATION_SECONDS = 50

    fun normalize(settings: LibraryScanSettings): LibraryScanSettings =
        settings.copy(
            selectedFolderUris = normalizeSelectedFolderUris(settings.selectedFolderUris),
            minimumTrackDurationSeconds = clampMinimumTrackDurationSeconds(
                settings.minimumTrackDurationSeconds,
            ),
            excludedPresetFolders = normalizePresetExclusions(settings.excludedPresetFolders),
        )

    /** A set has no duplicates; iteration is made deterministic (declaration order) so equal settings always serialize equally. */
    fun normalizePresetExclusions(exclusions: Set<LibraryScanExclusion>): Set<LibraryScanExclusion> =
        LibraryScanExclusion.entries.filterTo(linkedSetOf()) { it in exclusions }

    /** Enables or disables ONE preset exclusion; every other setting and every other exclusion is untouched. */
    fun withPresetExclusion(
        settings: LibraryScanSettings,
        exclusion: LibraryScanExclusion,
        excluded: Boolean,
    ): LibraryScanSettings =
        normalize(
            settings.copy(
                excludedPresetFolders = if (excluded) {
                    settings.excludedPresetFolders + exclusion
                } else {
                    settings.excludedPresetFolders - exclusion
                },
            ),
        )

    /** Parses persisted enum names: known values are kept, unknown/future/blank text is ignored. */
    fun parsePresetExclusions(names: Collection<String>?): Set<LibraryScanExclusion> =
        normalizePresetExclusions(
            names.orEmpty().mapNotNull { name -> LibraryScanExclusion.entries.firstOrNull { it.name == name } }.toSet(),
        )

    /**
     * SE-1: true when the user enabled the preset exclusion that [song]'s folder belongs to. Classification is
     * [LibraryScanFolderClassifier] (path segments only); nothing here duplicates a path rule.
     */
    fun isExcludedByPreset(song: Song, settings: LibraryScanSettings): Boolean {
        val enabled = settings.excludedPresetFolders
        if (enabled.isEmpty()) return false
        val category = LibraryScanFolderClassifier.classify(song) ?: return false
        return category in enabled
    }

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
    ): List<Song> = evaluateScanSettings(songs, settings).songs

    /**
     * Result of the two filter stages. [eligibleBeforePresetExclusionsCount] is how many songs passed the EXISTING rules
     * (minimum duration, WhatsApp rule, scan mode / selected folders) immediately BEFORE the SE-1 preset exclusions ran;
     * [songs] is what is left after them. It lets the sync tell "the explicit exclusion emptied the library" from an
     * ambiguous zero-result scan without a second query or a second path rule.
     */
    data class ScanEvaluation(
        val songs: List<Song>,
        val eligibleBeforePresetExclusionsCount: Int,
    )

    fun evaluateScanSettings(
        songs: List<Song>,
        settings: LibraryScanSettings,
    ): ScanEvaluation {
        val normalized = normalize(settings)
        val eligible = songs.filter { isSongAllowedBeforePresetExclusions(it, normalized) }
        return ScanEvaluation(
            songs = eligible.filterNot { isExcludedByPreset(it, normalized) },
            eligibleBeforePresetExclusionsCount = eligible.size,
        )
    }

    /** Stage 1: minimum duration AND the WhatsApp rule AND the scan mode. No preset exclusion is consulted here. */
    fun isSongAllowedBeforePresetExclusions(
        song: Song,
        settings: LibraryScanSettings,
    ): Boolean {
        val normalized = normalize(settings)
        if (song.duration < minimumDurationMs(normalized)) return false
        if (!normalized.includeWhatsAppVoiceNotes && song.isWhatsAppVoiceNote()) return false
        return when (normalized.scanMode) {
            LibraryScanMode.WHOLE_DEVICE -> true
            LibraryScanMode.SELECTED_FOLDERS ->
                matchesSelectedFolder(song, normalized.selectedFolderUris)
        }
    }

    /**
     * Duration AND WhatsApp rule AND scan mode (stage 1) AND NOT a preset exclusion (stage 2). An explicit exclusion
     * outranks everything, including an explicitly selected folder.
     */
    fun isSongAllowedByScanSettings(
        song: Song,
        settings: LibraryScanSettings,
    ): Boolean {
        val normalized = normalize(settings)
        return isSongAllowedBeforePresetExclusions(song, normalized) && !isExcludedByPreset(song, normalized)
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

    private fun Song.isWhatsAppVoiceNote(): Boolean {
        val path = folderPath.orEmpty()
        return path.contains("WhatsApp Voice Notes", ignoreCase = true) ||
            path.contains("WhatsApp Business Voice Notes", ignoreCase = true)
    }
}
