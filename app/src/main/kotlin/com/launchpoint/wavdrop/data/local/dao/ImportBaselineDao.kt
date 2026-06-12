package com.launchpoint.wavdrop.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.launchpoint.wavdrop.data.local.entity.ImportBaselineEntity

@Dao
interface ImportBaselineDao {

    @Query(
        """
        SELECT * FROM import_baselines
        WHERE songId = :songId
          AND sourceType = :sourceType
          AND sourceKey = :sourceKey
        LIMIT 1
        """
    )
    suspend fun getBaseline(
        songId: Long,
        sourceType: String,
        sourceKey: String,
    ): ImportBaselineEntity?

    @Upsert
    suspend fun upsertBaseline(entity: ImportBaselineEntity)

    @Query("SELECT * FROM import_baselines WHERE sourceType = :sourceType")
    suspend fun getBaselinesForSource(sourceType: String): List<ImportBaselineEntity>

    @Query("SELECT * FROM import_baselines ORDER BY sourceType ASC, sourceKey ASC, songId ASC")
    suspend fun getAllImportBaselinesSnapshot(): List<ImportBaselineEntity>

    @Query("DELETE FROM import_baselines WHERE songId = :songId")
    suspend fun deleteBySongId(songId: Long)
}
