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

/**
 * Device-local, one-way fact: "audio permission has been observed granted on this installation at least once". It lets the app tell
 * a revoked permission from a first run. It is never reset, is NOT part of backup/restore (it describes this device, not a portable
 * preference), and is independent of whether the songs table has rows.
 */
@Singleton
class AudioPermissionHistoryRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val hasEverGranted: Flow<Boolean> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it[EVER_GRANTED_KEY] ?: false }

    /** Idempotent: writing true repeatedly is a no-op. There is deliberately no way to set it back to false. */
    suspend fun markGranted() {
        dataStore.edit { preferences ->
            if (preferences[EVER_GRANTED_KEY] != true) preferences[EVER_GRANTED_KEY] = true
        }
    }

    companion object {
        val EVER_GRANTED_KEY = booleanPreferencesKey("audio_permission_ever_granted")
    }
}
