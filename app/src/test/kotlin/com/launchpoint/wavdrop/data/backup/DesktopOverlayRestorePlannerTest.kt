package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopOverlayRestorePlannerTest {

    @Test
    fun `matched desktop overlay stats use metadata matcher and max merge intent`() {
        val overlay = overlay(
            stats = listOf(
                stat(
                    playCount = 5,
                    skipCount = 2,
                    totalListeningTimeMs = 240_000L,
                    lastPlayedAt = 2_000L,
                    lastListenedAt = 3_000L,
                    favorite = true,
                ),
            ),
        )

        val plan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = listOf(song()),
            currentStats = mapOf(
                44L to TrackStatsEntity(
                    songId = 44L,
                    contentUri = "content://media/44",
                    playCount = 3,
                    skipCount = 1,
                    totalListeningTimeMs = 60_000L,
                    lastPlayedAt = 1_000L,
                    lastListenedAt = 2_000L,
                    isFavorite = false,
                ),
            ),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
        )

        val row = plan.matchedStats.single()
        assertEquals(44L, row.song.id)
        assertTrue(row.statsWillIncrease)
        assertTrue(row.favoriteWillApply)
        assertTrue(plan.hasWrites)
    }

    @Test
    fun `desktop play and zero-time skip events restore with desktop source`() {
        val overlay = overlay(
            events = listOf(
                event(eventId = "desktop-play-1", listenedMs = 30_000L, eventType = TrackListenEventEntity.TYPE_PLAY),
                event(eventId = "desktop-skip-1", listenedMs = 0L, eventType = TrackListenEventEntity.TYPE_SKIP),
            ),
        )

        val plan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = listOf(song()),
            currentStats = emptyMap(),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
        )

        assertEquals(2, plan.eventPlan.restored)
        assertEquals(0, plan.eventPlan.skippedTotal)
        assertEquals(
            listOf(TrackListenEventEntity.TYPE_PLAY, TrackListenEventEntity.TYPE_SKIP),
            plan.eventPlan.toInsert.map { it.eventType },
        )
        assertEquals(0L, plan.eventPlan.toInsert.last().listenedMs)
        assertTrue(plan.eventPlan.toInsert.all { it.source == TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK })
    }

    @Test
    fun `overlay event with desktopTrackId matching a stat uses matched stat song`() {
        val overlay = overlay(
            stats = listOf(stat(desktopTrackId = "desktop-track-1")),
            events = listOf(
                event(
                    eventId = "desktop-play-1",
                    desktopTrackId = "desktop-track-1",
                    title = "Different Event Title",
                    artist = "Different Artist",
                    album = "Different Album",
                ),
            ),
        )

        val plan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = listOf(song()),
            currentStats = emptyMap(),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
        )

        assertEquals(1, plan.eventPlan.restored)
        assertEquals(44L, plan.eventPlan.toInsert.single().songId)
    }

    @Test
    fun `overlay event with desktopTrackId absent from stats matches by event metadata`() {
        val overlay = overlay(
            stats = emptyList(),
            events = listOf(
                event(
                    eventId = "desktop-event-metadata",
                    desktopTrackId = "event-only-track",
                    title = "Event Only Song",
                    artist = "Event Artist",
                    album = "Event Album",
                    durationMs = 210_000L,
                ),
            ),
        )

        val plan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = listOf(
                song(
                    id = 77L,
                    title = "Event Only Song",
                    artist = "Event Artist",
                    album = "Event Album",
                    duration = 210_000L,
                ),
            ),
            currentStats = emptyMap(),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
        )

        assertEquals(1, plan.eventPlan.restored)
        assertEquals(77L, plan.eventPlan.toInsert.single().songId)
        assertEquals(0, plan.eventPlan.skippedUnmatched)
    }

    @Test
    fun `multiple events with same absent desktopTrackId reuse fallback metadata match`() {
        val overlay = overlay(
            stats = emptyList(),
            events = listOf(
                event(
                    eventId = "desktop-event-1",
                    desktopTrackId = "event-only-track",
                    title = "Event Only Song",
                    artist = "Event Artist",
                    album = "Event Album",
                    durationMs = 210_000L,
                    occurredAt = 10_000L,
                ),
                event(
                    eventId = "desktop-event-2",
                    desktopTrackId = "event-only-track",
                    title = "Ignored Different Title",
                    artist = "Ignored Different Artist",
                    album = "Ignored Different Album",
                    durationMs = 1L,
                    occurredAt = 20_000L,
                ),
            ),
        )

        val plan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = listOf(
                song(
                    id = 77L,
                    title = "Event Only Song",
                    artist = "Event Artist",
                    album = "Event Album",
                    duration = 210_000L,
                ),
            ),
            currentStats = emptyMap(),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
        )

        assertEquals(2, plan.eventPlan.restored)
        assertEquals(listOf(77L, 77L), plan.eventPlan.toInsert.map { it.songId })
    }

    @Test
    fun `desktop eventId dedup runs before fingerprint fallback`() {
        val duplicateById = event(eventId = "desktop-existing", occurredAt = 10_000L)
        val duplicateByFingerprint = event(eventId = null, occurredAt = 20_000L)
        val uniqueSameFingerprintWithEventId = event(eventId = "desktop-new", occurredAt = 20_000L)

        val plan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay(
                events = listOf(duplicateById, duplicateByFingerprint, uniqueSameFingerprintWithEventId),
            ),
            currentSongs = listOf(song()),
            currentStats = emptyMap(),
            existingEventFingerprints = setOf(DesktopOverlayRestorePlanner.fingerprint(44L, duplicateByFingerprint)),
            existingEventIds = setOf("desktop-existing"),
        )

        assertEquals(1, plan.eventPlan.restored)
        assertEquals(2, plan.eventPlan.skippedDuplicate)
        assertEquals("desktop-new", plan.eventPlan.toInsert.single().eventId)
    }

    @Test
    fun `ambiguous desktop overlay match is preserved but not applied`() {
        val overlay = overlay(
            stats = listOf(stat(favorite = true)),
            events = listOf(event(eventId = "desktop-ambiguous")),
        )

        val plan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = listOf(song(id = 44L, uri = "content://media/44"), song(id = 45L, uri = "content://media/45")),
            currentStats = emptyMap(),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
        )

        assertEquals(0, plan.matchedStats.size)
        assertEquals(1, plan.unresolvedStats.size)
        assertEquals(0, plan.eventPlan.restored)
        assertEquals(1, plan.eventPlan.skippedUnmatched)
        assertTrue(plan.hasPreservedOverlayRows)
    }

    @Test
    fun `ambiguous event-only fallback match remains unresolved`() {
        val overlay = overlay(
            stats = emptyList(),
            events = listOf(
                event(
                    eventId = "desktop-event-ambiguous",
                    desktopTrackId = "event-only-track",
                    title = "Shared Song",
                    artist = "Shared Artist",
                    album = "Shared Album",
                    durationMs = 180_000L,
                ),
            ),
        )

        val plan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = listOf(
                song(id = 44L, title = "Shared Song", artist = "Shared Artist", album = "Shared Album"),
                song(id = 45L, title = "Shared Song", artist = "Shared Artist", album = "Shared Album"),
            ),
            currentStats = emptyMap(),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
        )

        assertEquals(0, plan.eventPlan.restored)
        assertEquals(1, plan.eventPlan.skippedUnmatched)
        assertTrue(plan.hasPreservedOverlayRows)
    }

    @Test
    fun `unmatched desktop overlay stat — no local library — goes into unresolvedStats`() {
        val plan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay(stats = listOf(stat(favorite = true))),
            currentSongs = emptyList(),
            currentStats = emptyMap(),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
        )

        assertEquals(0, plan.matchedStats.size)
        assertEquals(1, plan.unresolvedStats.size)
        assertTrue("unresolvedStats must carry favorite flag", plan.unresolvedStats.single().favorite)
        assertTrue(plan.hasPreservedOverlayRows)
        assertFalse(plan.hasWrites)
    }

    @Test
    fun `overlay events with timestamps outside root event range are deduplicated correctly`() {
        // Root events occupy a completely different time window than overlay events.
        // The dedup sets must cover overlay timestamps even if the caller only loaded
        // fingerprints for the root-event window.
        val overlayOccurredAt = 1_800_000_000_000L  // far outside typical root window
        val overlayEvt = event(
            eventId = "desktop-far-future",
            occurredAt = overlayOccurredAt,
            listenedMs = 30_000L,
        )
        val overlay = overlay(events = listOf(overlayEvt))
        val songs = listOf(song())

        // First plan: no existing fingerprints → event should be inserted.
        val firstPlan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = songs,
            currentStats = emptyMap(),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
        )
        assertEquals(1, firstPlan.eventPlan.restored)

        // Second plan: simulate re-import — feed the inserted event's fingerprint/id back in.
        // This verifies that callers must supply dedup sets covering the overlay time range.
        val fingerprintsAfterApply = firstPlan.eventPlan.toInsert
            .mapTo(HashSet()) { "${it.songId}|${it.occurredAt}|${it.eventType}|${it.listenedMs}" }
        val eventIdsAfterApply = firstPlan.eventPlan.toInsert
            .mapNotNullTo(HashSet()) { it.eventId }

        val rePlan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = songs,
            currentStats = emptyMap(),
            existingEventFingerprints = fingerprintsAfterApply,
            existingEventIds = eventIdsAfterApply,
        )
        assertEquals("overlay event must not be re-inserted when fingerprint/id is present", 0, rePlan.eventPlan.restored)
        assertEquals(1, rePlan.eventPlan.skippedDuplicate)
    }

    @Test
    fun `re-import is idempotent when fingerprints from first plan are fed back (test-5)`() {
        val overlay = overlay(
            events = listOf(
                event(eventId = "desktop-play-1", listenedMs = 30_000L, eventType = TrackListenEventEntity.TYPE_PLAY),
                event(eventId = "desktop-skip-1", listenedMs = 0L, eventType = TrackListenEventEntity.TYPE_SKIP),
            ),
        )
        val songs = listOf(song())

        val firstPlan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = songs,
            currentStats = emptyMap(),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
        )
        assertEquals(2, firstPlan.eventPlan.restored)

        // Simulate what DB would look like after apply: build fingerprint + id sets from inserted rows.
        val fingerprintsAfterApply = firstPlan.eventPlan.toInsert
            .mapTo(HashSet()) { "${it.songId}|${it.occurredAt}|${it.eventType}|${it.listenedMs}" }
        val eventIdsAfterApply = firstPlan.eventPlan.toInsert
            .mapNotNullTo(HashSet()) { it.eventId }

        val rePlan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = songs,
            currentStats = emptyMap(),
            existingEventFingerprints = fingerprintsAfterApply,
            existingEventIds = eventIdsAfterApply,
        )
        assertEquals("re-import must not insert duplicates", 0, rePlan.eventPlan.restored)
        assertEquals(2, rePlan.eventPlan.skippedDuplicate)
        assertTrue("re-import toInsert must be empty", rePlan.eventPlan.toInsert.isEmpty())
    }

    @Test
    fun `overlay stat skipCount MAX merge applies when larger than local (test-6)`() {
        val overlay = overlay(stats = listOf(stat(skipCount = 100)))

        val plan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay,
            currentSongs = listOf(song()),
            currentStats = mapOf(
                44L to TrackStatsEntity(
                    songId = 44L,
                    contentUri = "content://media/44",
                    skipCount = 5,
                ),
            ),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
        )

        val row = plan.matchedStats.single()
        assertEquals(100, row.stats.skipCount)
        assertTrue("skipCount 100 > local 5 must flag statsWillIncrease", row.statsWillIncrease)
    }

    @Test
    fun `raw overlay rawJson is preserved in overlay object (test-7)`() {
        val rawJson = """{"schemaVersion":1,"customField":"keep-me"}"""
        val overlay = BackupDesktopOverlay(
            schemaVersion = 1,
            producerPlatform = "desktop",
            trackStats = listOf(stat()),
            listenEvents = emptyList(),
            rawJson = rawJson,
        )
        assertEquals(rawJson, overlay.rawJson)
    }

    @Test
    fun `invalid desktop event shapes are skipped`() {
        val plan = DesktopOverlayRestorePlanner.plan(
            overlay = overlay(
                events = listOf(
                    event(eventId = "bad-play", eventType = TrackListenEventEntity.TYPE_PLAY, listenedMs = 0L),
                    event(eventId = "bad-source", source = "other"),
                ),
            ),
            currentSongs = listOf(song()),
            currentStats = emptyMap(),
            existingEventFingerprints = emptySet(),
            existingEventIds = emptySet(),
        )

        assertEquals(0, plan.eventPlan.restored)
        assertEquals(2, plan.eventPlan.skippedInvalid)
    }

    private fun overlay(
        stats: List<BackupDesktopOverlayTrackStats> = listOf(stat()),
        events: List<BackupDesktopOverlayListenEvent> = emptyList(),
    ) = BackupDesktopOverlay(
        schemaVersion = 1,
        producerPlatform = "desktop",
        trackStats = stats,
        listenEvents = events,
        rawJson = "{}",
    )

    private fun stat(
        desktopTrackId: String = "desktop-track-1",
        title: String = "Ghost Song",
        artist: String = "Doors",
        album: String = "Other Voices",
        durationMs: Long = 180_000L,
        playCount: Int = 0,
        skipCount: Int = 0,
        totalListeningTimeMs: Long = 0L,
        lastPlayedAt: Long = 0L,
        lastListenedAt: Long = 0L,
        favorite: Boolean = false,
    ) = BackupDesktopOverlayTrackStats(
        desktopTrackId = desktopTrackId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        playCount = playCount,
        skipCount = skipCount,
        totalListeningTimeMs = totalListeningTimeMs,
        lastPlayedAt = lastPlayedAt,
        lastListenedAt = lastListenedAt,
        favorite = favorite,
    )

    private fun event(
        eventId: String? = "desktop-event-1",
        desktopTrackId: String? = "desktop-track-1",
        title: String = "Ghost Song",
        artist: String = "Doors",
        album: String = "Other Voices",
        durationMs: Long = 180_000L,
        occurredAt: Long = 1_782_230_500_000L,
        listenedMs: Long = 30_000L,
        eventType: String = TrackListenEventEntity.TYPE_PLAY,
        source: String = TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK,
    ) = BackupDesktopOverlayListenEvent(
        eventId = eventId,
        desktopTrackId = desktopTrackId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        occurredAt = occurredAt,
        listenedMs = listenedMs,
        eventType = eventType,
        source = source,
    )

    private fun song(
        id: Long = 44L,
        uri: String = "content://media/44",
        title: String = "Ghost Song",
        artist: String = "Doors",
        album: String = "Other Voices",
        duration: Long = 180_000L,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = 1L,
        duration = duration,
        uri = uri,
        dateAdded = 1_000L,
        trackNumber = 1,
        year = 1971,
    )
}
