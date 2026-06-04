package com.launchpoint.wavdrop.ui.screen.smart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.ui.components.LoadingStateContent
import com.launchpoint.wavdrop.ui.components.LocalCompactMode
import com.launchpoint.wavdrop.ui.components.MiniPlayer
import com.launchpoint.wavdrop.ui.viewmodel.PlaybackControlsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartCollectionsScreen(
    onNavigateBack: () -> Unit,
    onCollectionClick: (SmartCollectionType) -> Unit,
    onNowPlayingClick: () -> Unit = {},
    viewModel: SmartCollectionsViewModel = hiltViewModel(),
    playbackVm: PlaybackControlsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nowPlaying  by playbackVm.nowPlayingState.collectAsStateWithLifecycle()
    val collections = state.collections

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart Collections") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
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
        if (state.isLoading) {
            LoadingStateContent(
                message = "Loading smart collections...",
                modifier = Modifier.padding(innerPadding),
            )
        } else if (collections.isEmpty()) {
            EmptyCollectionsContent(Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier       = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(collections, key = { it.id }) { collection ->
                    SmartCollectionRow(
                        collection = collection,
                        onClick    = { onCollectionClick(collection.type) },
                    )
                    HorizontalDivider(
                        color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartCollectionRow(
    collection: SmartCollection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 10.dp else 14.dp
    val iconSize = if (compact) 26.dp else 28.dp
    Row(
        modifier          = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = iconFor(collection.type),
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.primary,
            modifier           = Modifier
                .padding(end = 16.dp)
                .size(iconSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = collection.title,
                style    = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text  = "${collection.songCount} songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Text(
                    text  = collection.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@Composable
private fun EmptyCollectionsContent(modifier: Modifier = Modifier) {
    Box(
        modifier         = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text      = "No smart collections yet.",
                style     = MaterialTheme.typography.titleMedium,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            Text(
                text      = "Play and favorite songs to build automatic collections.",
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun iconFor(type: SmartCollectionType): ImageVector = when (type) {
    SmartCollectionType.FAVORITES       -> Icons.Default.Favorite
    SmartCollectionType.MOST_PLAYED     -> Icons.Default.Star
    SmartCollectionType.RECENTLY_PLAYED -> Icons.Default.Schedule
    SmartCollectionType.NEVER_PLAYED    -> Icons.Default.MusicNote
    SmartCollectionType.RECENTLY_ADDED  -> Icons.Default.AutoAwesome
    SmartCollectionType.MOST_SKIPPED    -> Icons.Default.SkipNext
    SmartCollectionType.LONG_TRACKS     -> Icons.Default.Timer
    SmartCollectionType.SHORT_TRACKS    -> Icons.Default.Timer
}
