package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lyrics_overrides",
    indices = [Index(value = ["contentUri"])],
)
data class LyricsOverrideEntity(
    @PrimaryKey val songId: Long,
    val contentUri: String,
    val lyrics: String,
    val updatedAt: Long,
)
