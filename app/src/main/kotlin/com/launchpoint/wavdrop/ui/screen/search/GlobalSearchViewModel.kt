package com.launchpoint.wavdrop.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.SmartCollectionRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.settings.SearchTapBehavior
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val songRepository: SongRepository,
    private val playerController: PlayerController,
    private val statsRepository: StatsRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val smartCollectionRepository: SmartCollectionRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    val allSongs: StateFlow<List<Song>> = songRepository.songs
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val smartCollections: StateFlow<List<SmartCollection>> =
        smartCollectionRepository.observeSmartCollections()
            .stateIn(
                scope        = viewModelScope,
                started      = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    val nowPlayingState: StateFlow<NowPlayingState> = playerController.nowPlayingState

    val favoriteSongIds: StateFlow<Set<Long>> = statsRepository.favoriteSongIds()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptySet(),
        )

    fun playSearchResult(song: Song) {
        viewModelScope.launch {
            when (appSettingsRepository.searchTapBehavior.first()) {
                SearchTapBehavior.REPLACE_QUEUE -> {
                    val queue = allSongs.value.ifEmpty { listOf(song) }
                    playerController.playFromQueue(queue = queue, startSong = song)
                }
                SearchTapBehavior.PRESERVE_QUEUE ->
                    playerController.playSearchResultPreservingQueue(song)
            }
        }
    }

    fun playNext(song: Song) = playerController.playNext(song)

    fun addToQueue(song: Song) = playerController.addToQueue(song)

    fun toggleFavorite(songId: Long) {
        val song = allSongs.value.firstOrNull { it.id == songId } ?: return
        viewModelScope.launch { statsRepository.toggleFavorite(songId, song.uri) }
    }
}
