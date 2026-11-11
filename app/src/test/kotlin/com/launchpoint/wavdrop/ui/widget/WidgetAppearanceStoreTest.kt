package com.launchpoint.wavdrop.ui.widget

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** P5-UI-15B — per-widget appearance storage: defaults, persistence, isolation, cleanup, invalid ids, launcher restore. */
class WidgetAppearanceStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: WidgetAppearanceStore

    private fun open(): DataStore<Preferences> {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @Before fun setUp() {
        file = File(tmp.newFolder(), "widget_appearance.preferences_pb")
        dataStore = open()
        store = WidgetAppearanceStore(dataStore)
    }

    @After fun tearDown() { scope.cancel() }

    private val lightArtwork = WidgetAppearance(WidgetBackgroundStyle.ARTWORK, WidgetArtworkIntensity.LIGHT)
    private val darkArtwork = WidgetAppearance(WidgetBackgroundStyle.ARTWORK, WidgetArtworkIntensity.DARK)
    private val solid = WidgetAppearance(WidgetBackgroundStyle.SOLID, WidgetArtworkIntensity.BALANCED)

    // ── 1. defaults for widgets that existed before customization ──────────────────────────────────────────────────

    @Test fun aWidgetWithNoStoredSettingsIsArtworkBalancedWithoutAnyMigration() = runBlocking {
        assertEquals(WidgetAppearance.DEFAULT, store.get(41))
        assertEquals(WidgetBackgroundStyle.ARTWORK, WidgetAppearance.DEFAULT.style)
        assertEquals(WidgetArtworkIntensity.BALANCED, WidgetAppearance.DEFAULT.intensity)
        assertEquals(mapOf(1 to WidgetAppearance.DEFAULT, 2 to WidgetAppearance.DEFAULT), store.load(listOf(1, 2)))
    }

    @Test fun unknownStoredValuesFallBackToTheDefaultInsteadOfCrashing() = runBlocking {
        dataStore.edit {
            it[stringPreferencesKey("style_9")] = "neon"
            it[stringPreferencesKey("intensity_9")] = "blinding"
        }
        assertEquals(WidgetAppearance.DEFAULT, store.get(9))
    }

    // ── 2. persistence ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun savedSettingsSurviveAFreshStoreAndAFreshDataStoreOnTheSameFile() = runBlocking {
        assertTrue(store.save(7, lightArtwork))
        assertEquals(lightArtwork, WidgetAppearanceStore(dataStore).get(7))
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() // fully closes the first DataStore before reopening the file
        // A new DataStore over the same file = the process was restarted.
        val reopened = WidgetAppearanceStore(open())
        assertEquals(lightArtwork, reopened.get(7))
    }

    @Test fun solidIsStoredAndReadBack() = runBlocking {
        store.save(3, solid)
        assertEquals(WidgetBackgroundStyle.SOLID, store.get(3).style)
    }

    // ── 3. two widgets, different settings ────────────────────────────────────────────────────────────────────────

    @Test fun threeWidgetsKeepThreeIndependentAppearances() = runBlocking {
        store.save(10, lightArtwork)
        store.save(11, solid)
        store.save(12, darkArtwork)
        assertEquals(lightArtwork, store.get(10))
        assertEquals(solid, store.get(11))
        assertEquals(darkArtwork, store.get(12))
        assertEquals(mapOf(10 to lightArtwork, 11 to solid, 12 to darkArtwork), store.load(listOf(10, 11, 12)))
    }

    // ── 4. updating one widget leaves the others alone ───────────────────────────────────────────────────────────

    @Test fun changingOneWidgetDoesNotAlterAnother() = runBlocking {
        store.save(20, lightArtwork)
        store.save(21, darkArtwork)
        store.save(20, solid)
        assertEquals(solid, store.get(20))
        assertEquals(darkArtwork, store.get(21))
        // and a widget that was never configured is untouched too
        assertEquals(WidgetAppearance.DEFAULT, store.get(22))
    }

    // ── 5. removal / cleanup ─────────────────────────────────────────────────────────────────────────────────────

    @Test fun removingAWidgetForgetsOnlyThatWidget() = runBlocking {
        store.save(30, lightArtwork)
        store.save(31, darkArtwork)
        store.remove(listOf(30))
        assertEquals("removed widget reads as default", WidgetAppearance.DEFAULT, store.get(30))
        assertEquals(darkArtwork, store.get(31))
        val keysLeft = dataStore.data.first().asMap().keys.map { it.name }
        assertFalse("no leftover keys for the removed id", keysLeft.any { it.endsWith("_30") })
    }

    @Test fun removingSeveralWidgetsAtOnceAndClearAll() = runBlocking {
        store.save(40, lightArtwork); store.save(41, solid); store.save(42, darkArtwork)
        store.remove(listOf(40, 41))
        assertEquals(WidgetAppearance.DEFAULT, store.get(40))
        assertEquals(WidgetAppearance.DEFAULT, store.get(41))
        assertEquals(darkArtwork, store.get(42))
        store.clearAll()
        assertEquals(WidgetAppearance.DEFAULT, store.get(42))
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    // ── 6. invalid / missing widget ids ───────────────────────────────────────────────────────────────────────────

    @Test fun invalidIdsAreNeverStoredAndAlwaysReadAsDefault() = runBlocking {
        listOf(0, -1, Int.MIN_VALUE).forEach { bad ->
            assertFalse("save($bad) must be refused", store.save(bad, solid))
            assertEquals(WidgetAppearance.DEFAULT, store.get(bad))
        }
        store.remove(listOf(0, -5))
        assertEquals(WidgetAppearance.DEFAULT, store.load(listOf(0, -1))[0])
        assertTrue("nothing was written", dataStore.data.first().asMap().isEmpty())
    }

    // ── launcher restore: old ids → new ids ──────────────────────────────────────────────────────────────────────

    @Test fun aLauncherRestoreMovesSettingsFromOldIdsToNewIds() = runBlocking {
        store.save(50, lightArtwork)
        store.save(51, solid)
        store.migrate(intArrayOf(50, 51), intArrayOf(150, 151))
        assertEquals(lightArtwork, store.get(150))
        assertEquals(solid, store.get(151))
        assertEquals("old ids no longer hold data", WidgetAppearance.DEFAULT, store.get(50))
        assertEquals(WidgetAppearance.DEFAULT, store.get(51))
    }

    @Test fun restoreSkipsInvalidEqualOrUnconfiguredPairs() = runBlocking {
        store.save(60, darkArtwork)
        store.migrate(intArrayOf(60, 0, 62), intArrayOf(60, 160, -1))
        assertEquals("same id: untouched", darkArtwork, store.get(60))
        assertEquals(WidgetAppearance.DEFAULT, store.get(160))
        store.migrate(intArrayOf(70), intArrayOf(170))
        assertEquals("an unconfigured old id creates nothing", WidgetAppearance.DEFAULT, store.get(170))
        assertTrue(dataStore.data.first().asMap().keys.none { it.name.endsWith("_170") })
    }

    @Test fun mismatchedRestoreArraysOnlyPairTheCommonPrefix() = runBlocking {
        store.save(80, lightArtwork)
        store.migrate(intArrayOf(80, 81), intArrayOf(180))
        assertEquals(lightArtwork, store.get(180))
    }
}
