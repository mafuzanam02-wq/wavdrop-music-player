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

    /** Recovery Restore only: the desktopOverlay extension is exported verbatim, so the safety snapshot covers it. */
    @Query("DELETE FROM pending_backup_extensions WHERE rootName = :rootName")
    suspend fun deleteByRootNameForRecovery(rootName: String)
}
