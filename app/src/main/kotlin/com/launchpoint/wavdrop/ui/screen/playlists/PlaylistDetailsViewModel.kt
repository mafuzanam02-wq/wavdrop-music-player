package com.launchpoint.wavdrop.ui.screen.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playlists.PlaylistArtworkBuilder
import com.launchpoint.wavdrop.data.repository.PlaylistOperationResult
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.data.search.LibrarySearch
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistSongItem(
    val playlistId: Long,
    val songId: Long,
    val position: Int,
    val song: Song,
)

data class PlaylistDetailsUiState(
    val isLoading: Boolean,
    val playlist: PlaylistSummary?,
    val entries: List<PlaylistSongItem>,
    val visibleEntries: List<PlaylistSongItem>,
    val searchQuery: String,
    val artworkUris: List<String>,
    val favoriteSongIds: Set<Long>,
    val currentSongId: Long?,
) {
    val songs: List<Song> get() = entries.map { it.song }
    val isSearchActive: Boolean get() = searchQuery.isNotBlank()
}

internal fun playlistQueueSongs(entries: List<PlaylistSongItem>): List<Song> =
    entries.map { it.song }

@HiltViewModel
class PlaylistDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val songRepository: SongRepository,
    private val statsRepository: StatsRepository,
    private val playerController: PlayerController,
) : ViewModel() {

    private val playlistId: Long = checkNotNull(savedStateHandle["playlistId"])
    private val searchQuery = MutableStateFlow("")

    private val playlistSummary = playlistRepository.observePlaylists()
        .map { list -> list.firstOrNull { it.id == playlistId } }

    private val orderedEntries = combine(
        playlistRepository.observePlaylistSongs(playlistId),
        songRepository.songs,
    ) { playlistSongs, allSongs ->
        val songMap = allSongs.associateBy { it.id }
        playlistSongs.mapNotNull { ps ->
            songMap[ps.songId]?.let { song ->
                PlaylistSongItem(
                    playlistId = ps.playlistId,
                    songId     = ps.songId,
                    position   = ps.position,
                    song       = song,
                )
            }
        }
    }

    val uiState: StateFlow<PlaylistDetailsUiState> = combine(
        playlistSummary,
        orderedEntries,
        searchQuery,
        statsRepository.favoriteSongIds(),
        playerController.nowPlayingState,
    ) { summary, entries, query, favorites, nowPlaying ->
        val visibleSongs = LibrarySearch.filterSongs(entries.map { it.song }, query)
        val visibleSongIds = visibleSongs.mapTo(mutableSetOf()) { it.id }
        PlaylistDetailsUiState(
            isLoading     = false,
            playlist       = summary,
            entries        = entries,
            visibleEntries = if (query.isBlank()) {
                entries
            } else {
                entries.filter { it.songId in visibleSongIds }
            },
            searchQuery    = query,
            artworkUris    = PlaylistArtworkBuilder.buildArtworkUris(entries.map { it.song }),
            favoriteSongIds = favorites,
            currentSongId  = nowPlaying.song?.id,
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlaylistDetailsUiState(
            isLoading     = true,
            playlist       = null,
            entries        = emptyList(),
            visibleEntries = emptyList(),
            searchQuery    = "",
            artworkUris    = emptyList(),
            favoriteSongIds = emptySet(),
            currentSongId  = null,
        ),
    )

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun clearSearch() {
        searchQuery.value = ""
    }

    fun playNext(song: Song)   = playerController.playNext(song)
    fun addToQueue(song: Song) = playerController.addToQueue(song)

    fun playAllNext() {
        val songs = playlistQueueSongs(uiState.value.visibleEntries)
        if (songs.isEmpty()) return
        playerController.playAllNext(songs)
    }

    fun addAllToQueue() {
        val songs = playlistQueueSongs(uiState.value.visibleEntries)
        if (songs.isEmpty()) return
        playerController.addAllToQueue(songs)
    }

    fun playEntry(entry: PlaylistSongItem, shuffle: Boolean = false) {
        val songs = uiState.value.visibleEntries.map { it.song }
        if (songs.isEmpty()) return
        if (shuffle) {
            playerController.playFromQueueShuffled(queue = songs)
        } else {
            playerController.playFromQueue(queue = songs, startSong = entry.song)
        }
    }

    fun playAll() {
        val songs = uiState.value.visibleEntries.map { it.song }
        val first = songs.firstOrNull() ?: return
        playerController.playFromQueue(queue = songs, startSong = first)
    }

    fun shufflePlay() {
        val songs = uiState.value.visibleEntries.map { it.song }
        if (songs.isEmpty()) return
        playerController.playFromQueueShuffled(queue = songs)
    }

    fun toggleFavorite(songId: Long) {
        val song = uiState.value.songs.firstOrNull { it.id == songId } ?: return
        viewModelScope.launch { statsRepository.toggleFavorite(songId, song.uri) }
    }

    fun removeSong(songId: Long) {
        viewModelScope.launch {
            playlistRepository.removeSongFromPlaylist(songId = songId, playlistId = playlistId)
        }
    }

    fun removeEntry(position: Int) {
        viewModelScope.launch {
            playlistRepository.removePlaylistEntry(playlistId = playlistId, position = position)
        }
    }

    fun moveEntryUp(position: Int) {
        val entries = uiState.value.entries.sortedBy { it.position }
        val index = entries.indexOfFirst { it.position == position }
        if (index <= 0) return
        val targetPosition = entries[index - 1].position
        viewModelScope.launch {
            playlistRepository.movePlaylistSong(
                playlistId   = playlistId,
                fromPosition = position,
                toPosition   = targetPosition,
            )
        }
    }

    fun moveEntryDown(position: Int) {
        val entries = uiState.value.entries.sortedBy { it.position }
        val index = entries.indexOfFirst { it.position == position }
        if (index < 0 || index >= entries.lastIndex) return
        val targetPosition = entries[index + 1].position
        viewModelScope.launch {
            playlistRepository.movePlaylistSong(
                playlistId   = playlistId,
                fromPosition = position,
                toPosition   = targetPosition,
            )
        }
    }

    fun moveToPosition(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        viewModelScope.launch {
            playlistRepository.movePlaylistSong(
                playlistId   = playlistId,
                fromPosition = fromPosition,
                toPosition   = toPosition,
            )
        }
    }

    fun renamePlaylist(name: String, onResult: (PlaylistOperationResult) -> Unit = {}) {
        viewModelScope.launch { onResult(playlistRepository.renamePlaylist(playlistId, name)) }
    }

    fun deletePlaylist(onDeleted: () -> Unit) {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(playlistId)
            onDeleted()
        }
    }
}
