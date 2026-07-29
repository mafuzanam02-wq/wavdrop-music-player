package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WD-05 — plausibility validation for imported portable statistics.
 *
 * Validates that implausible or overflow-adjacent stat magnitudes are rejected
 * (Android sealed root) or preserved-but-not-applied (Desktop overlay), while
 * realistic values import unchanged. Validation always runs after integrity
 * verification, so the fingerprint formula is untouched.
 */
class ImportedStatPlausibilityTest {

    // A fixed "now" well after the fixture timestamps (~2026) so tests are
    // deterministic regardless of wall-clock. ~2033.
    private val nowMs = 2_000_000_000_000L

    private val realisticLastPlayed = 1_782_230_000_000L   // ~2026, in the past
    private val realisticLastListened = 1_782_230_100_000L

    // ── Model builders ────────────────────────────────────────────────────────

    private fun v2Backup(
        playCount: Int = 10,
        skipCount: Int = 1,
        totalListeningTimeMs: Long = 180_000L,
        lastPlayedAt: Long = realisticLastPlayed,
        lastListenedAt: Long = realisticLastListened,
    ) = WavdropBackup(
        exportedAt = "",
        exportedAtMs = 1_782_230_400_000L,
        backupId = "00000000-0000-0000-0000-000000000001",
        sourceInstallationId = "00000000-0000-0000-0000-000000000002",
        sourceVersion = BackupFormatVersion.V2,
        songs = listOf(
            BackupSong(3316L, "content://media/3316", "Ghost Song", "Doors",
                "Other Voices", 42L, 180_000L, 1_000_000L, 1, 1971),
        ),
        trackStats = listOf(
            BackupTrackStats(3316L, "content://media/3316", playCount, skipCount,
                lastPlayedAt = lastPlayedAt,
                totalListeningTimeMs = totalListeningTimeMs,
                isFavorite = false,
                lastListenedAt = lastListenedAt),
        ),
        importBaselines = emptyList(),
    )

    private fun parseV2(backup: WavdropBackup) =
        WavdropBackupParser.parse(WavdropBackupExporterV2.toJson(backup), nowMs)

    private fun ghostSong() = Song(
        id = 44L,
        title = "Ghost Song",
        artist = "Doors",
        album = "Other Voices",
        albumId = 42L,
        duration = 180_000L,
        uri = "content://media/44",
        dateAdded = 0L,
        trackNumber = 1,
        year = 1971,
    )

    // ── 1. Realistic values import unchanged ──────────────────────────────────

    @Test
    fun `realistic stat values import unchanged`() {
        val result = parseV2(v2Backup())
        assertNotNull("Realistic backup must parse: ${result.error}", result.backup)
        assertEquals(10, result.backup!!.trackStats[0].playCount)
        assertEquals(1, result.backup!!.trackStats[0].skipCount)
    }

    // ── 2. Maximum allowed playCount imports ──────────────────────────────────

    @Test
    fun `maximum allowed playCount imports`() {
        val result = parseV2(v2Backup(playCount = ImportedStatPlausibility.MAX_PLAY_COUNT))
        assertNotNull("Max playCount must be accepted: ${result.error}", result.backup)
        assertEquals(
            ImportedStatPlausibility.MAX_PLAY_COUNT,
            result.backup!!.trackStats[0].playCount,
        )
    }

    // ── 3. playCount above maximum is rejected ────────────────────────────────

    @Test
    fun `playCount above maximum is rejected`() {
        val result = parseV2(v2Backup(playCount = ImportedStatPlausibility.MAX_PLAY_COUNT + 1))
        assertNull(result.backup)
        assertEquals(WavdropBackupParser.IMPLAUSIBLE_STATS_ERROR, result.error)
    }

    @Test
    fun `playCount near Int MAX is rejected`() {
        val result = parseV2(v2Backup(playCount = Int.MAX_VALUE))
        assertNull(result.backup)
        assertEquals(WavdropBackupParser.IMPLAUSIBLE_STATS_ERROR, result.error)
    }

    // ── 4. Negative playCount is rejected ─────────────────────────────────────

    @Test
    fun `negative playCount is rejected`() {
        val result = parseV2(v2Backup(playCount = -1))
        assertNull(result.backup)
        assertEquals(WavdropBackupParser.IMPLAUSIBLE_STATS_ERROR, result.error)
    }

    // ── 5. skipCount above maximum is rejected ────────────────────────────────

    @Test
    fun `skipCount above maximum is rejected`() {
        val result = parseV2(v2Backup(skipCount = ImportedStatPlausibility.MAX_SKIP_COUNT + 1))
        assertNull(result.backup)
        assertEquals(WavdropBackupParser.IMPLAUSIBLE_STATS_ERROR, result.error)
    }

    // ── 6. totalListeningTimeMs above maximum is rejected ─────────────────────

    @Test
    fun `totalListeningTimeMs above maximum is rejected`() {
        val result = parseV2(
            v2Backup(totalListeningTimeMs = ImportedStatPlausibility.MAX_TOTAL_LISTENING_TIME_MS + 1),
        )
        assertNull(result.backup)
        assertEquals(WavdropBackupParser.IMPLAUSIBLE_STATS_ERROR, result.error)
    }

    // ── 7. Timestamp within allowed skew imports ──────────────────────────────

    @Test
    fun `timestamp within clock skew allowance imports`() {
        val withinSkew = nowMs + ImportedStatPlausibility.CLOCK_SKEW_ALLOWANCE_MS - 1
        val result = parseV2(v2Backup(lastPlayedAt = withinSkew, lastListenedAt = withinSkew))
        assertNotNull("Timestamp within skew must be accepted: ${result.error}", result.backup)
    }

    // ── 8. Timestamp beyond allowed skew is rejected ──────────────────────────

    @Test
    fun `lastPlayedAt beyond clock skew allowance is rejected`() {
        val beyondSkew = nowMs + ImportedStatPlausibility.CLOCK_SKEW_ALLOWANCE_MS + 1
        val result = parseV2(v2Backup(lastPlayedAt = beyondSkew))
        assertNull(result.backup)
        assertEquals(WavdropBackupParser.IMPLAUSIBLE_STATS_ERROR, result.error)
    }

    @Test
    fun `lastListenedAt far in the future is rejected`() {
        val result = parseV2(v2Backup(lastListenedAt = nowMs + 365L * 24 * 60 * 60 * 1000))
        assertNull(result.backup)
        assertEquals(WavdropBackupParser.IMPLAUSIBLE_STATS_ERROR, result.error)
    }

    // ── Fingerprint is unchanged by validation ────────────────────────────────

    @Test
    fun `plausibility runs after integrity — implausible backup still has valid fingerprint`() {
        // The exporter computes a correct fingerprint over the implausible model;
        // rejection is by plausibility, not integrity — proving no mutation occurred.
        val implausible = v2Backup(playCount = Int.MAX_VALUE)
        val json = WavdropBackupExporterV2.toJson(implausible)
        val parsed = WavdropBackupParser.parse(json, nowMs)
        assertNull(parsed.backup)
        // Not an integrity error — the fingerprint matched.
        assertEquals(WavdropBackupParser.IMPLAUSIBLE_STATS_ERROR, parsed.error)
    }

    // ── 10. Re-import remains idempotent ──────────────────────────────────────

    @Test
    fun `re-import of realistic backup is deterministic`() {
        val json = WavdropBackupExporterV2.toJson(v2Backup())
        val first = WavdropBackupParser.parse(json, nowMs)
        val second = WavdropBackupParser.parse(json, nowMs)
        assertNotNull(first.backup)
        assertNotNull(second.backup)
        assertEquals(first.backup!!.trackStats, second.backup!!.trackStats)
    }

    // ── 9. Desktop overlay: invalid row preserved, not applied ────────────────

    @Test
    fun `implausible desktop overlay stat is preserved as unresolved and not applied`() {
        val overlay = BackupDesktopOverlay(
            schemaVersion = 1,
            producerPlatform = "desktop",
            trackStats = listOf(
                BackupDesktopOverlayTrackStats(
                    desktopTrackId = "desktop-track-1",
                    title = "Ghost Song", artist = "Doors", album = "Other Voices",
                    durationMs = 180_000L,
                    playCount = ImportedStatPlausibility.MAX_PLAY_COUNT + 1, // implausible
                    skipCount = 0,
                    totalListeningTimeMs = 0L,
                    lastPlayedAt = 0L,
                    lastListenedAt = 0L,
                    favorite = true,
                ),
            ),
            listenEvents = emptyList(),
            rawJson = """{"schemaVersion":1,"keep":"me"}""",
        )

        val plan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = listOf(ghostSong()),
            currentStats = emptyMap(),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
            nowMs = nowMs,
        )

        assertEquals("implausible row must not be applied", 0, plan.matchedStats.size)
        assertEquals("implausible row must be preserved as unresolved", 1, plan.unresolvedStats.size)
        assertTrue(plan.hasPreservedOverlayRows)
        assertFalse(plan.hasWrites)
        // Raw overlay preserved verbatim regardless.
        assertEquals("""{"schemaVersion":1,"keep":"me"}""", overlay.rawJson)
    }

    @Test
    fun `plausible desktop overlay stat is still applied`() {
        val overlay = BackupDesktopOverlay(
            schemaVersion = 1,
            producerPlatform = "desktop",
            trackStats = listOf(
                BackupDesktopOverlayTrackStats(
                    desktopTrackId = "desktop-track-1",
                    title = "Ghost Song", artist = "Doors", album = "Other Voices",
                    durationMs = 180_000L,
                    playCount = 5, skipCount = 2, totalListeningTimeMs = 240_000L,
                    lastPlayedAt = realisticLastPlayed, lastListenedAt = realisticLastListened,
                    favorite = true,
                ),
            ),
            listenEvents = emptyList(),
            rawJson = "{}",
        )

        val plan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = listOf(ghostSong()),
            currentStats = mapOf(44L to TrackStatsEntity(songId = 44L, contentUri = "content://media/44")),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
            nowMs = nowMs,
        )

        assertEquals(1, plan.matchedStats.size)
        assertEquals(0, plan.unresolvedStats.size)
        assertTrue(plan.hasWrites)
    }

    // ── Policy-level unit checks ──────────────────────────────────────────────

    @Test
    fun `policy bounds are computed without overflow`() {
        // 100 years in ms, positive and within Long range.
        assertTrue(ImportedStatPlausibility.MAX_TOTAL_LISTENING_TIME_MS > 0L)
        assertEquals(3_153_600_000_000L, ImportedStatPlausibility.MAX_TOTAL_LISTENING_TIME_MS)
        assertEquals(604_800_000L, ImportedStatPlausibility.CLOCK_SKEW_ALLOWANCE_MS)
    }

    @Test
    fun `null lastListenedAt (v1) is accepted by the policy`() {
        assertTrue(
            ImportedStatPlausibility.isPlausibleTrackStats(
                playCount = 10, skipCount = 1, totalListeningTimeMs = 180_000L,
                lastPlayedAt = realisticLastPlayed, lastListenedAt = null, nowMs = nowMs,
            ),
        )
    }
}
