package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val uri: String,
    val dateAdded: Long,
    val trackNumber: Int,
    val year: Int,
    val folderPath: String? = null,
    val folderName: String? = null,
)
