package com.launchpoint.wavdrop.data.search

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.settings.SongSortMode
import com.launchpoint.wavdrop.data.text.MusicTextNormalizer

object SongSort {

    val byTitle: Comparator<Song> = compareBy<Song>(
        { MusicTextNormalizer.normalizeStrict(it.title) },
        { it.title.trim() },
        { it.id },
    )

    fun sortSongs(
        songs: List<Song>,
        mode: SongSortMode = SongSortMode.DEFAULT,
        allTimePlayCounts: Map<Long, Int> = emptyMap(),
        thisMonthPlayCounts: Map<Long, Int> = emptyMap(),
    ): List<Song> = when (mode) {
        SongSortMode.TITLE_ASC -> songs.sortedWith(byTitle)
        SongSortMode.RECENTLY_ADDED -> songs.sortedWith(
            compareByDescending<Song> { it.dateAdded }
                .then(byTitle),
        )
        SongSortMode.MOST_PLAYED_THIS_MONTH -> songs.sortedWith(
            compareByDescending<Song> { thisMonthPlayCounts[it.id] ?: 0 }
                .then(byTitle),
        )
        SongSortMode.MOST_PLAYED_ALL_TIME -> songs.sortedWith(
            compareByDescending<Song> { allTimePlayCounts[it.id] ?: 0 }
                .then(byTitle),
        )
    }
}
