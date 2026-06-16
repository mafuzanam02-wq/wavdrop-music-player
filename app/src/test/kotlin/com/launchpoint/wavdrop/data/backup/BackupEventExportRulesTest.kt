package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class BackupEventExportRulesTest {

    @Test
    fun `native playback events are exported`() {
        assertTrue(BackupEventExportRules.shouldExport(TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK))
    }

    @Test
    fun `restored events are exported so history survives backup generations`() {
        assertTrue(BackupEventExportRules.shouldExport(TrackListenEventEntity.SOURCE_MANUAL_RESTORE))
    }

    @Test
    fun `desktop playback events are exported so desktop history survives android round trip`() {
        assertTrue(BackupEventExportRules.shouldExport(TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK))
    }

    @Test
    fun `imported synthetic history is never exported`() {
        assertFalse(BackupEventExportRules.shouldExport(TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT))
    }

    @Test
    fun `unknown sources are not exported`() {
        assertFalse(BackupEventExportRules.shouldExport("some_future_source"))
    }

    // ── Generation survival: backup → restore → backup → restore ─────────────

    private val utc: ZoneId = ZoneOffset.UTC
    private val nowMs = LocalDate.of(2026, 6, 11).atStartOfDay(utc).toInstant().toEpochMilli()
    private val playedAt = LocalDate.of(2026, 6, 5).atStartOfDay(utc).toInstant().toEpochMilli()

    private fun song(id: Long) = Song(
        id = id, title = "Title", artist = "Artist", album = "Album",
        albumId = 0L, duration = 180_000L, uri = "content://media/$id", dateAdded = 0L,
        trackNumber = 0, year = 0,
    )

    private fun backupEvent(songId: Long) = BackupListenEvent(
        songId = songId, contentUri = "content://media/OLD/$songId",
        title = "Title", artist = "Artist", album = "Album",
        eventType = TrackListenEventEntity.TYPE_PLAY, occurredAt = playedAt,
        listenedMs = 30_000L, durationMs = 180_000L,
        source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK,
    )

    /**
     * Reproduces the generational-loss scenario end to end at the pure-logic level:
     * device A backs up a playback event, device B restores it (stamped MANUAL_RESTORE),
     * device B backs up again, device C restores that second-generation backup.
     * The event must survive every hop.
     */
    @Test
    fun `listening event survives backup - restore - backup - restore`() {
        // ── Generation 1: device A exports a native playback event ───────────
        val gen1Events = listOf(backupEvent(songId = 1L))

        // ── Restore on device B: event lands with MANUAL_RESTORE source ──────
        val deviceBSong = song(10L)
        val restoreB = ListenEventRestorePlanner.plan(
            events = gen1Events,
            resolveSong = { deviceBSong },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
        )
        assertEquals(1, restoreB.restored)
        val storedOnB = restoreB.toInsert.single()
        assertEquals(TrackListenEventEntity.SOURCE_MANUAL_RESTORE, storedOnB.source)

        // ── Generation 2: device B backs up — the restored row MUST pass the
        //    export filter or all pre-restore history dies here. ──────────────
        assertTrue(
            "Second-generation backup dropped the restored event",
            BackupEventExportRules.shouldExport(storedOnB.source),
        )
        val gen2Events = listOf(
            BackupListenEvent(
                songId = storedOnB.songId, contentUri = deviceBSong.uri,
                title = deviceBSong.title, artist = deviceBSong.artist, album = deviceBSong.album,
                eventType = storedOnB.eventType, occurredAt = storedOnB.occurredAt,
                listenedMs = storedOnB.listenedMs, durationMs = storedOnB.durationMs,
                source = storedOnB.source,
            ),
        )

        // ── Restore on device C: event survives with its original timestamp ──
        val deviceCSong = song(99L)
        val restoreC = ListenEventRestorePlanner.plan(
            events = gen2Events,
            resolveSong = { deviceCSong },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
        )
        assertEquals(1, restoreC.restored)
        assertEquals(playedAt, restoreC.toInsert.single().occurredAt)
        assertEquals(99L, restoreC.toInsert.single().songId)
    }

    @Test
    fun `re-restoring the second-generation backup does not duplicate the event`() {
        val local = song(10L)
        val first = ListenEventRestorePlanner.plan(
            events = listOf(backupEvent(songId = 1L)),
            resolveSong = { local },
            existingFingerprints = emptySet(),
            nowMs = nowMs,
            zone = utc,
        )
        val existing = first.toInsert
            .map { "${it.songId}|${it.occurredAt}|${it.eventType}|${it.listenedMs}" }
            .toSet()

        val second = ListenEventRestorePlanner.plan(
            events = listOf(backupEvent(songId = 1L)),
            resolveSong = { local },
            existingFingerprints = existing,
            nowMs = nowMs,
            zone = utc,
        )
        assertEquals(0, second.restored)
        assertEquals(1, second.skippedDuplicate)
    }
}
