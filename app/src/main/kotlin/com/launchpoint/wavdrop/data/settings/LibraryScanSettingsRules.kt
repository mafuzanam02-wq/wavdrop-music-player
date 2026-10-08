package com.launchpoint.wavdrop.data.settings

import com.launchpoint.wavdrop.data.library.FolderGrouper
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
            customExcludedFolderPaths = normalizeCustomFolderExclusions(settings.customExcludedFolderPaths),
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

    // ── CFE-1: custom folder exclusions. This is the ONE authority for user-chosen folder paths: canonicalization, set
    //    normalization, add/remove and matching all live here, on top of the classifier's segment normalizer. ──────────────

    /**
     * The canonical stored form of a folder path, or null when it cannot be a custom exclusion: blank, the grouper's
     * [FolderGrouper.UNKNOWN_FOLDER] placeholder, or nothing left after removing the shared-storage prefix (which would
     * otherwise exclude the whole device). Canonical = `\` -> `/`, empty segments dropped, shared-storage prefix removed (same
     * segment primitive the preset classifier uses), original letter case kept for display; matching is case-insensitive.
     * The input is a RAW filesystem folder path (Song.folderPath / FolderGrouper.folderKey), NOT a URL: it is never
     * form/percent-decoded, so `Music/C++` stays `Music/C++` and a literal `Music/A%2FB` stays one folder named `A%2FB`.
     * Custom exclusions operate on WavDrop's normalized RELATIVE folder path: identical relative paths on different storage
     * volumes are not distinguished by the current folder model.
     */
    fun canonicalCustomFolderPath(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals(FolderGrouper.UNKNOWN_FOLDER, ignoreCase = true)) return null
        val segments = LibraryScanFolderClassifier.segments(trimmed, lowerCase = false, decodeEncodedText = false)
        return segments.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    /**
     * Canonicalizes, drops invalid entries, collapses case-insensitive duplicates (the lexicographically smallest spelling
     * wins, so the result never depends on input order) and returns a deterministic, case-insensitively sorted set.
     */
    fun normalizeCustomFolderExclusions(paths: Collection<String>?): Set<String> =
        paths.orEmpty()
            .mapNotNull(::canonicalCustomFolderPath)
            .groupBy { it.lowercase(Locale.ROOT) }
            .toSortedMap()
            .mapValuesTo(linkedMapOf<String, String>()) { (_, spellings) -> spellings.min() }
            .values
            .toCollection(linkedSetOf())

    /** Adds ONE folder exclusion; an invalid path is ignored. Every other setting and exclusion is untouched. */
    fun withCustomFolderExclusion(settings: LibraryScanSettings, folderPath: String): LibraryScanSettings {
        val canonical = canonicalCustomFolderPath(folderPath) ?: return normalize(settings)
        val key = canonical.lowercase(Locale.ROOT)
        // Already excluded (any spelling): idempotent, the stored spelling is left as it is.
        if (normalizeCustomFolderExclusions(settings.customExcludedFolderPaths).any { it.lowercase(Locale.ROOT) == key }) return normalize(settings)
        return normalize(settings.copy(customExcludedFolderPaths = settings.customExcludedFolderPaths + canonical))
    }

    /** Removes ONE folder exclusion (case-insensitive, separator-insensitive); every other setting and exclusion is untouched. */
    fun withoutCustomFolderExclusion(settings: LibraryScanSettings, folderPath: String): LibraryScanSettings {
        val key = canonicalCustomFolderPath(folderPath)?.lowercase(Locale.ROOT) ?: return normalize(settings)
        return normalize(
            settings.copy(
                customExcludedFolderPaths = normalizeCustomFolderExclusions(settings.customExcludedFolderPaths)
                    .filterNotTo(linkedSetOf()) { it.lowercase(Locale.ROOT) == key },
            ),
        )
    }

    /**
     * True when [song] lives in a custom-excluded folder or any descendant of one. Whole-segment prefix match on the
     * normalized segments, never a substring: excluding `Music/Podcasts` does not touch `Music/Podcasts Archive`. A song without
     * a usable folder path never matches.
     */
    fun isExcludedByCustomFolder(song: Song, settings: LibraryScanSettings): Boolean =
        isExcludedByCustomSegments(song, customExclusionSegments(settings))

    /** True when any explicit exclusion (preset category or custom folder) is active. */
    fun hasAnyExplicitExclusion(settings: LibraryScanSettings): Boolean =
        settings.excludedPresetFolders.isNotEmpty() || normalizeCustomFolderExclusions(settings.customExcludedFolderPaths).isNotEmpty()

    private fun customExclusionSegments(settings: LibraryScanSettings): List<List<String>> =
        normalizeCustomFolderExclusions(settings.customExcludedFolderPaths)
            .map { LibraryScanFolderClassifier.segments(it, decodeEncodedText = false) }
            .filter { it.isNotEmpty() }

    private fun isExcludedByCustomSegments(song: Song, exclusions: List<List<String>>): Boolean {
        if (exclusions.isEmpty()) return false
        val segments = LibraryScanFolderClassifier.segments(song.folderPath, decodeEncodedText = false)
        if (segments.isEmpty()) return false
        return exclusions.any { prefix -> segments.size >= prefix.size && segments.subList(0, prefix.size) == prefix }
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
     * Result of the two filter stages. [eligibleBeforeExplicitExclusionsCount] is how many songs passed the EXISTING rules
     * (minimum duration, WhatsApp rule, scan mode / selected folders) immediately BEFORE any explicit exclusion (SE-1 preset
     * categories, CFE-1 custom folders) ran; [songs] is what is left after them. It lets the sync tell "the explicit
     * exclusions emptied the library" from an ambiguous zero-result scan without a second query or a second path rule.
     */
    data class ScanEvaluation(
        val songs: List<Song>,
        val eligibleBeforeExplicitExclusionsCount: Int,
    )

    fun evaluateScanSettings(
        songs: List<Song>,
        settings: LibraryScanSettings,
    ): ScanEvaluation {
        val normalized = normalize(settings)
        val eligible = songs.filter { isSongAllowedBeforeExplicitExclusions(it, normalized) }
        val customSegments = customExclusionSegments(normalized)
        return ScanEvaluation(
            songs = eligible.filterNot { isExcludedByPreset(it, normalized) || isExcludedByCustomSegments(it, customSegments) },
            eligibleBeforeExplicitExclusionsCount = eligible.size,
        )
    }

    /** Stage 1: minimum duration AND the WhatsApp rule AND the scan mode. No explicit exclusion is consulted here. */
    fun isSongAllowedBeforeExplicitExclusions(
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
     * Duration AND WhatsApp rule AND scan mode (stage 1) AND NOT a preset exclusion AND NOT a custom folder exclusion
     * (stage 2). An explicit exclusion outranks everything, including an explicitly selected folder.
     */
    fun isSongAllowedByScanSettings(
        song: Song,
        settings: LibraryScanSettings,
    ): Boolean {
        val normalized = normalize(settings)
        return isSongAllowedBeforeExplicitExclusions(song, normalized) &&
            !isExcludedByPreset(song, normalized) &&
            !isExcludedByCustomFolder(song, normalized)
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
