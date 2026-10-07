package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupInputReader
import java.util.Locale

/**
 * WDBK container version. INDEPENDENT of the logical backup version
 * ([com.launchpoint.wavdrop.data.backup.WavdropBackupParser.SUPPORTED_VERSION], v2): the container only
 * describes how a v2 logical backup is packaged. It is not "Backup V3".
 *
 *  - [MAJOR] changes mean the container cannot be read by older readers (rejected with the standard
 *    "newer version of Wavdrop" message).
 *  - [MINOR] changes are additive. A reader accepts any minor of its own major: unknown manifest fields are
 *    ignored and unknown entries are tolerated only when the manifest declares them OPTIONAL (their size and
 *    SHA-256 are still verified; their content is ignored). An unknown REQUIRED entry or section is rejected.
 */
object WdbkContainerVersion {
    const val FORMAT = "wavdrop_wdbk"
    const val MAJOR = 1
    const val MINOR = 0
    const val LOGICAL_FORMAT = "wavdrop_backup"
    const val LOGICAL_VERSION = 2
    const val SECTION_VERSION = 1
    const val EXTENSION = "wdbk"
}

/**
 * Centralised safety bounds for untrusted WDBK input. Every value is deliberately conservative: large enough
 * for any realistic Wavdrop backup, small enough that a hostile file cannot exhaust memory. Limits are counted
 * on ACTUAL bytes read; `ZipEntry.size`/`compressedSize` are never trusted.
 *
 * Tests inject tighter limits to cover the exact boundary (limit passes, limit + 1 fails) cheaply.
 */
data class WdbkLimits(
    /**
     * Outer (compressed) container size. Same 100 MiB cap as every other untrusted backup input
     * ([BackupInputReader.MAX_BACKUP_INPUT_BYTES]); compact+DEFLATEd JSON is far smaller than the legacy
     * pretty-printed JSON that cap was sized for.
     */
    val maxContainerBytes: Long = BackupInputReader.MAX_BACKUP_INPUT_BYTES,
    /**
     * Maximum ZIP entries including the manifest. A history chunk holds [EVENTS_PER_CHUNK] events, so the
     * default allows roughly 8 million events — orders of magnitude beyond a real listening history —
     * while still bounding per-entry bookkeeping (names, descriptors, digests).
     */
    val maxEntryCount: Int = 4096,
    /**
     * Maximum decompressed bytes of ONE entry. The largest real entry is a single section (songs/playlists
     * of a very large library, a few tens of MB at the extreme); a history chunk is ~0.5 MB. Bounds the
     * transient byte[]/String/object graph a single entry can force.
     */
    val maxEntryUncompressedBytes: Long = 64L * 1024L * 1024L,
    /**
     * Maximum decompressed bytes across ALL entries. Compact JSON of a backup that fit the legacy 100 MiB
     * pretty-printed cap is far below this; 256 MiB leaves ample headroom yet stops a decompression bomb
     * that stays under the per-entry bound by spreading across many entries.
     */
    val maxTotalUncompressedBytes: Long = 256L * 1024L * 1024L,
    /** The manifest lists at most [maxEntryCount] descriptors (~250 B each) — 4 MiB is a generous ceiling. */
    val maxManifestBytes: Long = 4L * 1024L * 1024L,
    /** Maximum events one history chunk may declare/contain on import (the writer emits [EVENTS_PER_CHUNK]). */
    val maxEventsPerChunk: Int = 10_000,
) {
    companion object {
        val DEFAULT = WdbkLimits()

        /**
         * Events per history chunk the writer emits. See docs/architecture/WDBK_BENCHMARKS.md: a ~2 000-event
         * chunk is ~0.4–0.6 MB of compact JSON — large enough that DEFLATE sees plenty of repetition (the
         * compression ratio is within a few percent of 10 000-event chunks), small enough to keep each
         * transient array/String/JSON graph well under a megabyte and to keep the per-entry hash/parse work
         * incremental.
         */
        const val EVENTS_PER_CHUNK = 2_000

        /** Rows fetched per keyset page while exporting. Equal to the chunk size so a page maps to one chunk. */
        const val EVENT_EXPORT_PAGE_SIZE = EVENTS_PER_CHUNK
    }
}

/** Canonical, deterministic entry layout of a WDBK container v1. */
object WdbkLayout {
    const val MANIFEST = "manifest.json"
    const val SONGS = "sections/songs.json"
    const val TRACK_STATS = "sections/track-stats.json"
    const val IMPORT_BASELINES = "sections/import-baselines.json"
    const val LYRICS_OVERRIDES = "sections/lyrics-overrides.json"
    const val PREFERENCES = "sections/preferences.json"
    const val PLAYLISTS = "sections/playlists.json"
    const val DESKTOP_OVERLAY = "extensions/desktop-overlay.json"
    const val HISTORY_DIR = "history/"

    /** Logical section ids recorded in manifest entry descriptors. */
    object Section {
        const val SONGS = "songs"
        const val TRACK_STATS = "trackStats"
        const val IMPORT_BASELINES = "importBaselines"
        const val LYRICS_OVERRIDES = "lyricsOverrides"
        const val PREFERENCES = "preferences"
        const val PLAYLISTS = "playlists"
        const val LISTEN_EVENTS = "listenEvents"
        const val DESKTOP_OVERLAY = "desktopOverlay"
    }

    /** Sections that are always present, even when empty (`[]`). */
    val ALWAYS_PRESENT: Map<String, String> = linkedMapOf(
        Section.SONGS to SONGS,
        Section.TRACK_STATS to TRACK_STATS,
        Section.IMPORT_BASELINES to IMPORT_BASELINES,
        Section.LYRICS_OVERRIDES to LYRICS_OVERRIDES,
        Section.PLAYLISTS to PLAYLISTS,
    )

    /** Presence-conditional sections: `preferences.json` only when preferences exist, overlay only when it exists. */
    val OPTIONAL_PRESENCE: Map<String, String> = linkedMapOf(
        Section.PREFERENCES to PREFERENCES,
        Section.DESKTOP_OVERLAY to DESKTOP_OVERLAY,
    )

    /** path → section for every entry name this reader understands (history chunks are matched separately). */
    val KNOWN_FIXED_PATHS: Map<String, String> =
        (ALWAYS_PRESENT.entries + OPTIONAL_PRESENCE.entries).associate { (section, path) -> path to section }

    private val HISTORY_NAME = Regex("""history/listen-events-(\d{6})\.json""")

    /** Locale.ROOT on purpose: a default locale with non-ASCII digits must never change entry names. */
    fun historyPath(chunkIndex: Int): String =
        String.format(Locale.ROOT, "%slisten-events-%06d.json", HISTORY_DIR, chunkIndex)

    /** The chunk index encoded in a canonical history entry name, or null when [path] is not one. */
    fun historyChunkIndex(path: String): Int? =
        HISTORY_NAME.matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()

    private val SAFE_SEGMENT = Regex("""[A-Za-z0-9._-]+""")

    /**
     * Strict entry-name allow-list. Rejects empty names, absolute paths, backslashes, drive/URL colons,
     * empty segments (`//`), `.`/`..` segments (path traversal), directory entries (trailing `/`), control or
     * non-ASCII characters and over-long names. Entries are never extracted to the filesystem; this keeps a
     * hostile name from ever being echoed or interpreted as a path.
     */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.length > 255) return false
        val segments = name.split('/')
        return segments.all { it != "." && it != ".." && SAFE_SEGMENT.matches(it) }
    }
}
