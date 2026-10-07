package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupFormatVersion
import com.launchpoint.wavdrop.data.backup.BackupInputReader
import com.launchpoint.wavdrop.data.backup.BackupIntegrityStatus
import com.launchpoint.wavdrop.data.backup.BackupDesktopOverlay
import com.launchpoint.wavdrop.data.backup.BackupImportBaseline
import com.launchpoint.wavdrop.data.backup.BackupListenEvent
import com.launchpoint.wavdrop.data.backup.BackupLyricsOverride
import com.launchpoint.wavdrop.data.backup.BackupPlaylist
import com.launchpoint.wavdrop.data.backup.BackupPreferences
import com.launchpoint.wavdrop.data.backup.BackupSong
import com.launchpoint.wavdrop.data.backup.BackupTrackStats
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportResult
import com.launchpoint.wavdrop.data.backup.WavdropBackupIntegrityV2
import com.launchpoint.wavdrop.data.backup.WavdropBackupParser
import com.launchpoint.wavdrop.data.backup.WavdropBackupSectionParser
import com.launchpoint.wavdrop.data.backup.BackupParseException
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Bounded, fail-closed reader for an UNTRUSTED WDBK container.
 *
 * Reads the archive sequentially (works on non-seekable provider streams), never extracts anything to the
 * filesystem and never recurses into nested archives. Each entry is read with a hard byte bound on ACTUAL bytes
 * (declared ZIP sizes are ignored), hashed, and — when its name is a canonical section — parsed with the same
 * strict section parsers as legacy v2 JSON. Nothing reaches the caller unless EVERY check passes:
 *
 *  1. structure: safe names, no duplicates, entry-count / per-entry / total / outer byte limits, ZIP end record;
 *  2. exactly one manifest, supported container major, supported logical v2 content, known capabilities;
 *  3. the manifest and the physical entries describe the same set (no undeclared / missing entries);
 *  4. every entry's byte length and SHA-256 match the manifest;
 *  5. section contract (required sections, contiguous history chunks, per-chunk event counts);
 *  6. logical section counts match the reconstructed model;
 *  7. the WavdropBackupIntegrityV2 semantic fingerprint of the reconstructed model matches the manifest;
 *  8. stat plausibility (WD-05), same as legacy.
 *
 * Success is [BackupIntegrityStatus.VERIFIED] with a backup indistinguishable from the one the legacy v2 parser
 * would produce for the same logical state. Any failure yields an error result with NO backup.
 */
class WdbkReader(private val limits: WdbkLimits = WdbkLimits.DEFAULT) {

    fun read(
        input: InputStream,
        nowMs: Long = System.currentTimeMillis(),
    ): WavdropBackupImportResult = try {
        decode(input, nowMs)
    } catch (e: WdbkFormatException) {
        failure(e.message ?: DAMAGED)
    } catch (e: BackupParseException) {
        failure(e.message ?: DAMAGED)
    } catch (_: BackupInputReader.InputTooLargeException) {
        failure(BackupInputReader.TOO_LARGE_MESSAGE)
    } catch (_: EOFException) {
        failure(DAMAGED)
    } catch (_: ZipException) {
        failure(DAMAGED)
    } catch (_: IOException) {
        failure(DAMAGED)
    } catch (_: RuntimeException) {
        // Malformed entry names / headers rejected by java.util.zip (IllegalArgumentException and friends).
        failure(DAMAGED)
    }

    // ── Streaming pass ────────────────────────────────────────────────────────

    private class Observed(val length: Long, val sha256: String)

    /** Parsed content of canonical entries, collected while streaming; trusted only after final verification. */
    private class Collected {
        var songs: List<BackupSong>? = null
        var trackStats: List<BackupTrackStats>? = null
        var importBaselines: List<BackupImportBaseline>? = null
        var lyricsOverrides: List<BackupLyricsOverride>? = null
        var playlists: List<BackupPlaylist>? = null
        var preferences: BackupPreferences? = null
        var overlay: BackupDesktopOverlay? = null
        val chunks = HashMap<Int, List<BackupListenEvent>>()
        /** First parse failure, deferred so an integrity mismatch is reported in preference to a parse error. */
        var deferredParseError: BackupParseException? = null
    }

    private fun decode(input: InputStream, nowMs: Long): WavdropBackupImportResult {
        // Buffered BELOW the recorder so provider streams are read in 64 KiB blocks (ZipInputStream asks for 512 bytes).
        val tail = TailRecordingInputStream(BufferedInputStream(input, 64 * 1024), limits.maxContainerBytes)
        val zip = ZipInputStream(tail)
        try {
            return decodeEntries(zip, tail, nowMs)
        } finally {
            try { zip.close() } catch (_: IOException) { }
        }
    }

    private fun decodeEntries(zip: ZipInputStream, tail: TailRecordingInputStream, nowMs: Long): WavdropBackupImportResult {
        val observed = LinkedHashMap<String, Observed>()
        val collected = Collected()
        var manifestBytes: ByteArray? = null
        var entryCount = 0
        var totalBytes = 0L

        while (true) {
            val entry = zip.nextEntry ?: break
            if (++entryCount > limits.maxEntryCount) {
                throw WdbkFormatException(TOO_LARGE_TO_OPEN, WdbkFormatException.Kind.TOO_LARGE)
            }
            val name = entry.name
            if (entry.isDirectory || !WdbkLayout.isSafeEntryName(name)) throw WdbkFormatException(DAMAGED)
            if (name in observed || (name == WdbkLayout.MANIFEST && manifestBytes != null)) {
                throw WdbkFormatException(DAMAGED)
            }

            val cap = if (name == WdbkLayout.MANIFEST) limits.maxManifestBytes else limits.maxEntryUncompressedBytes
            val bytes = readBounded(zip, cap, limits.maxTotalUncompressedBytes - totalBytes)
            totalBytes += bytes.size

            if (name == WdbkLayout.MANIFEST) {
                manifestBytes = bytes
                continue
            }
            observed[name] = Observed(bytes.size.toLong(), WdbkWriter.sha256Hex(bytes))
            collect(name, bytes, collected)
        }

        val rawManifest = manifestBytes ?: throw WdbkFormatException(
            if (entryCount == 0) WdbkManifest.NOT_A_BACKUP else DAMAGED,
            if (entryCount == 0) WdbkFormatException.Kind.NOT_A_CONTAINER else WdbkFormatException.Kind.DAMAGED,
        )
        tail.drainAndRequireZipEndRecord()

        val manifest = WdbkManifest.fromParsed(WavdropBackupSectionParser.parseJson(String(rawManifest, Charsets.UTF_8)))
        return finish(manifest, observed, collected, nowMs)
    }

    /** Routes a canonical entry to its section parser; failures are deferred (see [Collected.deferredParseError]). */
    private fun collect(name: String, bytes: ByteArray, into: Collected) {
        val section = WdbkLayout.KNOWN_FIXED_PATHS[name]
        val chunkIndex = if (section == null) WdbkLayout.historyChunkIndex(name) else null
        if (section == null && chunkIndex == null) return // unknown name: only hashed; must be declared optional later
        if (into.deferredParseError != null) return
        try {
            val value = WavdropBackupSectionParser.parseJson(String(bytes, Charsets.UTF_8))
            when {
                chunkIndex != null -> {
                    val list = value as? List<*> ?: throw BackupParseException("Field listenEvents must be an array")
                    // Bound BEFORE mapping so a hostile chunk cannot force a huge object graph.
                    if (list.size > limits.maxEventsPerChunk) {
                        throw WdbkFormatException(DAMAGED)
                    }
                    into.chunks[chunkIndex] = WavdropBackupSectionParser.listenEvents(list)
                }
                section == WdbkLayout.Section.SONGS -> into.songs = WavdropBackupSectionParser.songs(array(value, "songs"))
                section == WdbkLayout.Section.TRACK_STATS -> into.trackStats = WavdropBackupSectionParser.trackStats(array(value, "trackStats"))
                section == WdbkLayout.Section.IMPORT_BASELINES -> into.importBaselines = WavdropBackupSectionParser.importBaselines(array(value, "importBaselines"))
                section == WdbkLayout.Section.LYRICS_OVERRIDES -> into.lyricsOverrides = WavdropBackupSectionParser.lyricsOverrides(array(value, "lyricsOverrides"))
                section == WdbkLayout.Section.PLAYLISTS -> into.playlists = WavdropBackupSectionParser.playlists(array(value, "playlists"))
                section == WdbkLayout.Section.PREFERENCES -> {
                    val map = value as? Map<*, *> ?: throw BackupParseException("Field preferences must be an object")
                    into.preferences = WavdropBackupSectionParser.androidPreferences(map)
                }
                section == WdbkLayout.Section.DESKTOP_OVERLAY -> {
                    val map = value as? Map<*, *> ?: throw BackupParseException("Field desktopOverlay must be an object")
                    into.overlay = WavdropBackupSectionParser.desktopOverlay(map)
                }
            }
        } catch (e: BackupParseException) {
            into.deferredParseError = e
        } catch (e: WdbkFormatException) {
            into.deferredParseError = BackupParseException(e.message ?: DAMAGED)
        }
    }

    private fun array(value: Any?, name: String): List<*> =
        value as? List<*> ?: throw BackupParseException("Field $name must be an array")

    // ── Verification against the manifest ─────────────────────────────────────

    private fun finish(
        manifest: WdbkManifest,
        observed: Map<String, Observed>,
        collected: Collected,
        nowMs: Long,
    ): WavdropBackupImportResult {
        val warnings = mutableListOf<String>()

        // Capabilities (same rules as legacy v2).
        manifest.requiredCapabilities.firstOrNull { it !in WavdropBackupParser.KNOWN_REQUIRED_CAPABILITIES }?.let { cap ->
            throw WdbkFormatException(
                "This backup requires a feature this version of Wavdrop does not support: $cap. Update Wavdrop and try again.",
                WdbkFormatException.Kind.UNSUPPORTED,
            )
        }
        val unknownOptional = manifest.optionalCapabilities.filter { it !in WavdropBackupParser.KNOWN_OPTIONAL_CAPABILITIES }
        if (unknownOptional.isNotEmpty()) {
            warnings += "This backup includes optional features not supported by this version of " +
                "Wavdrop. Some data may not be fully restored: ${unknownOptional.joinToString()}."
        }

        // Descriptor structure.
        if (manifest.entries.size > limits.maxEntryCount) throw WdbkFormatException(TOO_LARGE_TO_OPEN, WdbkFormatException.Kind.TOO_LARGE)
        val declared = LinkedHashMap<String, WdbkEntryDescriptor>()
        for (d in manifest.entries) {
            if (!WdbkLayout.isSafeEntryName(d.path) || d.path == WdbkLayout.MANIFEST) throw WdbkFormatException(DAMAGED)
            if (declared.put(d.path, d) != null) throw WdbkFormatException(DAMAGED)
        }

        // Physical set must equal the declared set: no undeclared payload, no missing payload.
        if (declared.keys != observed.keys) throw WdbkFormatException(DAMAGED)

        // Physical integrity: length and SHA-256 of the exact uncompressed bytes.
        for ((path, d) in declared) {
            val seen = observed.getValue(path)
            if (seen.length != d.byteLength || !seen.sha256.equals(d.sha256, ignoreCase = true)) {
                throw WdbkFormatException(WavdropBackupParser.INTEGRITY_ERROR, WdbkFormatException.Kind.INTEGRITY)
            }
        }

        // Section contract.
        for ((section, path) in WdbkLayout.ALWAYS_PRESENT) {
            val d = declared[path] ?: throw WdbkFormatException(DAMAGED)
            checkKnownDescriptor(d, section, mustBeRequired = true)
        }
        var overlayUsable = false
        val chunkDescriptors = mutableListOf<WdbkEntryDescriptor>()
        for (d in declared.values) {
            val fixedSection = WdbkLayout.KNOWN_FIXED_PATHS[d.path]
            val historyIndex = if (fixedSection == null) WdbkLayout.historyChunkIndex(d.path) else null
            when {
                fixedSection != null && fixedSection in WdbkLayout.ALWAYS_PRESENT -> Unit // checked above
                fixedSection == WdbkLayout.Section.PREFERENCES -> checkKnownDescriptor(d, fixedSection, mustBeRequired = true)
                fixedSection == WdbkLayout.Section.DESKTOP_OVERLAY -> {
                    if (d.section != fixedSection) throw WdbkFormatException(DAMAGED)
                    if (d.sectionVersion == WdbkContainerVersion.SECTION_VERSION) {
                        overlayUsable = true
                    } else if (d.required) {
                        throw WdbkFormatException(WdbkManifest.NEWER_VERSION, WdbkFormatException.Kind.NEWER_VERSION)
                    } else {
                        warnings += "This backup contains Desktop data in a newer format that this version of Wavdrop cannot read; it was skipped."
                    }
                }
                historyIndex != null -> {
                    checkKnownDescriptor(d, WdbkLayout.Section.LISTEN_EVENTS, mustBeRequired = true)
                    if (d.chunkIndex != historyIndex) throw WdbkFormatException(DAMAGED)
                    chunkDescriptors += d
                }
                d.required -> throw WdbkFormatException(
                    "This backup requires a feature this version of Wavdrop does not support. Update Wavdrop and try again.",
                    WdbkFormatException.Kind.UNSUPPORTED,
                )
                else -> warnings += "This backup includes optional data this version of Wavdrop does not understand; it was ignored."
            }
        }

        // History chunks: contiguous 0..n-1, each non-empty, bounded, count matching the parsed array.
        val sorted = chunkDescriptors.sortedBy { it.chunkIndex }
        sorted.forEachIndexed { expected, d ->
            if (d.chunkIndex != expected) throw WdbkFormatException(DAMAGED)
            val declaredCount = d.eventCount ?: throw WdbkFormatException(DAMAGED)
            if (declaredCount < 1 || declaredCount > limits.maxEventsPerChunk) throw WdbkFormatException(DAMAGED)
        }

        // Only now that integrity holds is a deferred parse error the right thing to report.
        collected.deferredParseError?.let { throw it }

        val events = ArrayList<BackupListenEvent>(sorted.sumOf { it.eventCount ?: 0 })
        for (d in sorted) {
            val chunk = collected.chunks[d.chunkIndex] ?: throw WdbkFormatException(DAMAGED)
            if (chunk.size != d.eventCount) throw WdbkFormatException(WavdropBackupParser.INTEGRITY_ERROR, WdbkFormatException.Kind.INTEGRITY)
            events.addAll(chunk)
        }

        val preferencesDeclared = declared.containsKey(WdbkLayout.PREFERENCES)
        if (preferencesDeclared && collected.preferences == null) throw WdbkFormatException(DAMAGED)

        val sealed = WavdropBackup(
            exportedAt = "",
            exportedAtMs = manifest.exportedAtMs,
            backupId = manifest.backupId,
            sourceInstallationId = manifest.sourceInstallationId,
            sourceVersion = BackupFormatVersion.V2,
            songs = collected.songs ?: throw WdbkFormatException(DAMAGED),
            trackStats = collected.trackStats ?: throw WdbkFormatException(DAMAGED),
            importBaselines = collected.importBaselines ?: throw WdbkFormatException(DAMAGED),
            lyricsOverrides = collected.lyricsOverrides ?: throw WdbkFormatException(DAMAGED),
            preferences = if (preferencesDeclared) collected.preferences else null,
            playlists = collected.playlists ?: throw WdbkFormatException(DAMAGED),
            listenEvents = events,
            manifest = manifest.counts,
            appVersionCode = manifest.appVersionCode,
            appVersionName = manifest.appVersionName,
        )

        // Logical section counts, then the semantic fingerprint — the equivalence bridge to legacy v2.
        if (!manifest.counts.matchesContentOf(sealed)) {
            throw WdbkFormatException(WavdropBackupParser.INTEGRITY_ERROR, WdbkFormatException.Kind.INTEGRITY)
        }
        if (manifest.fingerprint != WavdropBackupIntegrityV2.fingerprint(sealed)) {
            throw WdbkFormatException(WavdropBackupParser.INTEGRITY_ERROR, WdbkFormatException.Kind.INTEGRITY)
        }
        if (!WavdropBackupSectionParser.trackStatsArePlausible(sealed.trackStats, nowMs)) {
            throw WdbkFormatException(WavdropBackupParser.IMPLAUSIBLE_STATS_ERROR)
        }

        val backup = sealed.copy(desktopOverlay = if (overlayUsable) collected.overlay else null)
        return WavdropBackupImportResult(
            backup = backup,
            error = null,
            integrityStatus = BackupIntegrityStatus.VERIFIED,
            warnings = warnings,
        )
    }

    private fun checkKnownDescriptor(d: WdbkEntryDescriptor, expectedSection: String, mustBeRequired: Boolean) {
        if (d.section != expectedSection) throw WdbkFormatException(DAMAGED)
        if (mustBeRequired && !d.required) throw WdbkFormatException(DAMAGED)
        if (d.sectionVersion != WdbkContainerVersion.SECTION_VERSION) {
            throw WdbkFormatException(WdbkManifest.NEWER_VERSION, WdbkFormatException.Kind.NEWER_VERSION)
        }
    }

    // ── Bounded stream helpers ────────────────────────────────────────────────

    /**
     * Reads the current entry fully, throwing as soon as ACTUAL decompressed bytes exceed [entryCap] or
     * [totalRemaining]. Never allocates more than the bound plus one buffer.
     */
    private fun readBounded(zip: ZipInputStream, entryCap: Long, totalRemaining: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            total += n
            if (total > entryCap || total > totalRemaining) {
                throw WdbkFormatException(TOO_LARGE_TO_OPEN, WdbkFormatException.Kind.TOO_LARGE)
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Counts raw container bytes against the outer cap, and remembers the last bytes so the ZIP end-of-central-
     * directory record can be verified after the sequential entry pass (a container cut inside the central
     * directory must not be accepted as "complete").
     */
    private class TailRecordingInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
        private val ring = ByteArray(EOCD_MAX_SPAN)
        private var writePos = 0
        private var total = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) record(byteArrayOf(b.toByte()), 0, 1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) record(b, off, n)
            return n
        }

        override fun skip(n: Long): Long {
            // Route skips through read() so skipped bytes are counted and recorded too.
            val scratch = ByteArray(8 * 1024)
            var remaining = n
            while (remaining > 0) {
                val r = read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (r < 0) break
                remaining -= r
            }
            return n - remaining
        }

        override fun markSupported(): Boolean = false

        private fun record(b: ByteArray, off: Int, n: Int) {
            total += n
            if (total > maxBytes) throw BackupInputReader.InputTooLargeException()
            var o = off
            var left = n
            while (left > 0) {
                val chunk = minOf(left, ring.size - writePos)
                System.arraycopy(b, o, ring, writePos, chunk)
                writePos = (writePos + chunk) % ring.size
                o += chunk
                left -= chunk
            }
        }

        fun drainAndRequireZipEndRecord() {
            val scratch = ByteArray(8 * 1024)
            while (read(scratch, 0, scratch.size) >= 0) { /* count + record the remaining central directory */ }
            val available = minOf(total, ring.size.toLong()).toInt()
            if (available < EOCD_MIN) throw WdbkFormatException(DAMAGED)
            val tailBytes = ByteArray(available)
            val start = if (total >= ring.size) writePos else 0
            for (i in 0 until available) tailBytes[i] = ring[(start + i) % ring.size]
            // End record: PK\u0005\u0006, with comment length such that it ends exactly at end of file.
            var p = available - EOCD_MIN
            while (p >= 0) {
                if (tailBytes[p] == 0x50.toByte() && tailBytes[p + 1] == 0x4B.toByte() &&
                    tailBytes[p + 2] == 0x05.toByte() && tailBytes[p + 3] == 0x06.toByte()
                ) {
                    val commentLen = (tailBytes[p + 20].toInt() and 0xFF) or ((tailBytes[p + 21].toInt() and 0xFF) shl 8)
                    if (p + EOCD_MIN + commentLen == available) return
                }
                p--
            }
            throw WdbkFormatException(DAMAGED)
        }
    }

    private companion object {
        const val EOCD_MIN = 22
        const val EOCD_MAX_SPAN = EOCD_MIN + 65_535

        const val DAMAGED = "The backup file is damaged and cannot be restored."
        const val TOO_LARGE_TO_OPEN = "This backup is larger than Wavdrop can safely open."

        fun failure(message: String) = WavdropBackupImportResult(
            backup = null,
            error = message,
            integrityStatus = BackupIntegrityStatus.INVALID,
        )
    }
}
