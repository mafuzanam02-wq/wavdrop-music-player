package com.launchpoint.wavdrop.data.playback

import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.launchpoint.wavdrop.BuildConfig
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
        val playbackOrder = stringPreferencesKey("session_playback_order")
        val currentSongId = longPreferencesKey("session_current_song_id")
        val currentIndex  = intPreferencesKey("session_current_index")
        val positionMs    = longPreferencesKey("session_position_ms")
        val repeatMode    = stringPreferencesKey("session_repeat_mode")
        val shuffleEnabled = booleanPreferencesKey("session_shuffle_enabled")
        val updatedAtMs   = longPreferencesKey("session_updated_at_ms")
    }

    suspend fun save(snapshot: PlaybackSessionSnapshot) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val queueIdsCsv = snapshot.queueSongIds.joinToString(",")
        val playbackOrderCsv = snapshot.playbackOrder?.joinToString(",")
        dataStore.edit { prefs ->
            prefs[Keys.queueIds]       = queueIdsCsv
            playbackOrderCsv?.let { prefs[Keys.playbackOrder] = it }
                ?: prefs.remove(Keys.playbackOrder)
            prefs[Keys.currentSongId]  = snapshot.currentSongId ?: -1L
            prefs[Keys.currentIndex]   = snapshot.currentIndex
            prefs[Keys.positionMs]     = snapshot.positionMs
            prefs[Keys.repeatMode]     = snapshot.repeatMode.name
            prefs[Keys.shuffleEnabled] = snapshot.shuffleEnabled
            prefs[Keys.updatedAtMs]    = snapshot.updatedAtMs
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                SESSION_PERF_TAG,
                "save queueSize=${snapshot.queueSongIds.size} " +
                    "playbackOrderSize=${snapshot.playbackOrder?.size ?: 0} " +
                    "currentIndex=${snapshot.currentIndex} " +
                    "queueCsvLength=${queueIdsCsv.length} " +
                    "playbackOrderCsvLength=${playbackOrderCsv?.length ?: 0} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
            )
        }
    }

    suspend fun load(): PlaybackSessionSnapshot? {
        val startedAtMs = SystemClock.elapsedRealtime()
        val prefs = dataStore.data.first()
        val rawIds = prefs[Keys.queueIds]
        if (rawIds == null) {
            logLoad(
                startedAtMs = startedAtMs,
                queueSize = 0,
                playbackOrderSize = 0,
                currentIndex = prefs[Keys.currentIndex] ?: 0,
                queueCsvLength = 0,
                playbackOrderCsvLength = prefs[Keys.playbackOrder]?.length ?: 0,
                result = "missing_queue",
            )
            return null
        }
        val rawPlaybackOrder = prefs[Keys.playbackOrder]
        val ids = PlaybackSessionRules.parseQueueIds(rawIds)
        if (ids.isEmpty()) {
            logLoad(
                startedAtMs = startedAtMs,
                queueSize = 0,
                playbackOrderSize = 0,
                currentIndex = prefs[Keys.currentIndex] ?: 0,
                queueCsvLength = rawIds.length,
                playbackOrderCsvLength = rawPlaybackOrder?.length ?: 0,
                result = "empty_queue",
            )
            return null
        }
        val playbackOrder = rawPlaybackOrder?.let(PlaybackSessionRules::parsePlaybackOrder)
        val songId = prefs[Keys.currentSongId]?.takeIf { it >= 0L }
        return PlaybackSessionSnapshot(
            queueSongIds  = ids,
            playbackOrder = playbackOrder,
            currentSongId = songId,
            currentIndex  = prefs[Keys.currentIndex] ?: 0,
            positionMs    = prefs[Keys.positionMs] ?: 0L,
            repeatMode    = PlaybackSessionRules.parseRepeatMode(prefs[Keys.repeatMode]),
            shuffleEnabled = prefs[Keys.shuffleEnabled] ?: false,
            updatedAtMs   = prefs[Keys.updatedAtMs] ?: 0L,
        ).also { snapshot ->
            logLoad(
                startedAtMs = startedAtMs,
                queueSize = snapshot.queueSongIds.size,
                playbackOrderSize = snapshot.playbackOrder?.size ?: 0,
                currentIndex = snapshot.currentIndex,
                queueCsvLength = rawIds.length,
                playbackOrderCsvLength = rawPlaybackOrder?.length ?: 0,
                result = "restored",
            )
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.queueIds)
            prefs.remove(Keys.playbackOrder)
            prefs.remove(Keys.currentSongId)
            prefs.remove(Keys.currentIndex)
            prefs.remove(Keys.positionMs)
            prefs.remove(Keys.repeatMode)
            prefs.remove(Keys.shuffleEnabled)
            prefs.remove(Keys.updatedAtMs)
        }
    }

    private fun logLoad(
        startedAtMs: Long,
        queueSize: Int,
        playbackOrderSize: Int,
        currentIndex: Int,
        queueCsvLength: Int,
        playbackOrderCsvLength: Int,
        result: String,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            SESSION_PERF_TAG,
            "load result=$result queueSize=$queueSize playbackOrderSize=$playbackOrderSize " +
                "currentIndex=$currentIndex queueCsvLength=$queueCsvLength " +
                "playbackOrderCsvLength=$playbackOrderCsvLength " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
        )
    }

    private companion object {
        const val SESSION_PERF_TAG = "WavdropSessionPerf"
    }
}
