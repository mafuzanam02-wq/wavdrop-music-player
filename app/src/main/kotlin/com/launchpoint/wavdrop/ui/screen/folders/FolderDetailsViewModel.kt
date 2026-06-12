package com.launchpoint.wavdrop.ui.screen.folders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.library.FolderGrouper
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FolderDetailsUiState(
    val isLoading: Boolean,
    val folderKey: String,
    val displayName: String,
    val songs: List<Song>,
    val favoriteSongIds: Set<Long>,
    val currentSongId: Long?,
    val totalDurationMs: Long,
)

@HiltViewModel
class FolderDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val songRepository: SongRepository,
    private val statsRepository: StatsRepository,
    private val playerController: PlayerController,
) : ViewModel() {

    private val folderKey: String = checkNotNull(savedStateHandle["folderKey"])

    private val folderSongs = songRepository.songs
        .map { songs -> FolderGrouper.songsForFolder(songs, folderKey) }

    val uiState: StateFlow<FolderDetailsUiState> = combine(
        folderSongs,
        statsRepository.favoriteSongIds(),
        playerController.nowPlayingState,
    ) { songs, favorites, nowPlaying ->
        FolderDetailsUiState(
            isLoading = false,
            folderKey = folderKey,
            displayName = displayNameForFolder(songs),
            songs = songs,
            favoriteSongIds = favorites,
            currentSongId = nowPlaying.song?.id,
            totalDurationMs = songs.sumOf { it.duration },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FolderDetailsUiState(
            isLoading = true,
            folderKey = folderKey,
            displayName = folderKey.substringAfterLast('/').substringAfterLast('\\'),
            songs = emptyList(),
            favoriteSongIds = emptySet(),
            currentSongId = null,
            totalDurationMs = 0L,
        ),
    )

    fun playSong(song: Song) {
        playerController.playFromQueue(queue = uiState.value.songs, startSong = song)
    }

    fun playNext(song: Song)   = playerController.playNext(song)
    fun addToQueue(song: Song) = playerController.addToQueue(song)

    fun playAll() {
        val songs = uiState.value.songs
        val first = songs.firstOrNull() ?: return
        playerController.playFromQueue(queue = songs, startSong = first)
    }
    fun playAllNext()   = playerController.playAllNext(uiState.value.songs)
    fun addAllToQueue() = playerController.addAllToQueue(uiState.value.songs)
    fun shufflePlay()   = playerController.playFromQueueShuffled(uiState.value.songs)

    fun toggleFavorite(songId: Long) {
        val song = uiState.value.songs.firstOrNull { it.id == songId } ?: return
        viewModelScope.launch { statsRepository.toggleFavorite(songId, song.uri) }
    }

    private fun displayNameForFolder(songs: List<Song>): String {
        if (folderKey == FolderGrouper.UNKNOWN_FOLDER) return FolderGrouper.UNKNOWN_FOLDER
        return songs
            .asSequence()
            .mapNotNull { it.folderName?.trim()?.ifBlank { null } }
            .firstOrNull()
            ?: folderKey.substringAfterLast('/').substringAfterLast('\\').ifBlank { FolderGrouper.UNKNOWN_FOLDER }
    }
}
