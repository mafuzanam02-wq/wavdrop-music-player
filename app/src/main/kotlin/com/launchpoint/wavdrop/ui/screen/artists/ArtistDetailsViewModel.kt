package com.launchpoint.wavdrop.ui.screen.artists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.model.ArtistInsightsSummary
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.stats.ArtistInsightsBuilder
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistDetailsUiState(
    val isLoading: Boolean,
    val artistName: String,
    val albumCount: Int,
    val songCount: Int,
    val totalDurationMs: Long,
    val artworkUri: String?,
    val insights: ArtistInsightsSummary,
    val songs: List<Song>,
    val favoriteSongIds: Set<Long>,
    val currentSongId: Long?,
)

@HiltViewModel
class ArtistDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val songRepository: SongRepository,
    private val statsRepository: StatsRepository,
    private val playerController: PlayerController,
) : ViewModel() {

    private val artistKey: String = checkNotNull(savedStateHandle["artistKey"])

    private val artistSongs = songRepository.songs
        .map { songs -> songs.filter { ArtistGrouper.artistKey(it) == artistKey } }

    val uiState: StateFlow<ArtistDetailsUiState> = combine(
        artistSongs,
        statsRepository.allTrackStatsEntities(),
        statsRepository.favoriteSongIds(),
        playerController.nowPlayingState,
    ) { songs, stats, favorites, nowPlaying ->
        val sorted = songs.sortedWith(
            compareBy(
                { it.album.trim().ifBlank { "￿" } },
                { it.trackNumber.takeIf { n -> n > 0 } ?: Int.MAX_VALUE },
                { it.title },
            )
        )
        val albumCount = songs.map { it.album.trim().ifBlank { "Unknown Album" } }.toSet().size
        ArtistDetailsUiState(
            isLoading       = false,
            artistName      = artistKey,
            albumCount      = albumCount,
            songCount       = songs.size,
            totalDurationMs = songs.sumOf { it.duration },
            artworkUri      = ArtistGrouper.representativeArtworkUri(songs),
            insights        = ArtistInsightsBuilder.build(songs = songs, stats = stats),
            songs           = sorted,
            favoriteSongIds = favorites,
            currentSongId   = nowPlaying.song?.id,
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = ArtistDetailsUiState(
            isLoading       = true,
            artistName      = artistKey,
            albumCount      = 0,
            songCount       = 0,
            totalDurationMs = 0L,
            artworkUri      = null,
            insights        = ArtistInsightsBuilder.build(songs = emptyList(), stats = emptyList()),
            songs           = emptyList(),
            favoriteSongIds = emptySet(),
            currentSongId   = null,
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
}
