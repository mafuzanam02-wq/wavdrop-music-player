package com.launchpoint.wavdrop.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetAppearanceDataStore by preferencesDataStore(name = "wavdrop_widget_appearance")

/**
 * Per-widget appearance, keyed by the launcher-assigned `appWidgetId`: two string preferences per widget
 * (`style_<id>`, `intensity_<id>`). Keys are per id, so writing one widget can never touch another. A widget with no entry
 * (every widget placed before customization existed) reads as [WidgetAppearance.DEFAULT]; no migration step is needed.
 *
 * Id 0 ([AppWidgetManager.INVALID_APPWIDGET_ID]) and negatives are never stored or returned.
 */
@Singleton
class WidgetAppearanceStore internal constructor(private val dataStore: DataStore<Preferences>) {

    @Inject constructor(@ApplicationContext context: Context) : this(context.widgetAppearanceDataStore)

    private fun styleKey(id: Int) = stringPreferencesKey("style_$id")
    private fun intensityKey(id: Int) = stringPreferencesKey("intensity_$id")

    private fun valid(id: Int) = id != AppWidgetManager.INVALID_APPWIDGET_ID && id > 0

    suspend fun get(id: Int): WidgetAppearance {
        if (!valid(id)) return WidgetAppearance.DEFAULT
        return read(dataStore.data.first(), id)
    }

    /** One datastore read for many widgets (a playback-state update refreshes them all). */
    suspend fun load(ids: Collection<Int>): Map<Int, WidgetAppearance> {
        val prefs = dataStore.data.first()
        return ids.associateWith { if (valid(it)) read(prefs, it) else WidgetAppearance.DEFAULT }
    }

    private fun read(prefs: Preferences, id: Int) = WidgetAppearance(
        style = WidgetBackgroundStyle.fromKey(prefs[styleKey(id)]),
        intensity = WidgetArtworkIntensity.fromKey(prefs[intensityKey(id)]),
    )

    /** Returns false (and stores nothing) for an invalid id. */
    suspend fun save(id: Int, appearance: WidgetAppearance): Boolean {
        if (!valid(id)) return false
        dataStore.edit { prefs ->
            prefs[styleKey(id)] = appearance.style.key
            prefs[intensityKey(id)] = appearance.intensity.key
        }
        return true
    }

    /** Widget instances removed by the user: forget their settings. */
    suspend fun remove(ids: Collection<Int>) {
        val real = ids.filter(::valid)
        if (real.isEmpty()) return
        dataStore.edit { prefs ->
            real.forEach {
                prefs.remove(styleKey(it))
                prefs.remove(intensityKey(it))
            }
        }
    }

    /**
     * Launcher backup/restore reassigns ids ([android.appwidget.AppWidgetProvider.onRestored]): carry each old id's settings to
     * its new id and drop the old entry. Pairs with an invalid side, or ids that are equal, are skipped.
     */
    suspend fun migrate(oldIds: IntArray, newIds: IntArray) {
        val pairs = oldIds.zip(newIds).filter { (o, n) -> valid(o) && valid(n) && o != n }
        if (pairs.isEmpty()) return
        dataStore.edit { prefs ->
            pairs.forEach { (old, new) ->
                prefs[styleKey(old)]?.let { prefs[styleKey(new)] = it }
                prefs[intensityKey(old)]?.let { prefs[intensityKey(new)] = it }
                prefs.remove(styleKey(old))
                prefs.remove(intensityKey(old))
            }
        }
    }

    /** The last widget was removed ([android.appwidget.AppWidgetProvider.onDisabled]): nothing left to remember. */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}
