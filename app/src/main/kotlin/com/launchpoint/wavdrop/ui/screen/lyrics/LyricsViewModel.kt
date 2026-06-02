package com.launchpoint.wavdrop.ui.screen.lyrics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.lyrics.LyricsRepository
import com.launchpoint.wavdrop.data.lyrics.LyricsResult
import com.launchpoint.wavdrop.data.repository.SongRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

    init {
        viewModelScope.launch {
            val song = songRepository.observeSongById(songId).filterNotNull().first()
            _lyricsState.value = lyricsRepository.getLyrics(song)
        }
    }
}
