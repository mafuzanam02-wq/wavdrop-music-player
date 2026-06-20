package com.launchpoint.wavdrop.data.smart

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song

object SmartCollectionBuilder {

    private const val LONG_TRACK_THRESHOLD_MS  = 7 * 60 * 1_000L   // 7 minutes
    private const val SHORT_TRACK_THRESHOLD_MS = 90 * 1_000L        // 90 seconds

    private const val FORGOTTEN_GEMS_PLAY_COUNT_MIN = 5
    private const val FORGOTTEN_GEMS_QUIET_DAYS     = 60
    private const val FORGOTTEN_GEMS_CAP            = 50

    fun build(songs: List<Song>, stats: List<TrackStatsEntity>): List<SmartCollection> {
        val songIds   = songs.mapTo(HashSet()) { it.id }
        val statsById = stats.filter { it.songId in songIds }.associateBy { it.songId }
        return SmartCollectionType.values().mapNotNull { type ->
            val filtered = songsForInternal(type, songs, statsById)
            if (filtered.isEmpty()) null
            else SmartCollection(
                id          = type.name,
                title       = titleFor(type),
                description = descriptionFor(type),
                type        = type,
                songCount   = filtered.size,
            )
        }
    }

    fun songsFor(
        type: SmartCollectionType,
        songs: List<Song>,
        stats: List<TrackStatsEntity>,
    ): List<Song> {
        val songIds   = songs.mapTo(HashSet()) { it.id }
        val statsById = stats.filter { it.songId in songIds }.associateBy { it.songId }
        return songsForInternal(type, songs, statsById)
    }

    private fun songsForInternal(
        type: SmartCollectionType,
        songs: List<Song>,
        statsById: Map<Long, TrackStatsEntity>,
    ): List<Song> = when (type) {
        SmartCollectionType.FAVORITES ->
            songs
                .filter { statsById[it.id]?.isFavorite == true }
                .sortedWith(compareBy({ it.title }, { it.id }))

        SmartCollectionType.MOST_PLAYED ->
            songs
                .filter { (statsById[it.id]?.playCount ?: 0) > 0 }
                .sortedWith(
                    compareByDescending<Song> { statsById[it.id]?.playCount ?: 0 }
                        .thenBy { it.id },
                )

        SmartCollectionType.RECENTLY_PLAYED ->
            songs
                .filter { (statsById[it.id]?.lastPlayedAt ?: 0L) > 0L }
                .sortedWith(
                    compareByDescending<Song> { statsById[it.id]?.lastPlayedAt ?: 0L }
                        .thenBy { it.id },
                )

        SmartCollectionType.FORGOTTEN_GEMS -> {
            val quietThresholdMs = System.currentTimeMillis() - FORGOTTEN_GEMS_QUIET_DAYS * 24 * 60 * 60 * 1_000L
            songs
                .filter { song ->
                    val stat = statsById[song.id]
                    stat != null &&
                        stat.playCount >= FORGOTTEN_GEMS_PLAY_COUNT_MIN &&
                        stat.lastPlayedAt > 0L &&
                        stat.lastPlayedAt < quietThresholdMs
                }
                .sortedWith(
                    compareByDescending<Song> { statsById[it.id]?.playCount ?: 0 }
                        .thenBy { statsById[it.id]?.lastPlayedAt ?: 0L }
                        .thenBy { it.id },
                )
                .take(FORGOTTEN_GEMS_CAP)
        }

        SmartCollectionType.NEVER_PLAYED ->
            songs
                .filter { (statsById[it.id]?.playCount ?: 0) == 0 }
                .sortedWith(compareBy({ it.title }, { it.id }))

        SmartCollectionType.RECENTLY_ADDED ->
            songs
                .filter { it.dateAdded > 0L }
                .sortedWith(
                    compareByDescending<Song> { it.dateAdded }
                        .thenBy { it.id },
                )

        SmartCollectionType.MOST_SKIPPED ->
            songs
                .filter { (statsById[it.id]?.skipCount ?: 0) > 0 }
                .sortedWith(
                    compareByDescending<Song> { statsById[it.id]?.skipCount ?: 0 }
                        .thenBy { it.id },
                )

        SmartCollectionType.LONG_TRACKS ->
            songs
                .filter { it.duration >= LONG_TRACK_THRESHOLD_MS }
                .sortedWith(
                    compareByDescending<Song> { it.duration }
                        .thenBy { it.id },
                )

        SmartCollectionType.SHORT_TRACKS ->
            songs
                .filter { it.duration <= SHORT_TRACK_THRESHOLD_MS }
                .sortedWith(
                    compareBy<Song> { it.duration }
                        .thenBy { it.id },
                )
    }

    fun titleFor(type: SmartCollectionType): String = when (type) {
        SmartCollectionType.FAVORITES       -> "Favorites"
        SmartCollectionType.MOST_PLAYED     -> "Most Played"
        SmartCollectionType.RECENTLY_PLAYED -> "Recently Played"
        SmartCollectionType.FORGOTTEN_GEMS  -> "Forgotten Gems"
        SmartCollectionType.NEVER_PLAYED    -> "Never Played"
        SmartCollectionType.RECENTLY_ADDED  -> "Recently Added"
        SmartCollectionType.MOST_SKIPPED    -> "Most Skipped"
        SmartCollectionType.LONG_TRACKS     -> "Long Tracks"
        SmartCollectionType.SHORT_TRACKS    -> "Short Tracks"
    }

    fun descriptionFor(type: SmartCollectionType): String = when (type) {
        SmartCollectionType.FAVORITES       -> "Songs you've marked as favorites"
        SmartCollectionType.MOST_PLAYED     -> "Your most frequently played tracks"
        SmartCollectionType.RECENTLY_PLAYED -> "Tracks you've played recently"
        SmartCollectionType.FORGOTTEN_GEMS  -> "Songs you used to play often, but haven't heard in a while"
        SmartCollectionType.NEVER_PLAYED    -> "Tracks you haven't played yet"
        SmartCollectionType.RECENTLY_ADDED  -> "Tracks recently added to your library"
        SmartCollectionType.MOST_SKIPPED    -> "Tracks you frequently skip"
        SmartCollectionType.LONG_TRACKS     -> "Tracks longer than 7 minutes"
        SmartCollectionType.SHORT_TRACKS    -> "Tracks shorter than 90 seconds"
    }
}
