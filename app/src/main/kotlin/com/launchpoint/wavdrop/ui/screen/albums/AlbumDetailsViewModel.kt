package com.launchpoint.wavdrop.ui.screen.albums

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.grouping.AlbumGrouper
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailsUiState(
    val albumName: String,
    val artist: String,
    val albumId: Long?,
    val songs: List<Song>,
    val favoriteSongIds: Set<Long>,
    val currentSongId: Long?,
    val totalDurationMs: Long,
)

@HiltViewModel
class AlbumDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val songRepository: SongRepository,
    private val statsRepository: StatsRepository,
    private val playerController: PlayerController,
) : ViewModel() {

    private val albumKey: String = checkNotNull(savedStateHandle["albumKey"])

    private val albumSongs = songRepository.songs
        .map { songs -> songs.filter { AlbumGrouper.albumKey(it) == albumKey } }

    val uiState: StateFlow<AlbumDetailsUiState> = combine(
        albumSongs,
        statsRepository.favoriteSongIds(),
        playerController.nowPlayingState,
    ) { songs, favorites, nowPlaying ->
        val artist = songs
            .groupBy { it.artist.trim() }
            .maxByOrNull { it.value.size }
            ?.key
            ?.ifBlank { "Unknown Artist" }
            ?: "Unknown Artist"
        val sorted = songs.sortedWith(
            compareBy({ it.trackNumber.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }, { it.title })
        )
        AlbumDetailsUiState(
            albumName       = albumKey,
            artist          = artist,
            albumId         = songs.firstOrNull { it.albumId > 0L }?.albumId,
            songs           = sorted,
            favoriteSongIds = favorites,
            currentSongId   = nowPlaying.song?.id,
            totalDurationMs = songs.sumOf { it.duration },
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = AlbumDetailsUiState(
            albumName       = albumKey,
            artist          = "",
            albumId         = null,
            songs           = emptyList(),
            favoriteSongIds = emptySet(),
            currentSongId   = null,
            totalDurationMs = 0L,
        ),
    )

    fun playSong(song: Song) {
        val queue = uiState.value.songs
        playerController.playFromQueue(queue = queue, startSong = song)
    }

    fun toggleFavorite(songId: Long) {
        val song = uiState.value.songs.firstOrNull { it.id == songId } ?: return
        viewModelScope.launch { statsRepository.toggleFavorite(songId, song.uri) }
    }
}
