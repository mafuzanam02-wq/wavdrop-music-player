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

    @Test
    fun `transport fade defaults to disabled at 300 ms`() = runBlocking {
        val r = repository()
        assertEquals(false, r.transportFadeEnabled.first())
        assertEquals(300L, r.transportFadeDurationMs.first())
    }

    @Test
    fun `transport fade settings round trip`() = runBlocking {
        val r = repository()
        r.setTransportFadeEnabled(true)
        r.setTransportFadeDurationMs(450L)
        assertEquals(true, r.transportFadeEnabled.first())
        assertEquals(450L, r.transportFadeDurationMs.first())
    }

    @Test
    fun `transport fade invalid stored values are normalized on read and write`() = runBlocking {
        val key = longPreferencesKey("transport_fade_duration_ms")
        for ((raw, expected) in listOf(-1L to 100L, 0L to 100L, 99L to 100L, 310L to 300L, 801L to 800L, Long.MAX_VALUE to 800L)) {
            val ds = PreferenceDataStoreFactory.create(produceFile = { temporaryFolder.newFile("tf-${System.nanoTime()}.preferences_pb") })
            ds.edit { it[key] = raw }
            assertEquals("read $raw", expected, AppSettingsRepository(ds).transportFadeDurationMs.first())
            val ds2 = PreferenceDataStoreFactory.create(produceFile = { temporaryFolder.newFile("tf2-${System.nanoTime()}.preferences_pb") })
            AppSettingsRepository(ds2).setTransportFadeDurationMs(raw)
            assertEquals("write $raw", expected, ds2.data.first()[key])
        }
    }

    @Test
    fun `transport fade settings do not touch the crossfade setting`() = runBlocking {
        val r = repository()
        r.setCrossfadeDurationMs(6_000L)
        r.setTransportFadeEnabled(true)
        r.setTransportFadeDurationMs(800L)
        assertEquals(6_000L, r.crossfadeDurationMs.first())
        val fresh = repository()
        fresh.setTransportFadeEnabled(true)
        assertEquals(0L, fresh.crossfadeDurationMs.first())
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
