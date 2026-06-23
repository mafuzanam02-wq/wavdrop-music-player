package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.ListeningPeriodRange
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.stats.ListeningAnalyticsBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ListenEventRestorePlannerTest {

    private val utc: ZoneId = ZoneOffset.UTC

    // June 2026 is "the current month" for these tests.
    private val nowMs = epochMs(2026, 6, 11)

    private fun epochMs(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(utc).toInstant().toEpochMilli()

    private fun song(id: Long, uri: String = "content://media/$id") = Song(
        id = id, title = "Title $id", artist = "Artist $id", album = "Album $id",
        albumId = 0L, duration = 180_000L, uri = uri, dateAdded = 0L,
        trackNumber = 0, year = 0,
    )

    private fun event(
        songId: Long,
        occurredAt: Long,
        uri: String = "content://media/OLD/$songId",
        type: String = TrackListenEventEntity.TYPE_PLAY,
        listenedMs: Long = 30_000L,
        durationMs: Long = 180_000L,
    ) = BackupListenEvent(
        songId = songId, contentUri = uri,
        title = "Title $songId", artist = "Artist $songId", album = "Album $songId",
        eventType = type, occurredAt = occurredAt,
        listenedMs = listenedMs, durationMs = durationMs, source = "wavdrop_playback",
    )

    @Test
    fun `current-month event restores and is counted as current-month`() {
        val local = song(7L)
        val plan = ListenEventRestorePlanner.plan(
            events = listOf(event(songId = 1L, occurredAt = epochMs(2026, 6, 5))),
            resolveSong = { local },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
        )
        assertEquals(1, plan.restored)
        assertEquals(1, plan.currentMonthRestored)
        assertEquals(7L, plan.toInsert.single().songId)
        assertEquals(epochMs(2026, 6, 5), plan.toInsert.single().occurredAt)
        assertEquals(TrackListenEventEntity.SOURCE_MANUAL_RESTORE, plan.toInsert.single().source)
    }

    @Test
    fun `restored current-month event appears in monthly report builder`() {
        val local = song(7L)
        val plan = ListenEventRestorePlanner.plan(
            events = listOf(event(songId = 1L, occurredAt = epochMs(2026, 6, 5))),
            resolveSong = { local },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
        )

        val range = ListeningPeriodRange.month(2026, 6, utc)
        val summary = ListeningAnalyticsBuilder.build(range, listOf(local), emptyList(), plan.toInsert)

        assertEquals(1, summary.totalPlayCount)
        assertEquals(30_000L, summary.totalListeningTimeMs)
    }

    @Test
    fun `restoring the same backup twice does not duplicate events`() {
        val local = song(7L)
        val events = listOf(event(songId = 1L, occurredAt = epochMs(2026, 6, 5)))

        val first = ListenEventRestorePlanner.plan(
            events, { local }, emptySet(), nowMs, utc,
        )
        val existing = first.toInsert
            .map { "${it.songId}|${it.occurredAt}|${it.eventType}|${it.listenedMs}" }
            .toSet()
        val second = ListenEventRestorePlanner.plan(
            events, { local }, existing, nowMs, utc,
        )

        assertEquals(1, first.restored)
        assertEquals(0, second.restored)
        assertEquals(1, second.skippedDuplicate)
    }

    @Test
    fun `two listens of the same song at different times both restore`() {
        val local = song(7L)
        val plan = ListenEventRestorePlanner.plan(
            events = listOf(
                event(songId = 1L, occurredAt = epochMs(2026, 6, 5)),
                event(songId = 1L, occurredAt = epochMs(2026, 6, 6)),
            ),
            resolveSong = { local },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
        )
        assertEquals(2, plan.restored)
        assertEquals(0, plan.skippedDuplicate)
    }

    @Test
    fun `old-month event restores but does not count as current-month`() {
        val local = song(7L)
        val plan = ListenEventRestorePlanner.plan(
            events = listOf(event(songId = 1L, occurredAt = epochMs(2026, 3, 10))),
            resolveSong = { local },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
        )
        assertEquals(1, plan.restored)
        assertEquals(0, plan.currentMonthRestored)
    }

    @Test
    fun `unmatched event is counted, not silently dropped`() {
        val plan = ListenEventRestorePlanner.plan(
            events = listOf(event(songId = 1L, occurredAt = epochMs(2026, 6, 5))),
            resolveSong = { null },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
        )
        assertEquals(0, plan.restored)
        assertEquals(1, plan.skippedUnmatched)
    }

    @Test
    fun `negative listenedMs is skipped as invalid`() {
        val plan = planInvalidEvent(event(songId = 1L, occurredAt = epochMs(2026, 6, 5), listenedMs = -1L))

        assertEquals(0, plan.restored)
        assertEquals(1, plan.skippedInvalidType)
    }

    @Test
    fun `zero listenedMs is skipped as invalid`() {
        val plan = planInvalidEvent(event(songId = 1L, occurredAt = epochMs(2026, 6, 5), listenedMs = 0L))

        assertEquals(0, plan.restored)
        assertEquals(1, plan.skippedInvalidType)
    }

    @Test
    fun `negative occurredAt is skipped as invalid`() {
        val plan = planInvalidEvent(event(songId = 1L, occurredAt = -1L))

        assertEquals(0, plan.restored)
        assertEquals(1, plan.skippedInvalidType)
    }

    @Test
    fun `zero occurredAt is skipped as invalid`() {
        val plan = planInvalidEvent(event(songId = 1L, occurredAt = 0L))

        assertEquals(0, plan.restored)
        assertEquals(1, plan.skippedInvalidType)
    }

    @Test
    fun `negative durationMs is skipped as invalid`() {
        val plan = planInvalidEvent(
            event(songId = 1L, occurredAt = epochMs(2026, 6, 5), durationMs = -1L),
        )

        assertEquals(0, plan.restored)
        assertEquals(1, plan.skippedInvalidType)
    }

    @Test
    fun `event with matched track after URI change restores to new song id`() {
        // Simulates reinstall: backup URIs are dead, but the tier matcher resolves
        // the backup song to the rescanned library song with a new id.
        val rescanned = song(42L, uri = "content://media/NEW/42")
        val backup = WavdropBackup(
            exportedAt = "2026-06-01T00:00:00Z",
            songs = listOf(
                BackupSong(
                    id = 1L, uri = "content://media/OLD/1",
                    title = "Title 42", artist = "Artist 42", album = "Album 42",
                    albumId = 0L, duration = 180_000L, dateAdded = 0L,
                    trackNumber = 0, year = 0,
                ),
            ),
            trackStats = emptyList(),
            importBaselines = emptyList(),
        )
        val resolved = WavdropBackupStatsMatcher.resolveBackupSongIds(backup, listOf(rescanned))

        val plan = ListenEventRestorePlanner.plan(
            events = listOf(event(songId = 1L, occurredAt = epochMs(2026, 6, 5), uri = "content://media/OLD/1")),
            resolveSong = { resolved[it.songId] },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
        )
        assertEquals(1, plan.restored)
        assertEquals(42L, plan.toInsert.single().songId)
    }

    // ── P2-B1 Phase 7: eventId dedup (only when non-null) ─────────────────────

    private fun eventWithId(songId: Long, occurredAt: Long, eventId: String?) =
        event(songId = songId, occurredAt = occurredAt).copy(eventId = eventId)

    @Test
    fun `phase7 - incoming non-null eventId already existing locally is skipped`() {
        val plan = ListenEventRestorePlanner.plan(
            events = listOf(eventWithId(1L, epochMs(2026, 6, 5), eventId = "evt-1")),
            resolveSong = { song(7L) },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
            existingEventIds = setOf("evt-1"),
        )
        assertEquals(0, plan.restored)
        assertEquals(1, plan.skippedDuplicate)
    }

    @Test
    fun `phase7 - duplicate non-null eventId within same batch skips the second`() {
        val plan = ListenEventRestorePlanner.plan(
            // Different occurredAt → distinct fingerprints; only eventId collides.
            events = listOf(
                eventWithId(1L, epochMs(2026, 6, 5), eventId = "evt-1"),
                eventWithId(1L, epochMs(2026, 6, 6), eventId = "evt-1"),
            ),
            resolveSong = { song(7L) },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
        )
        assertEquals(1, plan.restored)
        assertEquals(1, plan.skippedDuplicate)
    }

    @Test
    fun `phase7 - null eventId still uses fingerprint fallback`() {
        val nullEvent = eventWithId(1L, epochMs(2026, 6, 5), eventId = null)
        val fingerprint = "7|${nullEvent.occurredAt}|${nullEvent.eventType}|${nullEvent.listenedMs}"
        val plan = ListenEventRestorePlanner.plan(
            events = listOf(nullEvent),
            resolveSong = { song(7L) },
            existingFingerprints = setOf(fingerprint),
            nowMs = nowMs,
            zone = utc,
            // A populated eventId set must not affect a null-eventId event.
            existingEventIds = setOf("evt-1"),
        )
        assertEquals(0, plan.restored)
        assertEquals(1, plan.skippedDuplicate)
    }

    @Test
    fun `phase7 - new non-null eventId with no duplicate restores and preserves eventId`() {
        val plan = ListenEventRestorePlanner.plan(
            events = listOf(eventWithId(1L, epochMs(2026, 6, 5), eventId = "evt-new")),
            resolveSong = { song(7L) },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
            existingEventIds = setOf("evt-other"),
        )
        assertEquals(1, plan.restored)
        assertEquals("evt-new", plan.toInsert.single().eventId)
    }

    @Test
    fun `phase7 - restored legacy null-eventId event stays null`() {
        val plan = ListenEventRestorePlanner.plan(
            events = listOf(eventWithId(1L, epochMs(2026, 6, 5), eventId = null)),
            resolveSong = { song(7L) },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
        )
        assertEquals(1, plan.restored)
        assertNull(plan.toInsert.single().eventId)
    }

    private fun planInvalidEvent(event: BackupListenEvent): ListenEventRestorePlanner.Plan =
        ListenEventRestorePlanner.plan(
            events = listOf(event),
            resolveSong = { song(7L) },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
        )
}
