package com.launchpoint.wavdrop.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.launchpoint.wavdrop.data.local.entity.PendingBackupExtensionEntity

@Dao
interface PendingBackupExtensionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingBackupExtensionEntity)

    @Query("SELECT * FROM pending_backup_extensions WHERE rootName = :rootName")
    suspend fun getByRootName(rootName: String): PendingBackupExtensionEntity?
}
