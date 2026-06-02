package com.launchpoint.wavdrop.ui.screen.lyrics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.lyrics.LyricsRepository
import com.launchpoint.wavdrop.data.lyrics.LyricsResult
import com.launchpoint.wavdrop.data.repository.SongRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LyricsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val songRepository: SongRepository,
    private val lyricsRepository: LyricsRepository,
) : ViewModel() {

    private val songId: Long = checkNotNull(savedStateHandle["songId"])

    private val _lyricsState = MutableStateFlow<LyricsResult>(LyricsResult.Loading)
    val lyricsState: StateFlow<LyricsResult> = _lyricsState.asStateFlow()

    /** True while a user override exists for this track. */
    val hasCustomLyrics: StateFlow<Boolean> =
        lyricsRepository.observeOverride(songId)
            .map { it != null }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    init {
        viewModelScope.launch {
            // Re-observe whenever the song entity itself changes (e.g. MediaStore rescan
            // with a different URI). distinctUntilChanged prevents restarts for no-op emits.
            songRepository.observeSongById(songId)
                .filterNotNull()
                .distinctUntilChanged { old, new -> old.id == new.id && old.uri == new.uri }
                .collectLatest { song ->
                    // observeLyrics reacts to override changes automatically:
                    // saving custom lyrics pushes Available immediately; clearing pushes
                    // Loading then the file result — all without re-entering the screen.
                    lyricsRepository.observeLyrics(song).collect { result ->
                        _lyricsState.value = result
                    }
                }
        }
    }

    fun saveCustomLyrics(text: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            val song = songRepository.observeSongById(songId).filterNotNull().first()
            lyricsRepository.saveCustomLyrics(song, text)
            // No reloadLyrics() needed — observeLyrics auto-updates via the Room override Flow.
            onComplete()
        }
    }

    fun clearCustomLyrics(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            val song = songRepository.observeSongById(songId).filterNotNull().first()
            lyricsRepository.clearCustomLyrics(song)
            // No reloadLyrics() needed — observeLyrics auto-updates via the Room override Flow.
            onComplete()
        }
    }
}
