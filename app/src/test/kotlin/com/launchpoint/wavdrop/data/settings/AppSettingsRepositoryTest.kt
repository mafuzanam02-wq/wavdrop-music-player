package com.launchpoint.wavdrop.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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

    @Test
    fun `crossfade duration missing defaults to off`() = runBlocking {
        assertEquals(0L, repository().crossfadeDurationMs.first())
    }

    @Test
    fun `crossfade duration round trips a valid value`() = runBlocking {
        val repository = repository()
        repository.setCrossfadeDurationMs(6_000L)
        assertEquals(6_000L, repository.crossfadeDurationMs.first())
    }

    @Test
    fun `crossfade duration write normalizes before persisting`() = runBlocking {
        val key = longPreferencesKey("crossfade_duration_ms")
        for ((raw, expected) in listOf(-50L to 0L, 0L to 0L, 1L to 1_000L, 999L to 1_000L, 12_000L to 12_000L, 99_999L to 12_000L)) {
            val dataStore = PreferenceDataStoreFactory.create(
                produceFile = { temporaryFolder.newFile("cf-write-${System.nanoTime()}.preferences_pb") },
            )
            val repository = AppSettingsRepository(dataStore)
            repository.setCrossfadeDurationMs(raw)
            assertEquals("raw $raw", expected, dataStore.data.first()[key])
            assertEquals("raw $raw", expected, repository.crossfadeDurationMs.first())
        }
    }

    @Test
    fun `crossfade duration invalid stored values are normalized on read`() = runBlocking {
        val key = longPreferencesKey("crossfade_duration_ms")
        for ((raw, expected) in listOf(-1L to 0L, 0L to 0L, 1L to 1_000L, 999L to 1_000L, 1_000L to 1_000L, 6_000L to 6_000L, 12_000L to 12_000L, 12_001L to 12_000L)) {
            val dataStore = PreferenceDataStoreFactory.create(
                produceFile = { temporaryFolder.newFile("cf-read-${System.nanoTime()}.preferences_pb") },
            )
            dataStore.edit { it[key] = raw }
            assertEquals("raw $raw", expected, AppSettingsRepository(dataStore).crossfadeDurationMs.first())
        }
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
