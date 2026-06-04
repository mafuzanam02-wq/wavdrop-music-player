package com.launchpoint.wavdrop.ui.screen.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.grouping.AlbumGrouper
import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.repository.SongRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibrarySummaryUiState(
    val isLoading: Boolean = false,
    val totalSongs: Int = 0,
    val totalAlbums: Int = 0,
    val totalArtists: Int = 0,
    val totalDurationMs: Long = 0L,
) {
    val isEmpty: Boolean get() = totalSongs == 0
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val songRepository: SongRepository,
) : ViewModel() {

    val summary: StateFlow<LibrarySummaryUiState> = songRepository.songs
        .map { songs ->
            LibrarySummaryUiState(
                totalSongs      = songs.size,
                totalAlbums     = songs.mapTo(HashSet()) { AlbumGrouper.albumKey(it) }.size,
                totalArtists    = songs.mapTo(HashSet()) { ArtistGrouper.artistKey(it) }.size,
                totalDurationMs = songs.sumOf { it.duration },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibrarySummaryUiState(isLoading = true),
        )

    private var hasSynced = false

    fun syncIfNeeded() {
        if (hasSynced) return
        hasSynced = true
        viewModelScope.launch { songRepository.sync() }
    }
}
