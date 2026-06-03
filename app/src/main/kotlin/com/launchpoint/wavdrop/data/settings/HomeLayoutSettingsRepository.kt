package com.launchpoint.wavdrop.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
            if (saved == null) {
                HomeLayoutSettings()
            } else {
                val parsed = saved
                    .mapNotNull { name -> runCatching { HomeSectionId.valueOf(name) }.getOrNull() }
                    .toSet()
                HomeLayoutSettings(
                    visibleSections = parsed + HomeLayoutSettingsRules.ALWAYS_VISIBLE_SECTIONS,
                )
            }
        }

    suspend fun setVisibleSections(sections: Set<HomeSectionId>) {
        dataStore.edit { prefs ->
            prefs[HOME_VISIBLE_SECTIONS_KEY] =
                (sections + HomeLayoutSettingsRules.ALWAYS_VISIBLE_SECTIONS)
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

    private companion object {
        val HOME_VISIBLE_SECTIONS_KEY = stringSetPreferencesKey("home_visible_sections")
    }
}
