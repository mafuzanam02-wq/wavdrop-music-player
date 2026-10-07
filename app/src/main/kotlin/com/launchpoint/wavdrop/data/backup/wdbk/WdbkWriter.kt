package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupFormatVersion
import com.launchpoint.wavdrop.data.backup.BackupManifest
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupExporterV2
import com.launchpoint.wavdrop.data.backup.WavdropBackupIntegrityV2
import org.json.JSONObject
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What a successful [WdbkWriter.write] recorded; the verifier compares the re-read container against it. */
data class WdbkWriteReceipt(
    val manifest: WdbkManifest,
    val containerBytes: Long,
    val entryCount: Int,
) {
    val fingerprint: String get() = manifest.fingerprint
    val backupId: String get() = manifest.backupId
}

/**
 * Streams a [WdbkExportSnapshot] (logical v2 content) into a WDBK container v1 on [output]: ZIP/DEFLATE, compact
 * UTF-8 JSON sections, listening history split into bounded chunks.
 *
 * True streaming: listen events are pulled from the snapshot's [WdbkEventSource] ONE CHUNK AT A TIME. For each chunk the
 * writer encodes one bounded JSON array, writes and hashes it as a ZIP entry, feeds the same events into the semantic
 * fingerprint, counts them, and drops the chunk before requesting the next. No complete event list, no whole-history
 * JSON document and no whole-archive byte array exist on this path; manifest event counts and chunk descriptors are
 * accumulated as counters. (The small sections - songs, stats, playlists, ... - are still encoded one section at a time.)
 *
 * Semantic fingerprint. The v2 fingerprint canonicalizes events in sorted order, so the source must deliver events in
 * [WavdropBackupIntegrityV2.EVENT_ORDER]; they are fed to [WavdropBackupIntegrityV2.StreamingFingerprint] as they are
 * written, producing byte-for-byte the value [WavdropBackupIntegrityV2.fingerprint] gives the reconstructed model.
 *
 * Entry order: sections, history chunks, extension, then `manifest.json` LAST, because the manifest records every
 * entry's SHA-256 and the fingerprint. The reader does not depend on physical order.
 *
 * Section JSON is produced by the same per-section codecs as the legacy v2 exporter ([WavdropBackupExporterV2]), so no
 * field mapping is duplicated. The caller owns [output] (the ZIP is finished and flushed, NOT closed): the caller must
 * close it and then re-read and verify the result.
 */
class WdbkWriter(
    private val eventsPerChunk: Int = WdbkLimits.EVENTS_PER_CHUNK,
    private val maxEntryBytes: Long = WdbkLimits.DEFAULT.maxEntryUncompressedBytes,
) {

    init {
        require(eventsPerChunk > 0) { "eventsPerChunk must be positive" }
    }

    suspend fun write(snapshot: WdbkExportSnapshot, output: OutputStream): WdbkWriteReceipt {
        val counting = CountingOutputStream(output)
        val zip = ZipOutputStream(counting)
        zip.setMethod(ZipOutputStream.DEFLATED)
        try {
            return writeEntries(snapshot, zip, counting)
        } finally {
            // Releases the Deflater; CountingOutputStream.close() only flushes, the caller keeps ownership.
            // A secondary failure while closing must never mask the real (primary) exception.
            try { zip.close() } catch (_: Exception) { }
        }
    }

    private suspend fun writeEntries(
        snapshot: WdbkExportSnapshot,
        zip: ZipOutputStream,
        counting: CountingOutputStream,
    ): WdbkWriteReceipt {
        val descriptors = mutableListOf<WdbkEntryDescriptor>()

        fun entry(
            path: String,
            section: String,
            bytes: ByteArray,
            required: Boolean = true,
            chunkIndex: Int? = null,
            eventCount: Int? = null,
        ) {
            if (bytes.size > maxEntryBytes) {
                // Fail loudly at export instead of producing a container this app could not open again.
                throw IOException("Backup section $section is too large to save.")
            }
            putEntry(zip, path, bytes)
            descriptors += WdbkEntryDescriptor(
                path = path,
                section = section,
                sectionVersion = WdbkContainerVersion.SECTION_VERSION,
                required = required,
                byteLength = bytes.size.toLong(),
                sha256 = sha256Hex(bytes),
                chunkIndex = chunkIndex,
                eventCount = eventCount,
            )
        }

        // Semantic fingerprint: header + every non-event section now, events as they stream, preferences last.
        val fingerprint = WavdropBackupIntegrityV2.StreamingFingerprint(
            snapshot.backupId, snapshot.sourceInstallationId, snapshot.exportedAtMs,
            snapshot.songs, snapshot.trackStats, snapshot.importBaselines, snapshot.lyricsOverrides, snapshot.playlists,
        )

        entry(WdbkLayout.SONGS, WdbkLayout.Section.SONGS, utf8(WavdropBackupExporterV2.songsArray(snapshot.songs).toString()))
        entry(WdbkLayout.TRACK_STATS, WdbkLayout.Section.TRACK_STATS, utf8(WavdropBackupExporterV2.trackStatsArray(snapshot.trackStats).toString()))
        entry(WdbkLayout.IMPORT_BASELINES, WdbkLayout.Section.IMPORT_BASELINES, utf8(WavdropBackupExporterV2.baselinesArray(snapshot.importBaselines).toString()))
        entry(WdbkLayout.LYRICS_OVERRIDES, WdbkLayout.Section.LYRICS_OVERRIDES, utf8(WavdropBackupExporterV2.lyricsOverridesArray(snapshot.lyricsOverrides).toString()))
        // Preferences rule (single, deterministic): the entry exists IFF the backup has preferences. An absent
        // entry reconstructs preferences == null, exactly like a v2 JSON without a "preferences" key.
        snapshot.preferences?.let { prefs ->
            entry(WdbkLayout.PREFERENCES, WdbkLayout.Section.PREFERENCES, utf8(WavdropBackupExporterV2.platformPreferencesObject(prefs).toString()))
        }
        entry(WdbkLayout.PLAYLISTS, WdbkLayout.Section.PLAYLISTS, utf8(WavdropBackupExporterV2.playlistsArray(snapshot.playlists).toString()))

        // History: pull, encode, write, hash, count, DROP - one bounded chunk at a time. Chunk boundaries carry no
        // meaning (the fingerprint is order-canonical), only the deterministic names and counts.
        val source = snapshot.openEvents()
        var chunkIndex = 0
        var totalEvents = 0L
        while (true) {
            val chunk = source.nextChunk(eventsPerChunk)
            if (chunk.isEmpty()) break
            check(chunk.size <= eventsPerChunk) { "Event source returned more than the requested chunk size" }
            chunk.forEach(fingerprint::addEvent)
            entry(
                path = WdbkLayout.historyPath(chunkIndex),
                section = WdbkLayout.Section.LISTEN_EVENTS,
                bytes = utf8(WavdropBackupExporterV2.listenEventsArray(chunk).toString()),
                chunkIndex = chunkIndex,
                eventCount = chunk.size,
            )
            totalEvents += chunk.size
            chunkIndex++
            // `chunk` goes out of scope here; nothing retains it (descriptors hold only counters and a digest).
        }
        check(totalEvents <= Int.MAX_VALUE) { "Too many listen events for a single backup" }

        // Desktop overlay: preserved raw, never reinterpreted. Optional extension - older readers may skip it.
        snapshot.desktopOverlayRawJson?.let { raw ->
            entry(
                WdbkLayout.DESKTOP_OVERLAY,
                WdbkLayout.Section.DESKTOP_OVERLAY,
                utf8(JSONObject(raw).toString()),
                required = false,
            )
        }

        // Section counts come from the small sections plus the streamed counter - never from a full-history model.
        val counts = BackupManifest.of(
            WavdropBackup(
                exportedAt = "",
                songs = snapshot.songs,
                trackStats = snapshot.trackStats,
                importBaselines = snapshot.importBaselines,
                lyricsOverrides = snapshot.lyricsOverrides,
                preferences = snapshot.preferences,
                playlists = snapshot.playlists,
                sourceVersion = BackupFormatVersion.V2,
            ),
        ).copy(listenEventCount = totalEvents.toInt())

        val manifest = WdbkManifest(
            format = WdbkContainerVersion.FORMAT,
            containerMajor = WdbkContainerVersion.MAJOR,
            containerMinor = WdbkContainerVersion.MINOR,
            logicalFormat = WdbkContainerVersion.LOGICAL_FORMAT,
            logicalVersion = WdbkContainerVersion.LOGICAL_VERSION,
            backupId = snapshot.backupId,
            sourceInstallationId = snapshot.sourceInstallationId,
            exportedAtMs = snapshot.exportedAtMs,
            producerPlatform = "android",
            appVersionCode = snapshot.appVersionCode,
            appVersionName = snapshot.appVersionName,
            requiredCapabilities = emptyList(),
            optionalCapabilities = emptyList(),
            counts = counts,
            fingerprint = fingerprint.finish(snapshot.preferences),
            entries = descriptors.toList(),
        )
        putEntry(zip, WdbkLayout.MANIFEST, manifest.toJsonBytes())

        zip.finish()
        zip.flush()
        return WdbkWriteReceipt(manifest, counting.count, entryCount = descriptors.size + 1)
    }

    private fun putEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.DEFLATED
        // Fixed timestamp: entry metadata carries no information and must not make identical content differ.
        entry.time = FIXED_ENTRY_TIME_MS
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        // Never close the caller's stream from here; the caller owns it.
        override fun close() = flush()
    }

    internal companion object {
        /** 1980-01-01T00:00:00Z — the ZIP/DOS epoch. */
        const val FIXED_ENTRY_TIME_MS = 315_532_800_000L

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
