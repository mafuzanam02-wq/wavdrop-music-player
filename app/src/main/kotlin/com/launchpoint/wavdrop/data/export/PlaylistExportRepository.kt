package com.launchpoint.wavdrop.data.export

import androidx.room.withTransaction
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.repository.toDomain
import com.launchpoint.wavdrop.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject

sealed interface ExportPrepareResult {
    data class Ready(val plan: ExportPlan) : ExportPrepareResult
    object PlaylistMissing : ExportPrepareResult
    object Empty : ExportPrepareResult
    data class NothingExportable(val totalCount: Int) : ExportPrepareResult
}

interface PlaylistExporter {
    suspend fun prepare(playlistId: Long): ExportPrepareResult
    suspend fun write(plan: ExportPlan, openOutput: (String) -> OutputStream?): ExportWriteResult
}

/** Read-only: never touches playlists, songs, the queue or the player. */
class PlaylistExportRepository @Inject constructor(
    private val db: WavdropDatabase,
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val pathSource: MediaPathSource,
) : PlaylistExporter {

    override suspend fun prepare(playlistId: Long): ExportPrepareResult {
        val (playlist, rows, songs) = db.withTransaction {
            val p = playlistDao.getAllPlaylistsSnapshot().firstOrNull { it.playlistId == playlistId }
                ?: return@withTransaction Triple(null, emptyList(), emptyMap<Long, Song>())
            val r = playlistDao.getSongsForPlaylistSnapshot(playlistId)
            val s = HashMap<Long, Song>()
            r.map { it.songId }.distinct().chunked(MediaPathRules.CHUNK_SIZE).forEach { ids ->
                songDao.getSongsByIds(ids).forEach { s[it.id] = it.toDomain() }
            }
            Triple(p, r, s)
        }
        if (playlist == null) return ExportPrepareResult.PlaylistMissing
        if (rows.isEmpty()) return ExportPrepareResult.Empty
        val paths = if (songs.isEmpty()) emptyMap() else pathSource.resolvePaths(songs.keys.toSet())
        val plan = PlaylistExportPlanner.plan(playlistId, playlist.name, rows, songs, paths)
        return if (plan.entries.isEmpty()) ExportPrepareResult.NothingExportable(plan.totalCount)
        else ExportPrepareResult.Ready(plan)
    }

    override suspend fun write(plan: ExportPlan, openOutput: (String) -> OutputStream?): ExportWriteResult =
        withContext(Dispatchers.IO) {
            M3u8DocumentWriter.write(M3u8Formatter.toBytes(plan.entries.map { it.entry }), openOutput)
        }
}
