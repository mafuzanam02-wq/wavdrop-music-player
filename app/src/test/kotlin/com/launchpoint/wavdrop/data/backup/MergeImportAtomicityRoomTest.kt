package com.launchpoint.wavdrop.data.backup

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupEvent
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupPlaylistSong
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupSong
import com.launchpoint.wavdrop.data.backup.RecoveryTestFixtures.backupStats
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.LyricsOverrideDao
import com.launchpoint.wavdrop.data.local.dao.PendingBackupExtensionDao
import com.launchpoint.wavdrop.data.local.dao.PendingTrackDao
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.TrackListenEventDao
import com.launchpoint.wavdrop.data.local.dao.TrackStatsDao
import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.LyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.PendingBackupExtensionEntity
import com.launchpoint.wavdrop.data.local.entity.PendingImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.PendingListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.PendingLyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.PendingPlaylistEntryEntity
import com.launchpoint.wavdrop.data.local.entity.PendingTrackEntity
import com.launchpoint.wavdrop.data.local.entity.PendingTrackStatsEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P5-AUDIT-09. Deterministic failure injection against the REAL Merge repositories and a real in-memory Room database.
 *
 * Seams (no production hooks): the existing `onStage` callback, and test-only delegating DAO wrappers that perform the real write
 * and then throw. Every wrapper records the write it forwarded in [trace], so each scenario proves which writes had ALREADY happened
 * before the injected failure; rollback is then judged against a row-level dump of every table: each row serialized as column=value text, ordered by rowid, plus the `sqlite_sequence` counters. That is evidence of identical logical row contents and ordering; it is NOT a comparison of the literal SQLite database file bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MergeImportAtomicityRoomTest {

    private class Boom(message: String) : RuntimeException(message)

    private lateinit var db: WavdropDatabase
    private val trace = mutableListOf<String>()
    private var failAfter: String? = null

    private var onWrite: ((String) -> Unit)? = null

    private fun hit(write: String) {
        trace += write
        onWrite?.invoke(write)
        if (write == failAfter) throw Boom("injected after $write")
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build()
        seedLocalState()
    }

    @After fun tearDown() = db.close()

    // ── Wrappers: real write first, then record, then (maybe) throw ──────────

    private fun stats(): TrackStatsDao = object : TrackStatsDao by db.trackStatsDao() {
        override suspend fun insertIfAbsent(entity: TrackStatsEntity) { db.trackStatsDao().insertIfAbsent(entity); hit("stats.insertIfAbsent") }
        override suspend fun mergeMaxStats(
            songId: Long, importedPlayCount: Int, importedSkipCount: Int, importedListeningTimeMs: Long,
            importedLastPlayedAt: Long, importedLastListenedAt: Long,
        ) {
            db.trackStatsDao().mergeMaxStats(songId, importedPlayCount, importedSkipCount, importedListeningTimeMs, importedLastPlayedAt, importedLastListenedAt)
            hit("stats.mergeMaxStats")
        }
        override suspend fun setFavorite(songId: Long, isFavorite: Boolean) { db.trackStatsDao().setFavorite(songId, isFavorite); hit("stats.setFavorite") }
    }

    private fun lyrics(): LyricsOverrideDao = object : LyricsOverrideDao by db.lyricsOverrideDao() {
        override suspend fun upsert(entity: LyricsOverrideEntity) { db.lyricsOverrideDao().upsert(entity); hit("lyrics.upsert") }
    }

    private fun playlists(): PlaylistDao = object : PlaylistDao by db.playlistDao() {
        override suspend fun insertPlaylist(entity: PlaylistEntity): Long = db.playlistDao().insertPlaylist(entity).also { hit("playlists.insertPlaylist") }
        override suspend fun insertSongs(entities: List<PlaylistSongEntity>) { db.playlistDao().insertSongs(entities); hit("playlists.insertSongs") }
        override suspend fun insertSong(entity: PlaylistSongEntity) { db.playlistDao().insertSong(entity); hit("playlists.insertSong") }
        override suspend fun touchPlaylist(playlistId: Long, updatedAt: Long) { db.playlistDao().touchPlaylist(playlistId, updatedAt); hit("playlists.touch") }
    }

    private fun events(): TrackListenEventDao = object : TrackListenEventDao by db.trackListenEventDao() {
        override suspend fun insertAll(events: List<TrackListenEventEntity>) { db.trackListenEventDao().insertAll(events)
            // Distinguish the two production call sites: the Android root events carry no Desktop source; the Desktop overlay events do.
            hit(if (events.any { it.source == TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK }) "events.insertAll.overlay" else "events.insertAll.root")
        }
        override suspend fun insert(event: TrackListenEventEntity) { db.trackListenEventDao().insert(event); hit("events.insert") }
    }

    private fun baselines(): ImportBaselineDao = object : ImportBaselineDao by db.importBaselineDao() {
        override suspend fun upsertBaseline(entity: ImportBaselineEntity) { db.importBaselineDao().upsertBaseline(entity); hit("baselines.upsert") }
    }

    private fun extension(): PendingBackupExtensionDao = object : PendingBackupExtensionDao by db.pendingBackupExtensionDao() {
        override suspend fun upsert(entity: PendingBackupExtensionEntity) { db.pendingBackupExtensionDao().upsert(entity); hit("overlay.upsert") }
    }

    private fun pending(): PendingTrackDao = object : PendingTrackDao by db.pendingTrackDao() {
        override suspend fun insertTrack(entity: PendingTrackEntity): Long = db.pendingTrackDao().insertTrack(entity).also { hit("pending.insertTrack") }
        override suspend fun insertStats(entity: PendingTrackStatsEntity) { db.pendingTrackDao().insertStats(entity); hit("pending.insertStats") }
        override suspend fun insertEvent(entity: PendingListenEventEntity) { db.pendingTrackDao().insertEvent(entity); hit("pending.insertEvent") }
        // P5-STATS-18C: quarantined events are now written through PendingEventWriter, which uses insertEventOrIgnore.
        override suspend fun insertEventOrIgnore(entity: PendingListenEventEntity): Long =
            db.pendingTrackDao().insertEventOrIgnore(entity).also { hit("pending.insertEvent") }
        override suspend fun insertLyrics(entity: PendingLyricsOverrideEntity) { db.pendingTrackDao().insertLyrics(entity); hit("pending.insertLyrics") }
        override suspend fun insertBaseline(entity: PendingImportBaselineEntity) { db.pendingTrackDao().insertBaseline(entity); hit("pending.insertBaseline") }
        override suspend fun insertPlaylistEntry(entity: PendingPlaylistEntryEntity) { db.pendingTrackDao().insertPlaylistEntry(entity); hit("pending.insertPlaylistEntry") }
    }

    /** The real production repository, over wrapped DAOs. */
    private fun merge() = WavdropBackupImportRepository(
        db, db.songDao(), stats(), lyrics(), baselines(), playlists(), events(), pending(), extension(),
    )

    private fun desktop() = DesktopWavdropBackupImportRepository(db, db.songDao(), stats(), playlists(), events())

    // ── Row-level dump of every table (serialized values in rowid order + sqlite_sequence; not file bytes) ────────────────────────────────────

    private fun dump(): Map<String, List<String>> {
        val tables = mutableListOf<String>()
        db.query(SimpleSQLiteQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name")).use {
            while (it.moveToNext()) tables += it.getString(0)
        }
        val out = linkedMapOf<String, List<String>>()
        for (t in tables) {
            val rows = mutableListOf<String>()
            db.query(SimpleSQLiteQuery("SELECT * FROM `$t` ORDER BY rowid")).use { c ->
                while (c.moveToNext()) rows += (0 until c.columnCount).joinToString("|") { i -> "${c.getColumnName(i)}=${if (c.isNull(i)) "NULL" else c.getString(i)}" }
            }
            out[t] = rows
        }
        db.query(SimpleSQLiteQuery("SELECT name, seq FROM sqlite_sequence ORDER BY name")).use { c ->
            val rows = mutableListOf<String>()
            while (c.moveToNext()) rows += "${c.getString(0)}=${c.getString(1)}"
            out["sqlite_sequence"] = rows
        }
        return out
    }

    private fun changedTables(before: Map<String, List<String>>, after: Map<String, List<String>>) =
        before.keys.filter { before[it] != after[it] }.toSet()

    private fun assertUnchanged(before: Map<String, List<String>>) {
        val after = dump()
        for ((table, rows) in before) assertEquals("table $table: serialized rows (rowid order) must equal the pre-import dump after rollback", rows, after[table])
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private fun songEntity(id: Long) = backupSong(id).let {
        SongEntity(id, it.title, it.artist, it.album, it.albumId, it.duration, it.uri, it.dateAdded, it.trackNumber, it.year, it.folderPath, it.folderName)
    }

    private fun localEvent(id: String?, songId: Long, at: Long, source: String = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK) =
        TrackListenEventEntity(
            songId = songId, eventType = TrackListenEventEntity.TYPE_PLAY, occurredAt = at, listenedMs = 60_000L,
            durationMs = 180_000L, source = source, eventId = id,
        )

    private fun seedLocalState() = runBlocking {
        db.songDao().upsertAll((1L..4L).map(::songEntity))
        db.trackStatsDao().insertAllForRecovery(
            listOf(
                TrackStatsEntity(1, "content://media/1", playCount = 100, skipCount = 20, lastPlayedAt = RecoveryTestFixtures.NOW, lastListenedAt = RecoveryTestFixtures.NOW, totalListeningTimeMs = 9_000_000L, isFavorite = true),
                TrackStatsEntity(2, "content://media/2", playCount = 5, isFavorite = true),
                TrackStatsEntity(3, "content://media/3", playCount = 2, isFavorite = false),
            ),
        )
        db.trackListenEventDao().insertAll(
            listOf(
                localEvent("A", 1, 1_000L), localEvent("B", 1, 2_000L), localEvent("C", 1, 3_000L),
                localEvent(null, 1, 4_000L, source = TrackListenEventEntity.SOURCE_BLACKPLAYER_IMPORT),
            ),
        )
        val roadtrip = db.playlistDao().insertPlaylist(PlaylistEntity(name = "Roadtrip", createdAt = 1, updatedAt = 1))
        val gym = db.playlistDao().insertPlaylist(PlaylistEntity(name = "Gym", createdAt = 2, updatedAt = 2))
        db.playlistDao().insertSongs(listOf(PlaylistSongEntity(roadtrip, 2, 0), PlaylistSongEntity(roadtrip, 1, 1), PlaylistSongEntity(gym, 3, 0)))
        db.lyricsOverrideDao().upsert(LyricsOverrideEntity(2, "content://media/2", "local lyrics", 5_000L))
        db.importBaselineDao().upsertBaseline(ImportBaselineEntity(3, "blackplayer", "k", 4, 1, 9L))
        db.pendingTrackDao().insertTrack(
            PendingTrackEntity(originKey = "other|7", backupFingerprint = "other", backupSongId = "7", title = "Old", artist = "A", album = "B",
                duration = 1L, trackNumber = 1, year = 2000, sourceUri = null, sourceFolderPath = null, restoredAt = 1L),
        )
        db.pendingBackupExtensionDao().upsert(PendingBackupExtensionEntity(DESKTOP_OVERLAY_ROOT, "{\"old\":true}", 1L))
    }

    /** Mergeable data in EVERY category, including an Android-matched song, an unmatched song (quarantine) and a Desktop overlay. */
    private fun backup(withOverlay: Boolean = true) = RecoveryTestFixtures.v2Backup(
        songs = listOf(backupSong(1), backupSong(2), backupSong(3), backupSong(4), backupSong(9, "Gone Song")),
        stats = listOf(
            backupStats(1, 150, 5, false),   // plays raised, skips retained (local 20), local favorite retained
            backupStats(2, 1, 0, false),     // lower than local, favorite retained
            backupStats(3, 8, 0, true),      // raised + favorite set
            backupStats(4, 3, 0, true),      // no local row yet
            backupStats(9, 7, 0, true),      // unmatched -> quarantine
        ),
        events = listOf(
            backupEvent(1, "A", 1_000L), backupEvent(1, "X1", 7_000L), backupEvent(3, "X2", 8_000L), backupEvent(9, "G1", 6_000L, "Gone Song"),
        ),
        playlists = listOf(
            BackupPlaylist(11, "Roadtrip", 10L, 20L, listOf(backupPlaylistSong(2, 0), backupPlaylistSong(1, 1), backupPlaylistSong(3, 2), backupPlaylistSong(4, 3))),
            BackupPlaylist(12, "Sleep", 30L, 40L, listOf(backupPlaylistSong(1, 0), backupPlaylistSong(9, 1).copy(title = "Gone Song"))),
        ),
        lyrics = listOf(
            BackupLyricsOverride(1, "content://media/1", "backup lyrics 1", 7_000L),   // restored (no local)
            BackupLyricsOverride(2, "content://media/2", "OLDER backup lyrics", 1_000L), // older than local 5_000: must NOT overwrite
            BackupLyricsOverride(9, "content://media/9", "ghost lyrics", 7_000L),      // quarantine
        ),
        baselines = listOf(
            BackupImportBaseline(1, "blackplayer", "kk", 3, 1, 8L),
            BackupImportBaseline(9, "blackplayer", "gone", 2, 1, 8L),
        ),
        overlayRawJson = if (withOverlay) "{\"schemaVersion\":1}" else null,
    ).let { b ->
        if (!withOverlay) b else b.copy(
            desktopOverlay = b.desktopOverlay!!.copy(
                trackStats = listOf(
                    BackupDesktopOverlayTrackStats("dt-2", "Song 2", "Artist", "Album", 180_002L, 50, 0, 500_000L, RecoveryTestFixtures.NOW - 5_000L, RecoveryTestFixtures.NOW - 4_000L, true),
                ),
                listenEvents = listOf(
                    BackupDesktopOverlayListenEvent("D1", "dt-2", "Song 2", "Artist", "Album", 180_002L, 9_000L, 30_000L, TrackListenEventEntity.TYPE_PLAY, TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK),
                ),
            ),
        )
    }

    private val tablesMerge = setOf(
        "track_stats", "lyrics_overrides", "playlists", "playlist_songs", "track_listen_events", "import_baselines",
        "pending_backup_extensions", "pending_tracks", "pending_track_stats", "pending_listen_events", "pending_lyrics_overrides",
        "pending_import_baselines", "pending_playlist_entries",
    )

    /** Runs the import with one injected failure; asserts it reaches the caller, nothing persisted, and then proves a clean retry works. */
    private fun injectAndVerify(
        label: String,
        expectedWritesBeforeFailure: List<String>? = null,
        stage: String? = null,
        after: String? = null,
        prefixOfTrace: Boolean = true,
    ): List<String> = runBlocking {
        val before = dump()
        trace.clear()
        failAfter = after
        val thrown = runCatching {
            merge().applyImport(backup()) { s -> if (s == stage) throw Boom("injected at stage '$s'") }
        }.exceptionOrNull()
        failAfter = null
        assertTrue("$label: the injected exception must reach the caller, got $thrown", thrown is Boom)
        val writes = trace.toList()
        if (expectedWritesBeforeFailure != null) {
            if (prefixOfTrace) assertEquals("$label: writes that had happened before the failure", expectedWritesBeforeFailure, writes.distinct())
        }
        assertUnchanged(before)

        // retry with no injection must succeed and change every Merge-written category
        trace.clear()
        val result = merge().applyImport(backup())
        assertFalse("$label: retry must not be a no-op", result.isNoOp)
        assertTrue("$label: retry must change the merge tables", changedTables(before, dump()).containsAll(tablesMerge))
        writes
    }

    // ── Sanity: the fixture really exercises every category ──────────────────

    @Test fun `fixture - a clean merge writes every Merge-written table`() = runBlocking {
        val before = dump()
        val r = merge().applyImport(backup())
        assertFalse(r.isNoOp)
        val changed = changedTables(before, dump())
        assertTrue("changed tables were $changed", changed.containsAll(tablesMerge))
        assertTrue("trace shows quarantine writes after the last stage callback", trace.contains("pending.insertTrack"))
        assertEquals("songs table is never written by Merge", before["songs"], dump()["songs"])
        assertEquals("playlists the backup does not mention stay", before["track_identities"], dump()["track_identities"])
    }

    // ── A–E: stage-callback injection (callbacks fire BEFORE their stage's writes) ──

    @Test fun `A failure at the lyrics stage rolls back statistics and favorites`() {
        val w = injectAndVerify("A", stage = "Restoring lyrics…")
        assertTrue("stats were really written first", "stats.mergeMaxStats" in w && "stats.setFavorite" in w)
        assertFalse("no lyrics write had started", "lyrics.upsert" in w)
    }

    @Test fun `B failure at the playlist stage rolls back statistics, favorites and lyrics`() {
        val w = injectAndVerify("B", stage = "Restoring playlists…")
        assertTrue("stats.mergeMaxStats" in w && "lyrics.upsert" in w)
        assertFalse("no playlist write had started", w.any { it.startsWith("playlists.") })
    }

    @Test fun `C failure at the listening-history stage rolls back new playlists, entries and touch updates`() {
        val w = injectAndVerify("C", stage = "Restoring listening history…")
        assertTrue("playlists.insertPlaylist" in w && "playlists.insertSongs" in w && "playlists.touch" in w)
        assertFalse("events not written yet", w.any { it.startsWith("events.insertAll") })
    }

    @Test fun `C2 failure immediately after the playlist rows are inserted still rolls everything back`() {
        val w = injectAndVerify("C2", after = "playlists.insertSongs")
        assertEquals("failed at the FIRST playlist entry insert, after stats+lyrics+playlist creation", "playlists.insertSongs", w.last())
    }

    @Test fun `D failure after the listening events were inserted rolls back events, ids and statistics`() {
        val w = injectAndVerify("D", after = "events.insertAll.root")
        assertEquals("events.insertAll.root", w.last())
        assertTrue("stats, lyrics and playlists were already written", "stats.mergeMaxStats" in w && "lyrics.upsert" in w && "playlists.insertSongs" in w)
    }

    @Test fun `D2 failure at the Desktop activity stage happens after root events were inserted`() {
        val w = injectAndVerify("D2", stage = "Restoring Desktop activity…")
        assertTrue("events.insertAll.root" in w)
        assertFalse("overlay not touched yet", "overlay.upsert" in w)
    }

    @Test fun `D3 failure after the Desktop overlay payload and stats were written rolls back`() {
        val w = injectAndVerify("D3", stage = "Restoring import baselines…")
        assertTrue("overlay.upsert" in w)
    }

    @Test fun `D4 failure after the Desktop overlay events were inserted rolls back`() {
        val w = injectAndVerify("D4", after = "events.insertAll.overlay")
        // the injected failure is the overlay event write itself: it was forwarded to the real DAO first, then the exception was thrown
        assertEquals("events.insertAll.overlay", w.last())
        assertEquals("the overlay events were inserted exactly once", 1, w.count { it == "events.insertAll.overlay" })
        // earlier Merge writes had already happened, in transaction order
        fun at(write: String) = w.indexOf(write).also { assertTrue("$write must have happened before the failure; trace=$w", it >= 0) }
        val stats = at("stats.mergeMaxStats"); val lyrics = at("lyrics.upsert"); val playlist = at("playlists.insertSongs")
        val root = at("events.insertAll.root"); val overlayPayload = at("overlay.upsert"); val overlayEvents = at("events.insertAll.overlay")
        assertTrue("order: stats < lyrics < playlists < root events < overlay payload < overlay events",
            stats < lyrics && lyrics < playlist && playlist < root && root < overlayPayload && overlayPayload < overlayEvents)
        assertFalse("the failure came before the baselines stage", "baselines.upsert" in w)
        assertFalse("and before quarantine", w.any { it.startsWith("pending.") })
        // (the rollback of every table and the clean retry are asserted by injectAndVerify); the retry also restores the overlay event
        val ids = runBlocking { db.trackListenEventDao().getAllSnapshot().mapNotNull { it.eventId } }
        assertTrue("clean retry committed the Desktop overlay event D1", "D1" in ids)
    }

    @Test fun `E1 failure after the import baseline was written rolls back`() {
        val w = injectAndVerify("E1", after = "baselines.upsert")
        assertEquals("baselines.upsert", w.last())
        assertFalse("quarantine not started", w.any { it.startsWith("pending.") })
    }

    @Test fun `E2 failure at the Finishing stage rolls back baselines too but quarantine has not begun`() {
        val w = injectAndVerify("E2", stage = "Finishing import…")
        assertTrue("baselines.upsert" in w)
        assertFalse("the last stage callback fires BEFORE any quarantine write", w.any { it.startsWith("pending.") })
    }

    @Test fun `E3 failure after the first quarantine track row rolls back`() {
        val w = injectAndVerify("E3", after = "pending.insertTrack")
        assertEquals("pending.insertTrack", w.last())
    }

    @Test fun `E4 failure after quarantined stats rolls back`() {
        val w = injectAndVerify("E4", after = "pending.insertStats")
        assertTrue("pending.insertTrack" in w && w.last() == "pending.insertStats")
    }

    @Test fun `E5 failure after a quarantined event rolls back`() {
        val w = injectAndVerify("E5", after = "pending.insertEvent")
        assertTrue("pending.insertStats" in w && w.last() == "pending.insertEvent")
    }

    @Test fun `E6 failure after quarantined lyrics rolls back`() {
        val w = injectAndVerify("E6", after = "pending.insertLyrics")
        assertTrue("pending.insertEvent" in w && w.last() == "pending.insertLyrics")
    }

    @Test fun `E7 failure after a quarantined baseline rolls back`() {
        val w = injectAndVerify("E7", after = "pending.insertBaseline")
        assertEquals("pending.insertBaseline", w.last())
    }

    @Test fun `E8 failure after a quarantined playlist entry - the final write - rolls back`() {
        val w = injectAndVerify("E8", after = "pending.insertPlaylistEntry")
        assertEquals("pending.insertPlaylistEntry", w.last())
    }

    @Test fun `failure at the first stage before any write is a clean no-change`() {
        val w = injectAndVerify("S0", stage = "Restoring song matches…")
        assertTrue("no writes at all", w.isEmpty())
    }

    // ── F: cancellation ───────────────────────────────────────────────────────

    /**
     * OBSERVED production behavior, recorded as a CHARACTERIZATION ONLY and as an UNRESOLVED lifecycle issue (not an approved product
     * contract, not a fix): cancelling the caller's job from inside the Room transaction does NOT
     * interrupt it. Room's in-transaction DAO calls are not cancellation checkpoints, so the body runs to the end and commits; the
     * caller then receives the CancellationException and no result. The observed state is all-or-nothing (here: all), never partial; whether that is the desired lifecycle is undecided.
     */
    @Test fun `F1 cancelling the caller mid-transaction yields no result and a complete, never partial, import`() = runBlocking {
        val before = dump()
        val lock = RestoreOperationLock()
        trace.clear()
        var result: WavdropBackupImportApplyResult? = null
        var cancelled = false
        val job = launch(start = CoroutineStart.LAZY) {
            try {
                result = lock.tryRun { merge().applyImport(backup()) { s -> if (s == "Restoring playlists…") cancelHolder.cancel() } }
            } catch (e: CancellationException) {
                cancelled = true
                throw e // never swallowed
            }
        }
        cancelHolder = job
        job.start(); job.join()
        assertTrue("the import coroutine was cancelled and the CancellationException reached the caller", job.isCancelled && cancelled)
        assertNull("no success result is produced for a cancelled import", result)

        val afterCancel = dump()
        assertTrue("all-or-nothing: every Merge-written category is present, none is half-applied", changedTables(before, afterCancel).containsAll(tablesMerge))
        assertTrue("including the very last quarantine write", "pending.insertPlaylistEntry" in trace)

        // the lock was released and the identical import is now a complete no-op (nothing was left unfinished)
        val next = lock.tryRun { merge().applyImport(backup()) }
        assertNotNull("restore ownership must be free after cancellation", next)
        assertTrue("a retry finds nothing left to merge", next!!.isNoOp)
        assertEquals(afterCancel, dump())
    }

    @Test fun `F2 a CancellationException thrown INSIDE the transaction rolls everything back`() = runBlocking {
        val before = dump()
        val lock = RestoreOperationLock()
        trace.clear()
        onWrite = { w -> if (w == "playlists.insertSongs") throw CancellationException("injected cancellation at a cooperative checkpoint") }
        var result: WavdropBackupImportApplyResult? = null
        val thrown = runCatching { result = lock.tryRun { merge().applyImport(backup()) } }.exceptionOrNull()
        onWrite = null
        assertTrue("CancellationException must propagate, not be swallowed: $thrown", thrown is CancellationException)
        assertNull(result)
        assertTrue("stats and lyrics had been written first", "stats.mergeMaxStats" in trace && "lyrics.upsert" in trace)
        assertUnchanged(before)
        val next = lock.tryRun { merge().applyImport(backup()) }
        assertNotNull("lock released", next)
        assertFalse(next!!.isNoOp)
    }

    private lateinit var cancelHolder: kotlinx.coroutines.Job

    // ── G: retry and duplicate import ────────────────────────────────────────

    @Test fun `G after a failed import the retry succeeds and a second identical import changes nothing`() = runBlocking {
        val before = dump()
        runCatching { merge().applyImport(backup()) { if (it == "Restoring playlists…") throw Boom("x") } }
        assertUnchanged(before)

        val first = merge().applyImport(backup())
        assertFalse(first.isNoOp)
        val afterFirst = dump()

        val second = merge().applyImport(backup())
        assertTrue("an identical second import is a no-op (existing dedup/idempotence policy)", second.isNoOp)
        assertEquals(afterFirst, dump())
        val ids = db.trackListenEventDao().getAllSnapshot().mapNotNull { it.eventId }
        assertEquals("no event id is duplicated", ids.toSet().size, ids.size)
    }

    // ── H: genuinely non-mergeable backup ────────────────────────────────────

    @Test fun `H a backup with nothing new to merge reports no changes and writes nothing`() = runBlocking {
        val before = dump()
        trace.clear()
        val nothing = RecoveryTestFixtures.v2Backup(
            songs = listOf(backupSong(1), backupSong(2)),
            stats = listOf(backupStats(1, 10, 1, false, 0L, 0L, 0L), backupStats(2, 1, 0, false, 0L, 0L, 0L)), // all lower than or equal to local
            events = listOf(backupEvent(1, "A", 1_000L)),                                // duplicate id
            playlists = listOf(BackupPlaylist(11, "Roadtrip", 10L, 20L, listOf(backupPlaylistSong(2, 0), backupPlaylistSong(1, 1)))),
            lyrics = listOf(BackupLyricsOverride(2, "content://media/2", "older", 1_000L)),
            baselines = emptyList(), overlayRawJson = null,
        )
        val r = merge().applyImport(nothing) { error("no stage may be reported for a no-op") }
        assertTrue(r.isNoOp)
        assertTrue("no write method was reached", trace.isEmpty())
        assertUnchanged(before)
    }

    // ── I: existing data protection on a SUCCESSFUL merge ─────────────────────

    @Test fun `I a successful merge never lowers or clears existing local data`() = runBlocking {
        merge().applyImport(backup())
        val s1 = db.trackStatsDao().getStatsBySongId(1)!!
        assertEquals("higher backup count raises", 150, s1.playCount)
        assertEquals("higher local skips retained", 20, s1.skipCount)
        assertTrue("local favorite is never cleared by a backup that says false", s1.isFavorite)
        assertEquals("MAX of local 9,000,000 and backup 15,000,000", 15_000_000L, s1.totalListeningTimeMs)
        val s2 = db.trackStatsDao().getStatsBySongId(2)!!
        assertTrue("favorite kept", s2.isFavorite)
        assertEquals("overlay raised plays but never lowered", 50, s2.playCount)
        assertEquals("newer local lyrics survive an older backup", "local lyrics", db.lyricsOverrideDao().getAllSnapshot().first { it.songId == 2L }.lyrics)
        assertEquals("backup lyrics fill a gap", "backup lyrics 1", db.lyricsOverrideDao().getAllSnapshot().first { it.songId == 1L }.lyrics)
        val road = db.playlistDao().findByName("Roadtrip")!!
        assertEquals("existing entries keep their order and new ones append", listOf(2L, 1L, 3L, 4L),
            db.playlistDao().getSongsForPlaylistSnapshot(road.playlistId).sortedBy { it.position }.map { it.songId })
        assertEquals("local-only playlist untouched", listOf(3L), db.playlistDao().getSongsForPlaylistSnapshot(db.playlistDao().findByName("Gym")!!.playlistId).map { it.songId })
        assertEquals(setOf("A", "B", "C", "X1", "X2", "D1"), db.trackListenEventDao().getAllSnapshot().mapNotNull { it.eventId }.toSet())
        assertTrue("earlier quarantine row kept", "other|7" in db.pendingTrackDao().getAllOriginKeys())
        assertEquals("existing baseline untouched", "k", db.importBaselineDao().getAllImportBaselinesSnapshot().first { it.songId == 3L }.sourceKey)
    }

    // ── J: Desktop import ─────────────────────────────────────────────────────

    private fun desktopBackup() = DesktopWavdropBackup(
        schemaVersion = 1, exportedAt = "2026-06-01T10:00:00Z", appName = DesktopWavdropBackupParser.APP_NAME,
        sourcePlatform = "desktop", folderPath = null,
        songs = listOf(
            DesktopBackupSong("d1", "Song 3", "Artist", "Album", 77, 700_000L, RecoveryTestFixtures.NOW, true),
            DesktopBackupSong("d2", "Song 4", "Artist", "Album", 12, 100_000L, RecoveryTestFixtures.NOW, true),
        ),
        playlists = listOf(DesktopBackupPlaylist("p1", "Desktop Mix", listOf("d1", "d2"))),
        listenEvents = listOf(
            DesktopBackupListenEvent("d1", "Song 3", "Artist", "Album", 180_003L, 50_000L, 30_000L, TrackListenEventEntity.TYPE_PLAY, TrackListenEventEntity.SOURCE_DESKTOP_PLAYBACK),
        ),
    )

    @Test fun `J desktop import writes stats, a playlist and events when it succeeds`() = runBlocking {
        val before = dump()
        val r = desktop().applyImport(desktopBackup())
        assertTrue("matched=${r.matchedTracks}", r.matchedTracks > 0)
        val changed = changedTables(before, dump())
        assertTrue("changed=$changed", changed.containsAll(setOf("track_stats", "playlists", "playlist_songs", "track_listen_events")))
    }

    private fun desktopFailsAfter(write: String) = runBlocking {
        val before = dump()
        trace.clear(); failAfter = write
        val thrown = runCatching { desktop().applyImport(desktopBackup()) }.exceptionOrNull()
        failAfter = null
        assertTrue("desktop $write: exception must reach the caller, got $thrown", thrown is Boom)
        assertEquals(write, trace.last())
        assertUnchanged(before)
        trace.clear()
        assertTrue("retry after $write failure succeeds", desktop().applyImport(desktopBackup()).matchedTracks > 0)
        trace
    }

    @Test fun `J1 desktop failure after statistics rolls back`() { desktopFailsAfter("stats.setFavorite") }
    @Test fun `J2 desktop failure after the new playlist row rolls back`() { desktopFailsAfter("playlists.insertPlaylist") }
    @Test fun `J3 desktop failure after the playlist entries rolls back`() { desktopFailsAfter("playlists.insertSong") }
    @Test fun `J4 desktop failure after the touch update rolls back`() { desktopFailsAfter("playlists.touch") }
    @Test fun `J5 desktop failure after the listening events rolls back`() { desktopFailsAfter("events.insert") }

    @Test fun `J6 desktop caller cancellation mid-transaction yields no result and a complete, never partial, import`() = runBlocking {
        val before = dump()
        trace.clear()
        var result: WavdropBackupImportApplyResult? = null
        var cancelled = false
        val job = launch(start = CoroutineStart.LAZY) {
            try { result = desktop().applyImport(desktopBackup()) } catch (e: CancellationException) { cancelled = true; throw e }
        }
        onWrite = { w -> if (w == "playlists.insertPlaylist") job.cancel() }
        job.start(); job.join()
        onWrite = null
        assertTrue("cancelled, exception reached the caller", cancelled && job.isCancelled)
        assertNull(result)
        val afterCancel = dump()
        assertTrue(changedTables(before, afterCancel).containsAll(setOf("track_stats", "playlists", "playlist_songs", "track_listen_events")))
        assertTrue("the last write (events) completed too", "events.insert" in trace)
        desktop().applyImport(desktopBackup())
        assertEquals("re-import is idempotent", afterCancel, dump())
    }

    @Test fun `J7 desktop CancellationException thrown inside the transaction rolls everything back`() = runBlocking {
        val before = dump()
        trace.clear()
        onWrite = { w -> if (w == "playlists.insertPlaylist") throw CancellationException("injected") }
        val thrown = runCatching { desktop().applyImport(desktopBackup()) }.exceptionOrNull()
        onWrite = null
        assertTrue(thrown is CancellationException)
        assertUnchanged(before)
        assertTrue(desktop().applyImport(desktopBackup()).matchedTracks > 0)
    }
}
