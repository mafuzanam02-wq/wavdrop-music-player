package com.launchpoint.wavdrop.data.local.dao

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.launchpoint.wavdrop.data.backup.BackupEventExportRules
import com.launchpoint.wavdrop.data.backup.BackupListenEvent
import com.launchpoint.wavdrop.data.backup.BackupSong
import com.launchpoint.wavdrop.data.backup.CanonicalEventStream
import com.launchpoint.wavdrop.data.backup.EventExportCursor
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupIntegrityV2
import com.launchpoint.wavdrop.data.backup.toBackupListenEvent
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkExportSnapshot
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkWriter
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Android-device counterpart of the host LegacyExportTieOrderRobolectricTest (WDBK-1 compatibility edge).
 *
 * SQL does not specify the order of rows that are equal on occurredAt, yet the legacy exporter's fingerprint depended on it
 * for rows that tie in WavdropBackupIntegrityV2.EVENT_ORDER but emit different canonical records (null eventId versus an
 * empty one). WDBK export deliberately reproduces the order observed in the host test environment (descending id). This
 * test runs the REAL TrackListenEventDao.getAllSnapshot() on the device's SQLite, without adding any id sort, records the
 * observed order, and checks that the production WDBK streamed path yields the same semantic fingerprint as the legacy path.
 * A failure here is a real compatibility finding, not a flaky test.
 */
@RunWith(AndroidJUnit4::class)
class LegacyExportTieOrderInstrumentedTest {

    private lateinit var db: WavdropDatabase
    private lateinit var dao: TrackListenEventDao

    @Before fun setUp() = openDb()

    private fun openDb() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.trackListenEventDao()
    }

    @After fun tearDown() = db.close()

    private val song = SongEntity(
        id = 1, title = "T", artist = "A", album = "B", albumId = 1, duration = 1000, uri = "content://s/1",
        dateAdded = 1, trackNumber = 1, year = 2000,
    )

    private fun tied(id: Long, eventId: String?) = TrackListenEventEntity(
        id = id, songId = 1, eventType = "PLAY", occurredAt = 5_000L, listenedMs = 100, durationMs = 200,
        source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK, eventId = eventId,
    )

    private fun map(e: TrackListenEventEntity) = e.toBackupListenEvent(song)

    private val songs = listOf(BackupSong(1L, "content://s/1", "T", "A", "B", 1L, 1000L, 1L, 1, 2000, null, null))

    private fun shell(events: List<BackupListenEvent>) = WavdropBackup(
        exportedAt = "", songs = songs, trackStats = emptyList(), importBaselines = emptyList(), listenEvents = events,
        backupId = "tie-order-test", sourceInstallationId = "tie-order-install", exportedAtMs = 1_700_000_000_000L,
    )

    /** The legacy computation: the production snapshot exactly as returned, fingerprinted by the production comparator. */
    private fun legacyFingerprint(): String = runBlocking {
        val events = dao.getAllSnapshot().filter { BackupEventExportRules.shouldExport(it.source) }.map(::map)
        WavdropBackupIntegrityV2.fingerprint(shell(events))
    }

    private val fetch: suspend (EventExportCursor, Long, Int) -> List<TrackListenEventEntity> = { c, upTo, limit ->
        dao.getExportPage(c.occurredAt, c.songId, c.eventType, c.listenedMs, c.durationMs, c.source, c.eventKey, c.id, upTo, limit)
    }

    private fun streamedFingerprint(pageSize: Int): String = runBlocking {
        val boundary = dao.getMaxId()
        val b = shell(emptyList())
        val snapshot = WdbkExportSnapshot(
            backupId = b.backupId!!, sourceInstallationId = b.sourceInstallationId!!, exportedAtMs = b.exportedAtMs!!,
            appVersionCode = null, appVersionName = null, songs = b.songs, trackStats = b.trackStats,
            importBaselines = b.importBaselines, lyricsOverrides = b.lyricsOverrides, preferences = null,
            playlists = b.playlists, desktopOverlayRawJson = null,
            openEvents = { CanonicalEventStream(boundary, pageSize, fetch, ::map) },
        )
        WdbkWriter(eventsPerChunk = 3).write(snapshot, ByteArrayOutputStream()).fingerprint
    }

    @Test fun legacySnapshotTieOrderAndStreamedFingerprintAgree() {
        val hashes = mutableListOf<String>()
        for ((nullId, emptyId) in listOf(1L to 2L, 2L to 1L)) { // both insertion arrangements
            tearDown(); openDb()
            runBlocking { dao.insertAll(listOf(tied(nullId, null), tied(emptyId, "")).sortedBy { it.id }) }
            val observed = runBlocking { dao.getAllSnapshot() }.map { it.id } // NOT re-sorted by id
            Log.i("WdbkTieOrder", "null id=$nullId empty id=$emptyId getAllSnapshot ids=$observed")
            assertEquals("observed equal-occurredAt order on this device", listOf(2L, 1L), observed)

            val legacy = legacyFingerprint()
            for (page in listOf(1, 2, 5)) assertEquals("page=$page null=$nullId empty=$emptyId", legacy, streamedFingerprint(page))
            hashes += legacy
        }
        assertNotEquals("the two arrangements must really produce different fingerprints", hashes[0], hashes[1])
    }

    @Test fun manyTiedRowsStreamTheSameFingerprintAsTheLegacyPath() {
        val rnd = java.util.Random(5)
        runBlocking {
            dao.insertAll(
                (1L..300L).map {
                    tied(it, when (rnd.nextInt(3)) { 0 -> null; 1 -> ""; else -> "e${rnd.nextInt(3)}" }).copy(listenedMs = rnd.nextInt(2).toLong())
                },
            )
        }
        val legacy = legacyFingerprint()
        for (page in listOf(1, 7, 64, 300)) assertEquals("page=$page", legacy, streamedFingerprint(page))
    }
}
