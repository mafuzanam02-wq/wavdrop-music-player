package com.launchpoint.wavdrop.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Singleton
class HomeLayoutSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<HomeLayoutSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            val saved = prefs[HOME_VISIBLE_SECTIONS_KEY]
            val visibleSections = if (saved == null) {
                HomeSectionId.ALL
            } else {
                val parsed = saved
                    .mapNotNull { name -> runCatching { HomeSectionId.valueOf(name) }.getOrNull() }
                    .toSet()
                HomeLayoutSettingsRules.normalizeVisibleSections(parsed)
            }
            HomeLayoutSettings(
                visibleSections = visibleSections,
                homeSmartCollections = readHomeSmartCollections(prefs),
            )
        }

    suspend fun setVisibleSections(sections: Set<HomeSectionId>) {
        dataStore.edit { prefs ->
            prefs[HOME_VISIBLE_SECTIONS_KEY] =
                HomeLayoutSettingsRules.normalizeVisibleSections(sections)
                    .map { it.name }
                    .toSet()
        }
    }

    suspend fun setSectionVisible(id: HomeSectionId, visible: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[HOME_VISIBLE_SECTIONS_KEY]
                ?.mapNotNull { name -> runCatching { HomeSectionId.valueOf(name) }.getOrNull() }
                ?.toSet()
                ?: HomeSectionId.ALL
            val updated = HomeLayoutSettingsRules.withSectionVisible(
                HomeLayoutSettings(visibleSections = current),
                id,
                visible,
            )
            prefs[HOME_VISIBLE_SECTIONS_KEY] = updated.visibleSections.map { it.name }.toSet()
        }
    }

    /**
     * Persists the Home Smart Collections selection (WU-01). The input is sanitised to the
     * exact-three unique contract before writing, so DataStore never stores 0/1/2/4+ or
     * duplicate ids. Order is preserved as the Home display order.
     */
    suspend fun setHomeSmartCollections(types: List<SmartCollectionType>) {
        val sanitized = HomeSmartCollectionSelection.sanitize(types)
        dataStore.edit { prefs ->
            prefs[HOME_SMART_COLLECTIONS_KEY] =
                HomeSmartCollectionSelection.serialize(sanitized).joinToString(SELECTION_DELIMITER)
        }
    }

    private fun readHomeSmartCollections(prefs: Preferences): List<SmartCollectionType> {
        val stored = prefs[HOME_SMART_COLLECTIONS_KEY]
        // Absent (existing users / never configured) -> sanitize() backfills to DEFAULT.
        val ids = stored?.split(SELECTION_DELIMITER)?.filter { it.isNotBlank() }.orEmpty()
        return HomeSmartCollectionSelection.sanitizeIds(ids)
    }

    private companion object {
        val HOME_VISIBLE_SECTIONS_KEY = stringSetPreferencesKey("home_visible_sections")
        val HOME_SMART_COLLECTIONS_KEY = stringPreferencesKey("home_smart_collections")
        const val SELECTION_DELIMITER = ","
    }
}
