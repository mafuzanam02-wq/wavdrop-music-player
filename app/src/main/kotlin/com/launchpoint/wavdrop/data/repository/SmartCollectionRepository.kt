package com.launchpoint.wavdrop.data.repository

import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.smart.SmartCollectionBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartCollectionRepository @Inject constructor(
    private val songRepository: SongRepository,
    private val statsRepository: StatsRepository,
) {
    fun observeSmartCollections(): Flow<List<SmartCollection>> =
        combine(
            songRepository.songs,
            statsRepository.allTrackStatsEntities(),
            statsRepository.observeCompletionSummaries(),
        ) { songs, stats, completions ->
            SmartCollectionBuilder.build(songs, stats, completions)
        }

    fun observeSongsForCollection(type: SmartCollectionType): Flow<List<Song>> =
        combine(
            songRepository.songs,
            statsRepository.allTrackStatsEntities(),
            statsRepository.observeCompletionSummaries(),
        ) { songs, stats, completions ->
            SmartCollectionBuilder.songsFor(type, songs, stats, completions)
        }
}
