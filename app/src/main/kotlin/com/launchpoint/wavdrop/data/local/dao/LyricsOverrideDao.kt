package com.launchpoint.wavdrop.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.launchpoint.wavdrop.data.local.entity.LyricsOverrideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LyricsOverrideDao {

    @Query("SELECT * FROM lyrics_overrides WHERE songId = :songId")
    fun observeBySongId(songId: Long): Flow<LyricsOverrideEntity?>

    @Query(
        """
        SELECT * FROM lyrics_overrides
        WHERE songId = :songId OR contentUri = :contentUri
        ORDER BY CASE WHEN songId = :songId THEN 0 ELSE 1 END
        LIMIT 1
        """,
    )
    suspend fun getForSong(songId: Long, contentUri: String): LyricsOverrideEntity?

    @Upsert
    suspend fun upsert(entity: LyricsOverrideEntity)

    @Query("DELETE FROM lyrics_overrides WHERE songId = :songId OR contentUri = :contentUri")
    suspend fun deleteForSong(songId: Long, contentUri: String)
}
