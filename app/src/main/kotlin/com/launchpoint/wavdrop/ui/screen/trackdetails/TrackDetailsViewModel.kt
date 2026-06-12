package com.launchpoint.wavdrop.ui.screen.trackdetails

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.local.dao.ImportBaselineDao
import com.launchpoint.wavdrop.data.local.dao.LyricsOverrideDao
import com.launchpoint.wavdrop.data.model.PlaylistSong
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.TrackStats
import com.launchpoint.wavdrop.data.repository.AddToPlaylistResult
import com.launchpoint.wavdrop.data.repository.PlaylistRepository
import com.launchpoint.wavdrop.data.repository.PlaylistOperationResult
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.repository.StatsRepository
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TrackDetailsUiState {
    data object Loading  : TrackDetailsUiState
    data object NotFound : TrackDetailsUiState
    data class  Ready(
        val song: Song,
        val stats: TrackStats?,
        val isFavorite: Boolean,
    ) : TrackDetailsUiState
}

@HiltViewModel
class TrackDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val songRepository: SongRepository,
    private val statsRepository: StatsRepository,
    private val playlistRepository: PlaylistRepository,
    private val playerController: PlayerController,
    private val lyricsOverrideDao: LyricsOverrideDao,
    private val importBaselineDao: ImportBaselineDao,
) : ViewModel() {

    private val songId: Long = checkNotNull(savedStateHandle["songId"])

    val uiState: StateFlow<TrackDetailsUiState> = combine(
        songRepository.observeSongById(songId),
        statsRepository.observeStats(songId),
        statsRepository.favoriteSongIds(),
    ) { song, stats, favorites ->
        if (song == null) TrackDetailsUiState.NotFound
        else TrackDetailsUiState.Ready(
            song       = song,
            stats      = stats,
            isFavorite = songId in favorites,
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackDetailsUiState.Loading,
    )

    val playlists: StateFlow<List<PlaylistSummary>> = playlistRepository.observePlaylists()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val allPlaylistSongs: StateFlow<List<PlaylistSong>> = playlistRepository.observeAllPlaylistSongs()
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun toggleFavorite() {
        val state = uiState.value as? TrackDetailsUiState.Ready ?: return
        viewModelScope.launch {
            statsRepository.toggleFavorite(state.song.id, state.song.uri)
        }
    }

    fun addToPlaylist(playlistId: Long, onResult: (AddToPlaylistResult) -> Unit = {}) {
        val song = (uiState.value as? TrackDetailsUiState.Ready)?.song ?: return
        viewModelScope.launch {
            val result = playlistRepository.addSongToPlaylist(song.id, playlistId)
            onResult(result)
        }
    }

    fun createPlaylistAndAdd(name: String, onResult: (PlaylistOperationResult) -> Unit = {}) {
        val song = (uiState.value as? TrackDetailsUiState.Ready)?.song ?: return
        viewModelScope.launch {
            val result = playlistRepository.createPlaylist(name)
            if (result is PlaylistOperationResult.Success) {
                playlistRepository.addSongToPlaylist(song.id, result.playlistId)
            }
            onResult(result)
        }
    }

    fun deleteFromDevice(
        song: Song,
        onDeleteRequested: (IntentSender) -> Unit,
        onFailed: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { onFailed(); return }
        runCatching {
            val pendingIntent = MediaStore.createDeleteRequest(
                context.contentResolver,
                listOf(Uri.parse(song.uri)),
            )
            onDeleteRequested(pendingIntent.intentSender)
        }.onFailure { onFailed() }
    }

    fun onDeleteApproved(song: Song, onComplete: () -> Unit) {
        viewModelScope.launch {
            playerController.handleSongDeleted(song.id)
            runCatching { songRepository.pruneSong(song.id) }
            runCatching { playlistRepository.removeSongFromAllPlaylists(song.id) }
            runCatching { lyricsOverrideDao.deleteForSong(song.id, song.uri) }
            // Import baselines for this song are no longer useful: the file has been
            // permanently deleted from the device, so it cannot be re-imported. Clearing
            // them avoids stale deduplication if the user ever imports a similarly-named track.
            // track_stats and track_listen_events are intentionally kept for historical reports.
            runCatching { importBaselineDao.deleteBySongId(song.id) }
            onComplete()
        }
    }
}
