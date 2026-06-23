package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "track_stats")
data class TrackStatsEntity(
    @PrimaryKey val songId: Long,
    val contentUri: String,           // secondary key / fallback if songId changes after rescan
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val lastPlayedAt: Long = 0L,       // epoch ms — last meaningful counted play
    val lastListenedAt: Long = 0L,    // epoch ms — last time user listened ≥ 5 s (for Recently Played)
    val totalListeningTimeMs: Long = 0L,
    val isFavorite: Boolean = false,
)
