package com.launchpoint.wavdrop.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Singleton
class ResumeBehaviorSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<ResumeBehaviorSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            ResumeBehaviorSettings(
                pauseOnAudioDisconnect = prefs[PAUSE_ON_AUDIO_DISCONNECT] ?: true,
                rememberLastTrack      = prefs[REMEMBER_LAST_TRACK] ?: true,
                rememberPosition       = prefs[REMEMBER_POSITION] ?: true,
                restoreQueue           = prefs[RESTORE_QUEUE] ?: true,
                autoResumeOnHeadphones = prefs[AUTO_RESUME_HEADPHONES] ?: false,
                autoResumeOnBluetooth  = prefs[AUTO_RESUME_BLUETOOTH] ?: false,
            )
        }

    suspend fun setPauseOnAudioDisconnect(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PAUSE_ON_AUDIO_DISCONNECT] = enabled }
    }

    suspend fun setRememberLastTrack(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[REMEMBER_LAST_TRACK] = enabled }
    }

    suspend fun setRememberPosition(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[REMEMBER_POSITION] = enabled }
    }

    suspend fun setRestoreQueue(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[RESTORE_QUEUE] = enabled }
    }

    suspend fun setAutoResumeOnHeadphones(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[AUTO_RESUME_HEADPHONES] = enabled }
    }

    suspend fun setAutoResumeOnBluetooth(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[AUTO_RESUME_BLUETOOTH] = enabled }
    }

    private companion object {
        val PAUSE_ON_AUDIO_DISCONNECT = booleanPreferencesKey("playback_pause_on_audio_disconnect")
        val REMEMBER_LAST_TRACK       = booleanPreferencesKey("resume_remember_last_track")
        val REMEMBER_POSITION      = booleanPreferencesKey("resume_remember_position")
        val RESTORE_QUEUE          = booleanPreferencesKey("resume_restore_queue")
        val AUTO_RESUME_HEADPHONES = booleanPreferencesKey("resume_auto_headphones")
        val AUTO_RESUME_BLUETOOTH  = booleanPreferencesKey("resume_auto_bluetooth")
    }
}
