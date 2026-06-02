package com.launchpoint.wavdrop.data.smart

import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song

object SmartCollectionBuilder {

    private const val LONG_TRACK_THRESHOLD_MS  = 7 * 60 * 1_000L   // 7 minutes
    private const val SHORT_TRACK_THRESHOLD_MS = 90 * 1_000L        // 90 seconds

    fun build(songs: List<Song>, stats: List<TrackStatsEntity>): List<SmartCollection> =
        SmartCollectionType.values().mapNotNull { type ->
            val filtered = songsFor(type, songs, stats)
            if (filtered.isEmpty()) null
            else SmartCollection(
                id          = type.name,
                title       = titleFor(type),
                description = descriptionFor(type),
                type        = type,
                songCount   = filtered.size,
            )
        }

    fun songsFor(
        type: SmartCollectionType,
        songs: List<Song>,
        stats: List<TrackStatsEntity>,
    ): List<Song> {
        // only consider stats whose songId is still present in the library
        val songIds   = songs.map { it.id }.toHashSet()
        val statsById = stats.filter { it.songId in songIds }.associateBy { it.songId }

        return when (type) {
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
    }

    fun titleFor(type: SmartCollectionType): String = when (type) {
        SmartCollectionType.FAVORITES       -> "Favorites"
        SmartCollectionType.MOST_PLAYED     -> "Most Played"
        SmartCollectionType.RECENTLY_PLAYED -> "Recently Played"
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
        SmartCollectionType.NEVER_PLAYED    -> "Tracks you haven't played yet"
        SmartCollectionType.RECENTLY_ADDED  -> "Tracks recently added to your library"
        SmartCollectionType.MOST_SKIPPED    -> "Tracks you frequently skip"
        SmartCollectionType.LONG_TRACKS     -> "Tracks longer than 7 minutes"
        SmartCollectionType.SHORT_TRACKS    -> "Tracks shorter than 90 seconds"
    }
}
