package com.launchpoint.wavdrop.ui.screen.folders

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.ui.components.MiniPlayer
import com.launchpoint.wavdrop.ui.components.SongRow
import com.launchpoint.wavdrop.ui.viewmodel.PlaybackControlsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailsScreen(
    onNavigateBack: () -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onNowPlayingClick: () -> Unit = {},
    viewModel: FolderDetailsViewModel = hiltViewModel(),
    playbackVm: PlaybackControlsViewModel = hiltViewModel(),
) {
    val state     by viewModel.uiState.collectAsStateWithLifecycle()
    val nowPlaying by playbackVm.nowPlayingState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${state.songs.size} songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            MiniPlayer(
                nowPlaying        = nowPlaying,
                onOpenNowPlaying  = onNowPlayingClick,
                onTogglePlayPause = playbackVm::togglePlayPause,
                onPrevious        = playbackVm::skipToPrevious,
                onNext            = playbackVm::skipToNext,
                onToggleShuffle   = playbackVm::toggleShuffle,
                onCycleRepeatMode = playbackVm::cycleRepeatMode,
            )
        },
    ) { innerPadding ->
        if (state.songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No songs found in this folder.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.songs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        isCurrent = song.id == state.currentSongId,
                        isFavorite = song.id in state.favoriteSongIds,
                        onClick = { viewModel.playSong(song) },
                        onToggleFavorite = { viewModel.toggleFavorite(song.id) },
                        onOpenDetails = { onTrackDetailsClick(song.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }
}
