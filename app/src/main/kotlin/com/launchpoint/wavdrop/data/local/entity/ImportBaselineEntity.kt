package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "import_baselines",
    primaryKeys = ["songId", "sourceType", "sourceKey"],
    indices = [
        Index(value = ["sourceType", "sourceKey"]),
    ],
)
data class ImportBaselineEntity(
    val songId: Long,
    val sourceType: String,
    val sourceKey: String,
    val lastImportedPlayCount: Int,
    val lastImportedSkipCount: Int,
    val lastImportedAt: Long,
)
