package com.launchpoint.wavdrop.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PlaybackControlsViewModel @Inject constructor(
    private val playerController: PlayerController,
) : ViewModel() {

    val nowPlayingState: StateFlow<NowPlayingState> = playerController.nowPlayingState

    fun playNext(song: Song)    = playerController.playNext(song)
    fun addToQueue(song: Song) = playerController.addToQueue(song)
    fun togglePlayPause()      = playerController.togglePlayPause()
    fun skipToNext()           = playerController.skipToNext()
    fun skipToPrevious()       = playerController.skipToPrevious()
    fun toggleShuffle()        = playerController.toggleShuffle()
    fun cycleRepeatMode()      = playerController.cycleRepeatMode()
}
