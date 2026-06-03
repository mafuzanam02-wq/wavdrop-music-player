package com.launchpoint.wavdrop.data.search

import com.launchpoint.wavdrop.data.model.Song

object SongSort {

    val byTitle: Comparator<Song> = compareBy<Song>(
        { it.title.trim().lowercase() },
        { it.title.trim() },
        { it.id },
    )
}
