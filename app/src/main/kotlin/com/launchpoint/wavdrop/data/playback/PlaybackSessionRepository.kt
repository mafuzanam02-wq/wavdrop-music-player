package com.launchpoint.wavdrop.data.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.launchpoint.wavdrop.playback.RepeatMode
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackSessionRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val queueIds      = stringPreferencesKey("session_queue_ids")
        val currentSongId = longPreferencesKey("session_current_song_id")
        val currentIndex  = intPreferencesKey("session_current_index")
        val positionMs    = longPreferencesKey("session_position_ms")
        val repeatMode    = stringPreferencesKey("session_repeat_mode")
        val shuffleEnabled = booleanPreferencesKey("session_shuffle_enabled")
        val updatedAtMs   = longPreferencesKey("session_updated_at_ms")
    }

    suspend fun save(snapshot: PlaybackSessionSnapshot) {
        dataStore.edit { prefs ->
            prefs[Keys.queueIds]       = snapshot.queueSongIds.joinToString(",")
            prefs[Keys.currentSongId]  = snapshot.currentSongId ?: -1L
            prefs[Keys.currentIndex]   = snapshot.currentIndex
            prefs[Keys.positionMs]     = snapshot.positionMs
            prefs[Keys.repeatMode]     = snapshot.repeatMode.name
            prefs[Keys.shuffleEnabled] = snapshot.shuffleEnabled
            prefs[Keys.updatedAtMs]    = snapshot.updatedAtMs
        }
    }

    suspend fun load(): PlaybackSessionSnapshot? {
        val prefs = dataStore.data.first()
        val rawIds = prefs[Keys.queueIds] ?: return null
        val ids = PlaybackSessionRules.parseQueueIds(rawIds)
        if (ids.isEmpty()) return null
        val songId = prefs[Keys.currentSongId]?.takeIf { it >= 0L }
        return PlaybackSessionSnapshot(
            queueSongIds  = ids,
            currentSongId = songId,
            currentIndex  = prefs[Keys.currentIndex] ?: 0,
            positionMs    = prefs[Keys.positionMs] ?: 0L,
            repeatMode    = PlaybackSessionRules.parseRepeatMode(prefs[Keys.repeatMode]),
            shuffleEnabled = prefs[Keys.shuffleEnabled] ?: false,
            updatedAtMs   = prefs[Keys.updatedAtMs] ?: 0L,
        )
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.queueIds)
            prefs.remove(Keys.currentSongId)
            prefs.remove(Keys.currentIndex)
            prefs.remove(Keys.positionMs)
            prefs.remove(Keys.repeatMode)
            prefs.remove(Keys.shuffleEnabled)
            prefs.remove(Keys.updatedAtMs)
        }
    }
}
