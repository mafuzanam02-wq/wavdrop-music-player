package com.launchpoint.wavdrop.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Singleton
class AppSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val startupDestination: Flow<StartupDestination> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[STARTUP_DESTINATION_KEY]?.toStartupDestination()
                ?: StartupDestination.SONGS
        }

    suspend fun setStartupDestination(destination: StartupDestination) {
        dataStore.edit { preferences ->
            preferences[STARTUP_DESTINATION_KEY] = destination.name
        }
    }

    private fun String.toStartupDestination(): StartupDestination? =
        runCatching { StartupDestination.valueOf(this) }.getOrNull()

    private companion object {
        val STARTUP_DESTINATION_KEY = stringPreferencesKey("startup_destination")
    }
}
