package com.launchpoint.wavdrop.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Singleton
class LibraryScanSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<LibraryScanSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            LibraryScanSettingsRules.normalize(
                LibraryScanSettings(
                    scanMode = preferences[SCAN_MODE_KEY]?.toScanMode()
                        ?: LibraryScanMode.WHOLE_DEVICE,
                    selectedFolderUris = preferences[SELECTED_FOLDER_URIS_KEY]
                        ?.toList()
                        .orEmpty(),
                    minimumTrackDurationSeconds = preferences[MINIMUM_TRACK_DURATION_SECONDS_KEY]
                        ?: LibraryScanSettingsRules.DEFAULT_MINIMUM_TRACK_DURATION_SECONDS,
                    includeWhatsAppVoiceNotes = preferences[INCLUDE_WHATSAPP_VOICE_NOTES_KEY]
                        ?: false,
                ),
            )
        }

    suspend fun setScanMode(scanMode: LibraryScanMode) {
        dataStore.edit { preferences ->
            preferences[SCAN_MODE_KEY] = scanMode.name
        }
    }

    suspend fun setMinimumTrackDurationSeconds(seconds: Int) {
        dataStore.edit { preferences ->
            preferences[MINIMUM_TRACK_DURATION_SECONDS_KEY] =
                LibraryScanSettingsRules.clampMinimumTrackDurationSeconds(seconds)
        }
    }

    suspend fun setSelectedFolderUris(uris: List<String>) {
        dataStore.edit { preferences ->
            preferences[SELECTED_FOLDER_URIS_KEY] = uris
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }
    }

    suspend fun setIncludeWhatsAppVoiceNotes(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[INCLUDE_WHATSAPP_VOICE_NOTES_KEY] = enabled
        }
    }

    suspend fun addSelectedFolderUri(folderUri: String) {
        dataStore.edit { preferences ->
            val current = preferences[SELECTED_FOLDER_URIS_KEY]?.toList().orEmpty()
            val updated = LibraryScanSettingsRules.withAddedFolderUri(
                LibraryScanSettings(selectedFolderUris = current),
                folderUri,
            )
            preferences[SELECTED_FOLDER_URIS_KEY] = updated.selectedFolderUris.toSet()
        }
    }

    suspend fun removeSelectedFolderUri(folderUri: String) {
        dataStore.edit { preferences ->
            val current = preferences[SELECTED_FOLDER_URIS_KEY]?.toList().orEmpty()
            val updated = LibraryScanSettingsRules.withRemovedFolderUri(
                LibraryScanSettings(selectedFolderUris = current),
                folderUri,
            )
            preferences[SELECTED_FOLDER_URIS_KEY] = updated.selectedFolderUris.toSet()
        }
    }

    private fun String.toScanMode(): LibraryScanMode? =
        runCatching { LibraryScanMode.valueOf(this) }.getOrNull()

    private companion object {
        val SCAN_MODE_KEY = stringPreferencesKey("library_scan_mode")
        val SELECTED_FOLDER_URIS_KEY = stringSetPreferencesKey("library_selected_folder_uris")
        val MINIMUM_TRACK_DURATION_SECONDS_KEY =
            intPreferencesKey("library_minimum_track_duration_seconds")
        val INCLUDE_WHATSAPP_VOICE_NOTES_KEY =
            booleanPreferencesKey("library_include_whatsapp_voice_notes")
    }
}
