package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.launchpoint.wavdrop.data.model.PlaylistSong

@Entity(
    tableName   = "playlist_songs",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [ForeignKey(
        entity        = PlaylistEntity::class,
        parentColumns = ["playlistId"],
        childColumns  = ["playlistId"],
        onDelete      = ForeignKey.CASCADE,
    )],
    indices = [Index("playlistId")],
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: Long,
    val position: Int,
) {
    fun toDomain() = PlaylistSong(
        playlistId = playlistId,
        songId     = songId,
        position   = position,
    )
}
