package com.launchpoint.wavdrop.ui.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.launchpoint.wavdrop.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetStateDataStore by preferencesDataStore(name = "wavdrop_widget_state")

/**
 * Snapshot written exclusively by PlaybackService's direct ExoPlayer listener.
 * No UI-side component writes this; it represents real player state only.
 */
data class WidgetPlaybackSnapshot(
    val title: String,
    val artist: String,
    val albumId: Long,
    val isPlaying: Boolean,
    val hasActiveMedia: Boolean,
    val updatedAt: Long,
)

@Singleton
class WidgetStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private val KEY_TITLE            = stringPreferencesKey("wgt_title")
        private val KEY_ARTIST           = stringPreferencesKey("wgt_artist")
        private val KEY_ALBUM_ID         = longPreferencesKey("wgt_album_id")
        private val KEY_IS_PLAYING       = booleanPreferencesKey("wgt_is_playing")
        private val KEY_HAS_ACTIVE_MEDIA = booleanPreferencesKey("wgt_has_active_media")
        private val KEY_UPDATED_AT       = longPreferencesKey("wgt_updated_at")
        private const val TAG            = "WavdropWidget"
    }

    suspend fun save(snapshot: WidgetPlaybackSnapshot) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[store] save START title=${snapshot.title} isPlaying=${snapshot.isPlaying}")
        context.widgetStateDataStore.edit { prefs ->
            prefs[KEY_TITLE]            = snapshot.title
            prefs[KEY_ARTIST]           = snapshot.artist
            prefs[KEY_ALBUM_ID]         = snapshot.albumId
            prefs[KEY_IS_PLAYING]       = snapshot.isPlaying
            prefs[KEY_HAS_ACTIVE_MEDIA] = snapshot.hasActiveMedia
            prefs[KEY_UPDATED_AT]       = snapshot.updatedAt
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "[store] save COMPLETE title=${snapshot.title} isPlaying=${snapshot.isPlaying}")
    }

    /** Partial update: only isPlaying and timestamp. Track metadata is untouched. */
    suspend fun updateIsPlaying(isPlaying: Boolean) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[store] updateIsPlaying START isPlaying=$isPlaying")
        context.widgetStateDataStore.edit { prefs ->
            prefs[KEY_IS_PLAYING] = isPlaying
            prefs[KEY_UPDATED_AT] = System.currentTimeMillis()
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "[store] updateIsPlaying COMPLETE isPlaying=$isPlaying")
    }

    /** Resets to idle state when the player has no media loaded. */
    suspend fun clear() {
        if (BuildConfig.DEBUG) Log.d(TAG, "[store] clear START")
        context.widgetStateDataStore.edit { prefs ->
            prefs[KEY_HAS_ACTIVE_MEDIA] = false
            prefs[KEY_IS_PLAYING]       = false
            prefs[KEY_UPDATED_AT]       = System.currentTimeMillis()
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "[store] clear COMPLETE")
    }

    suspend fun load(): WidgetPlaybackSnapshot? {
        val prefs = context.widgetStateDataStore.data.first()
        val title = prefs[KEY_TITLE] ?: return null.also {
            if (BuildConfig.DEBUG) Log.d(TAG, "[store] load → null (no title key)")
        }
        val snapshot = WidgetPlaybackSnapshot(
            title          = title,
            artist         = prefs[KEY_ARTIST]           ?: "",
            albumId        = prefs[KEY_ALBUM_ID]         ?: 0L,
            isPlaying      = prefs[KEY_IS_PLAYING]       ?: false,
            hasActiveMedia = prefs[KEY_HAS_ACTIVE_MEDIA] ?: false,
            updatedAt      = prefs[KEY_UPDATED_AT]       ?: 0L,
        )
        if (BuildConfig.DEBUG) Log.d(TAG, "[store] load → title=${snapshot.title} isPlaying=${snapshot.isPlaying} updatedAt=${snapshot.updatedAt}")
        return snapshot
    }
}
