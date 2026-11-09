package com.launchpoint.wavdrop.data.export

import androidx.room.Room
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistExportRepositoryTest {
    private lateinit var db: WavdropDatabase

    private class FakePaths(val map: Map<Long, String>) : MediaPathSource {
        val calls = mutableListOf<Set<Long>>()
        override suspend fun resolvePaths(songIds: Set<Long>): Map<Long, String> {
            calls += songIds
            return map.filterKeys { it in songIds }
        }
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun repo(paths: MediaPathSource) = PlaylistExportRepository(db, db.playlistDao(), db.songDao(), paths)

    private fun songEntity(id: Long) =
        SongEntity(id, "Song $id", "Artist", "Album", 1, 60_000, "content://media/external/audio/media/$id", 1, 1, 2020)

    private fun seed(songIds: List<Long>, order: List<Long>): Long = runBlocking {
        db.songDao().upsertAll(songIds.map(::songEntity))
        val pid = db.playlistDao().insertPlaylist(PlaylistEntity(name = "Mix", createdAt = 1, updatedAt = 2))
        // Insert in reverse so insertion order differs from saved position order.
        db.playlistDao().insertSongs(order.mapIndexed { i, s -> PlaylistSongEntity(pid, s, i) }.reversed())
        pid
    }

    @Test fun `saved order and duplicates come from the dao snapshot`() = runBlocking {
        val pid = seed(listOf(1, 2, 3), listOf(2, 1, 2, 3))
        val paths = FakePaths((1L..3L).associateWith { "/storage/emulated/0/Music/$it.mp3" })
        val r = repo(paths).prepare(pid) as ExportPrepareResult.Ready
        assertEquals(listOf(2L, 1L, 2L, 3L), r.plan.entries.map { it.songId })
        assertEquals(listOf(0, 1, 2, 3), r.plan.entries.map { it.position })
    }

    @Test fun `missing song rows and stale ids are counted unresolved`() = runBlocking {
        val pid = seed(listOf(1, 2), listOf(1, 99, 2, 2))
        val paths = FakePaths(mapOf(1L to "/storage/emulated/0/Music/1.mp3")) // 2 has no media row
        val r = repo(paths).prepare(pid) as ExportPrepareResult.Ready
        assertEquals(4, r.plan.totalCount); assertEquals(1, r.plan.resolvedCount); assertEquals(3, r.plan.unresolvedCount)
        assertEquals(
            listOf(UnresolvedReason.SongRowMissing, UnresolvedReason.NoFilePath, UnresolvedReason.NoFilePath),
            r.plan.unresolved.map { it.reason },
        )
    }

    @Test fun `empty playlist and all-unresolved playlist are not exportable`() = runBlocking {
        val empty = runBlocking { db.playlistDao().insertPlaylist(PlaylistEntity(name = "E", createdAt = 1, updatedAt = 1)) }
        assertEquals(ExportPrepareResult.Empty, repo(FakePaths(emptyMap())).prepare(empty))
        val pid = seed(listOf(1), listOf(1, 1))
        assertEquals(ExportPrepareResult.NothingExportable(2), repo(FakePaths(emptyMap())).prepare(pid))
        assertEquals(ExportPrepareResult.PlaylistMissing, repo(FakePaths(emptyMap())).prepare(12345))
    }

    @Test fun `preparing never mutates the saved playlist`() = runBlocking {
        val pid = seed(listOf(1, 2), listOf(2, 1, 2))
        val before = db.playlistDao().getSongsForPlaylistSnapshot(pid)
        val plBefore = db.playlistDao().getAllPlaylistsSnapshot()
        repo(FakePaths(mapOf(1L to "/a.mp3", 2L to "/b.mp3"))).prepare(pid)
        assertEquals(before, db.playlistDao().getSongsForPlaylistSnapshot(pid))
        assertEquals(plBefore, db.playlistDao().getAllPlaylistsSnapshot())
    }

    @Test fun `large playlist is resolved with one batched path request not one per entry`() = runBlocking {
        val ids = (1L..1_100L).toList()
        val pid = seed(ids, ids + ids.take(200)) // 1,300 entries
        val paths = FakePaths(ids.associateWith { "/storage/emulated/0/Music/$it.mp3" })
        val r = repo(paths).prepare(pid) as ExportPrepareResult.Ready
        assertEquals(1_300, r.plan.resolvedCount)
        assertEquals(1, paths.calls.size)
        assertEquals(1_100, paths.calls.single().size)
    }

    @Test fun `write serializes only resolvable entries and reports counts`() = runBlocking {
        val pid = seed(listOf(1, 2), listOf(1, 99, 2, 1))
        val paths = FakePaths(mapOf(1L to "/storage/emulated/0/Music/1.mp3", 2L to "/storage/1A2B-3C4D/Music/2.mp3"))
        val r = repo(paths)
        val plan = (r.prepare(pid) as ExportPrepareResult.Ready).plan
        val out = ByteArrayOutputStream()
        val result = r.write(plan) { out }
        assertEquals(ExportWriteResult.Success, result)
        val text = out.toString("UTF-8")
        assertEquals(
            listOf("/storage/emulated/0/Music/1.mp3", "/storage/1A2B-3C4D/Music/2.mp3", "/storage/emulated/0/Music/1.mp3"),
            text.lines().filter { it.isNotEmpty() && !it.startsWith("#") },
        )
        assertTrue(text.startsWith("#EXTM3U\n"))
    }
}
