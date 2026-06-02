package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.launchpoint.wavdrop.data.model.Playlist

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0L,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toDomain() = Playlist(
        id        = playlistId,
        name      = name,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
