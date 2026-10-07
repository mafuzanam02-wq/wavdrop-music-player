package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupFormatVersion
import com.launchpoint.wavdrop.data.backup.BackupIntegrityStatus
import com.launchpoint.wavdrop.data.backup.BackupManifest
import com.launchpoint.wavdrop.data.backup.RecoveryEligibility
import com.launchpoint.wavdrop.data.backup.RecoveryRestorePlanner
import com.launchpoint.wavdrop.data.backup.ListenEventRestorePlanner
import com.launchpoint.wavdrop.data.backup.PlaylistEntryRestorePlanner
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupExporterV2
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportResult
import com.launchpoint.wavdrop.data.backup.WavdropBackupIntegrityV2
import com.launchpoint.wavdrop.data.backup.WavdropBackupParser
import com.launchpoint.wavdrop.data.backup.WavdropMergePreviewAnalyzer
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.read
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.unzip
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkTestSupport.write
import com.launchpoint.wavdrop.data.model.Song
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** The equivalence gate: the same logical backup written as legacy v2 JSON and as WDBK decodes to the same model. */
class WdbkRoundTripTest {

    private fun legacy(backup: WavdropBackup): WavdropBackupImportResult =
        WavdropBackupParser.parse(WavdropBackupExporterV2.toJson(backup), WdbkTestSupport.NOW + 1_000_000L)

    private fun wdbk(backup: WavdropBackup): WavdropBackupImportResult = read(write(backup).first)

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album", albumId = 1L, duration = 180_000L + id,
        uri = "content://media/$id", dateAdded = 1_000L, trackNumber = 1, year = 2020, folderPath = "Music/", folderName = "Music",
    )

    // ── Model equivalence ─────────────────────────────────────────────────────

    @Test fun `full backup decodes to exactly the model the legacy v2 parser produces`() {
        val backup = WdbkTestSupport.fullBackup()
        val old = legacy(backup)
        val new = wdbk(backup)

        assertNull(new.error)
        assertEquals(BackupIntegrityStatus.VERIFIED, new.integrityStatus)
        assertEquals(old.integrityStatus, new.integrityStatus)
        assertEquals(BackupFormatVersion.V2, new.backup!!.sourceVersion)
        // The whole domain object, field for field: metadata, songs, stats, favourites, lastListenedAt, baselines,
        // lyrics, preferences, playlists (order + duplicate occurrences), events (+ eventId) and the Desktop overlay.
        assertEquals(old.backup, new.backup)
        assertEquals(
            WavdropBackupIntegrityV2.fingerprint(old.backup!!),
            WavdropBackupIntegrityV2.fingerprint(new.backup!!),
        )
    }

    @Test fun `every section is preserved individually`() {
        val src = WdbkTestSupport.fullBackup()
        val out = wdbk(src).backup!!
        assertEquals(src.backupId, out.backupId)
        assertEquals(src.sourceInstallationId, out.sourceInstallationId)
        assertEquals(src.exportedAtMs, out.exportedAtMs)
        assertEquals(src.songs, out.songs)
        assertEquals(src.trackStats, out.trackStats)
        assertTrue(out.trackStats.first { it.songId == 1L }.isFavorite)
        assertEquals(0L, out.trackStats.first { it.songId == 3L }.lastListenedAt)
        assertEquals(src.importBaselines, out.importBaselines)
        assertEquals(src.lyricsOverrides, out.lyricsOverrides)
        assertEquals(src.preferences, out.preferences)
        assertEquals(src.playlists, out.playlists)
        assertEquals(listOf(1L, 3L, 1L), out.playlists.first().songs.map { it.songId }) // duplicate occurrence kept
        assertEquals(listOf(0, 1, 2), out.playlists.first().songs.map { it.position })
        assertEquals(src.listenEvents, out.listenEvents)
        assertTrue(out.listenEvents.any { it.eventId == null })
        assertTrue(out.listenEvents.any { it.eventId == "evt-x" })
    }

    @Test fun `empty backup with every optional section empty round-trips`() {
        val backup = WdbkTestSupport.emptyBackup()
        val old = legacy(backup)
        val new = wdbk(backup)
        assertNull(new.error)
        assertEquals(old.backup, new.backup)
        assertNull(new.backup!!.preferences)
        assertNull(new.backup!!.desktopOverlay)
        assertTrue(new.backup!!.listenEvents.isEmpty())
    }

    @Test fun `lossless on a fixture with the maximum field variety`() {
        val backup = WdbkTestSupport.fullBackup(eventCount = 4_321)
        assertEquals(legacy(backup).backup, wdbk(backup).backup)
    }

    // ── Preferences rule ──────────────────────────────────────────────────────

    @Test fun `preferences entry exists if and only if the backup has preferences`() {
        val without = write(WdbkTestSupport.emptyBackup()).first
        assertFalse(WdbkLayout.PREFERENCES in unzip(without))
        assertNull(read(without).backup!!.preferences)

        val allNull = RecoveryTestFixtures.v2Backup(preferences = RecoveryTestFixtures.emptyPrefs())
        val withEmpty = write(allNull).first
        assertTrue(WdbkLayout.PREFERENCES in unzip(withEmpty))
        // An all-null preferences object stays a non-null object, exactly like v2 JSON {"android":{}}.
        assertEquals(allNull.preferences, read(withEmpty).backup!!.preferences)
        assertNotNull(read(withEmpty).backup!!.preferences)
        assertEquals(legacy(allNull).backup!!.preferences, read(withEmpty).backup!!.preferences)
    }

    // ── Desktop overlay ───────────────────────────────────────────────────────

    @Test fun `desktop overlay is stored in its own optional entry and preserved raw including unknown fields`() {
        val backup = WdbkTestSupport.fullBackup(overlay = true)
        val (bytes, receipt) = write(backup)
        val entries = unzip(bytes)
        assertTrue(WdbkLayout.DESKTOP_OVERLAY in entries)
        val descriptor = receipt.manifest.entries.first { it.path == WdbkLayout.DESKTOP_OVERLAY }
        assertFalse("overlay is an optional extension", descriptor.required)

        val out = read(bytes).backup!!.desktopOverlay!!
        val old = legacy(backup).backup!!.desktopOverlay!!
        assertEquals(old, out)
        // Unknown field survives, unreinterpreted.
        val raw = JSONObject(out.rawJson)
        assertEquals(3, raw.getJSONObject("unknownFutureField").getJSONArray("keep").length())
        assertEquals("d1", out.trackStats.single().desktopTrackId)
    }

    @Test fun `no overlay entry exists when there is no overlay`() {
        val bytes = write(WdbkTestSupport.fullBackup(overlay = false)).first
        assertFalse(WdbkLayout.DESKTOP_OVERLAY in unzip(bytes))
        assertNull(read(bytes).backup!!.desktopOverlay)
    }

    // ── Container structure ───────────────────────────────────────────────────

    @Test fun `entry layout is deterministic and the manifest is written last`() {
        val bytes = write(WdbkTestSupport.fullBackup(eventCount = 3), eventsPerChunk = 4).first
        assertEquals(
            listOf(
                "sections/songs.json", "sections/track-stats.json", "sections/import-baselines.json",
                "sections/lyrics-overrides.json", "sections/preferences.json", "sections/playlists.json",
                "history/listen-events-000000.json", "history/listen-events-000001.json",
                "extensions/desktop-overlay.json", "manifest.json",
            ),
            unzip(bytes).keys.toList(),
        )
    }

    @Test fun `entry names do not depend on the default locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            for (tag in listOf("ar-SA-u-nu-arab", "hi-IN-u-nu-deva", "fa-IR", "th-TH-u-nu-thai")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                assertEquals(tag, "history/listen-events-000042.json", WdbkLayout.historyPath(42))
                val bytes = write(WdbkTestSupport.fullBackup(eventCount = 9), eventsPerChunk = 4).first
                assertNull(tag, read(bytes).error)
            }
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun `same backup writes byte-identical containers`() {
        val backup = WdbkTestSupport.fullBackup(eventCount = 50)
        assertArrayEquals(write(backup, 8).first, write(backup, 8).first)
    }

    @Test fun `entries are DEFLATED and section payloads are compact UTF-8 JSON`() {
        val bytes = write(WdbkTestSupport.fullBackup()).first
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var seen = 0
            while (true) {
                val e = zip.nextEntry ?: break
                assertEquals(e.name, ZipEntry.DEFLATED, e.method)
                val text = String(zip.readBytes(), Charsets.UTF_8)
                assertFalse("${e.name} must not be pretty-printed", text.contains("\n"))
                assertFalse("${e.name} must not be pretty-printed", text.contains("  "))
                seen++
            }
            assertTrue(seen > 5)
        }
    }

    @Test fun `manifest carries identity, versions, counts, fingerprint and a descriptor per payload entry`() {
        val backup = WdbkTestSupport.fullBackup(eventCount = 10)
        val (bytes, receipt) = write(backup, eventsPerChunk = 4)
        val m = JSONObject(String(unzip(bytes).getValue("manifest.json"), Charsets.UTF_8))
        assertEquals("wavdrop_wdbk", m.getString("format"))
        assertEquals(1, m.getInt("containerMajor"))
        assertEquals(0, m.getInt("containerMinor"))
        assertEquals("wavdrop_backup", m.getString("logicalFormat"))
        assertEquals(2, m.getInt("logicalVersion"))
        assertEquals(backup.backupId, m.getString("backupId"))
        assertEquals(backup.sourceInstallationId, m.getString("sourceInstallationId"))
        assertEquals(backup.exportedAtMs, m.getLong("exportedAt"))
        assertEquals("android", m.getJSONObject("producer").getString("platform"))
        assertEquals(0, m.getJSONArray("requiredCapabilities").length())
        assertEquals(0, m.getJSONArray("optionalCapabilities").length())
        assertEquals(BackupManifest.of(backup).songCount, m.getJSONObject("counts").getInt("songCount"))
        assertEquals(backup.listenEvents.size, m.getJSONObject("counts").getInt("listenEventCount"))
        assertEquals(WavdropBackupIntegrityV2.fingerprint(backup), m.getJSONObject("integrity").getString("fingerprint"))

        val files = unzip(bytes)
        val entries = m.getJSONArray("entries")
        assertEquals(files.size - 1, entries.length()) // every payload entry, not the manifest itself
        for (i in 0 until entries.length()) {
            val d = entries.getJSONObject(i)
            val content = files.getValue(d.getString("path"))
            assertEquals(content.size.toLong(), d.getLong("byteLength"))
            assertEquals(WdbkWriter.sha256Hex(content), d.getString("sha256"))
            assertEquals(1, d.getInt("sectionVersion"))
            assertTrue(d.has("required"))
            if (d.getString("section") == "listenEvents") {
                assertTrue(d.has("chunkIndex"))
                assertTrue(d.getInt("eventCount") in 1..4)
            }
        }
        assertEquals(receipt.entryCount, files.size)
    }

    @Test fun `container version is independent of the logical backup version`() {
        assertEquals(1, WdbkContainerVersion.MAJOR)
        assertEquals(0, WdbkContainerVersion.MINOR)
        assertEquals(2, WdbkContainerVersion.LOGICAL_VERSION)
        assertEquals(WavdropBackupParser.SUPPORTED_VERSION, WdbkContainerVersion.LOGICAL_VERSION)
    }

    // ── Restore-semantics equivalence (decoded legacy vs decoded WDBK feed identical inputs) ──────────────────

    @Test fun `recovery eligibility and planner see identical inputs from legacy and wdbk`() {
        val backup = WdbkTestSupport.fullBackup()
        val old = legacy(backup)
        val new = wdbk(backup)

        val oldElig = RecoveryEligibility.evaluate(old) as RecoveryEligibility.Result.Eligible
        val newElig = RecoveryEligibility.evaluate(new) as RecoveryEligibility.Result.Eligible
        assertEquals(oldElig.backup, newElig.backup)

        val songs = listOf(song(1L), song(3L), song(9_007_199_254_740_993L))
        val oldPlan = RecoveryRestorePlanner.plan(old.backup!!, songs)
        val newPlan = RecoveryRestorePlanner.plan(new.backup!!, songs)
        assertEquals(oldPlan, newPlan)
        assertTrue(newPlan.stats.isNotEmpty())
        assertEquals(backup.listenEvents.size - newPlan.eventPlan.skippedTotal, newPlan.eventPlan.restored)
    }

    @Test fun `event restore and playlist planning are identical for legacy and wdbk`() {
        val backup = WdbkTestSupport.fullBackup(eventCount = 25)
        val old = legacy(backup).backup!!
        val new = wdbk(backup).backup!!
        val songs = listOf(song(1L), song(3L), song(9_007_199_254_740_993L)).associateBy { it.id }

        fun events(b: WavdropBackup) = ListenEventRestorePlanner.plan(
            events = b.listenEvents,
            resolveSong = { songs[it.songId] },
            existingFingerprints = emptySet(),
            nowMs = WdbkTestSupport.NOW,
            zone = java.time.ZoneOffset.UTC,
        )
        assertEquals(events(old), events(new))

        fun playlists(b: WavdropBackup) = b.playlists.map { p ->
            PlaylistEntryRestorePlanner.plan(p.songs, resolve = { songs[it.songId] }, existingSongIds = emptySet(), nextPosition = 0)
        }
        assertEquals(playlists(old), playlists(new))
    }

    @Test fun `merge preview analysis is identical for legacy and wdbk`() {
        val backup = WdbkTestSupport.fullBackup()
        val old = legacy(backup).backup!!
        val new = wdbk(backup).backup!!
        val songs = listOf(song(1L), song(3L))

        fun analyze(b: WavdropBackup) = WavdropMergePreviewAnalyzer.analyze(
            backup = b,
            currentSongs = songs,
            existingQuarantineOriginKeys = emptySet(),
            existingStats = emptyMap(),
            existingEventFingerprints = emptySet(),
            existingBaselines = emptyList(),
            existingLyrics = emptyMap(),
            existingPlaylists = emptyMap(),
        )
        assertEquals(analyze(old), analyze(new))
        assertTrue(analyze(new).hasMergeableData)
    }

    @Test fun `stats merge deltas are identical for legacy and wdbk`() {
        val backup = WdbkTestSupport.fullBackup()
        val old = legacy(backup).backup!!
        val new = wdbk(backup).backup!!
        fun deltas(b: WavdropBackup) = b.trackStats.map {
            com.launchpoint.wavdrop.data.backup.StatsImportMerger.computeEffect(3, 0, 1_000L, it.playCount, it.skipCount, it.totalListeningTimeMs)
        }
        assertEquals(deltas(old), deltas(new))
    }

    @Test fun `a legacy v1 backup is not affected - still V1 and still not eligible for Recovery`() {
        val v1 = WavdropBackupParser.parse(RecoveryTestFixtures.v1Json())
        assertEquals(BackupFormatVersion.V1, v1.backup!!.sourceVersion)
        assertTrue(RecoveryEligibility.evaluate(v1) is RecoveryEligibility.Result.Blocked)
    }
}
