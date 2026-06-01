package com.launchpoint.wavdrop.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.entity.SongEntity

@Database(
    entities = [SongEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class WavdropDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
}
