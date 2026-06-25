package com.launchpoint.wavdrop.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSettingsRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `previous button behavior defaults to restart current`() = runBlocking {
        val repository = repository()

        assertEquals(
            PreviousButtonBehavior.RESTART_CURRENT,
            repository.previousButtonBehavior.first(),
        )
    }

    @Test
    fun `previous button behavior saves previous track`() = runBlocking {
        val repository = repository()

        repository.setPreviousButtonBehavior(PreviousButtonBehavior.PREVIOUS_TRACK)

        assertEquals(
            PreviousButtonBehavior.PREVIOUS_TRACK,
            repository.previousButtonBehavior.first(),
        )
    }

    @Test
    fun `previous button behavior invalid stored value falls back to restart current`() = runBlocking {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { temporaryFolder.newFile("invalid.preferences_pb") },
        )
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("previous_button_behavior")] = "NOT_A_BEHAVIOR"
        }
        val repository = AppSettingsRepository(dataStore)

        assertEquals(
            PreviousButtonBehavior.RESTART_CURRENT,
            repository.previousButtonBehavior.first(),
        )
    }

    private fun repository(): AppSettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = {
                temporaryFolder.newFile("settings-${System.nanoTime()}.preferences_pb")
            },
        )
        return AppSettingsRepository(dataStore)
    }
}
