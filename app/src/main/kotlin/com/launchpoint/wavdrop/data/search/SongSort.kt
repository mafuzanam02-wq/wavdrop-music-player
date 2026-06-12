package com.launchpoint.wavdrop.data.search

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.text.MusicTextNormalizer

object SongSort {

    val byTitle: Comparator<Song> = compareBy<Song>(
        { MusicTextNormalizer.normalizeStrict(it.title) },
        { it.title.trim() },
        { it.id },
    )
}
