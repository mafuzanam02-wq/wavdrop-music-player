package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_backup_extensions")
data class PendingBackupExtensionEntity(
    @PrimaryKey val rootName: String,
    val rawJson: String,
    val importedAt: Long,
)
