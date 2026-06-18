package com.launchpoint.wavdrop.ui.screen.folders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.model.FolderSummary
import com.launchpoint.wavdrop.data.search.AlphabetIndex
import com.launchpoint.wavdrop.ui.components.AlphabetSideIndex
import com.launchpoint.wavdrop.ui.components.LocalCompactMode
import com.launchpoint.wavdrop.ui.components.MiniPlayer
import com.launchpoint.wavdrop.ui.components.SearchTopAppBar
import com.launchpoint.wavdrop.ui.viewmodel.PlaybackControlsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    onNavigateBack: () -> Unit,
    onFolderClick: (String) -> Unit,
    onNowPlayingClick: () -> Unit = {},
    viewModel: FoldersViewModel = hiltViewModel(),
    playbackVm: PlaybackControlsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nowPlaying by playbackVm.nowPlayingState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    var isSearchActive by remember { mutableStateOf(false) }
    var isSortMenuExpanded by remember { mutableStateOf(false) }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        viewModel.setSearchQuery("")
    }

    Scaffold(
        topBar = {
            if (isSearchActive) {
                SearchTopAppBar(
                    query = searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    onClose = {
                        isSearchActive = false
                        viewModel.setSearchQuery("")
                    },
                    placeholder = "Search folders...",
                )
            } else {
                TopAppBar(
                    title = { Text("Folders") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Box {
                            IconButton(onClick = { isSortMenuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Default.Sort,
                                    contentDescription = "Sort",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            DropdownMenu(
                                expanded = isSortMenuExpanded,
                                onDismissRequest = { isSortMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Name A–Z") },
                                    onClick = { viewModel.setSortMode(FolderSortMode.NAME); isSortMenuExpanded = false },
                                    trailingIcon = if (sortMode == FolderSortMode.NAME) ({
                                        Icon(Icons.Default.Sort, null, tint = MaterialTheme.colorScheme.primary)
                                    }) else null,
                                )
                                DropdownMenuItem(
                                    text = { Text("Most songs") },
                                    onClick = { viewModel.setSortMode(FolderSortMode.MOST_SONGS); isSortMenuExpanded = false },
                                    trailingIcon = if (sortMode == FolderSortMode.MOST_SONGS) ({
                                        Icon(Icons.Default.Sort, null, tint = MaterialTheme.colorScheme.primary)
                                    }) else null,
                                )
                                DropdownMenuItem(
                                    text = { Text("Longest") },
                                    onClick = { viewModel.setSortMode(FolderSortMode.LONGEST); isSortMenuExpanded = false },
                                    trailingIcon = if (sortMode == FolderSortMode.LONGEST) ({
                                        Icon(Icons.Default.Sort, null, tint = MaterialTheme.colorScheme.primary)
                                    }) else null,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
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
        when (val s = state) {
            FoldersUiState.Loading -> LoadingContent(Modifier.padding(innerPadding))
            FoldersUiState.Empty -> EmptyContent(Modifier.padding(innerPadding))
            is FoldersUiState.Ready -> FolderListContent(
                folders = s.folders,
                onFolderClick = onFolderClick,
                sortMode = sortMode,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun FolderListContent(
    folders: List<FolderSummary>,
    onFolderClick: (String) -> Unit,
    sortMode: FolderSortMode,
    modifier: Modifier = Modifier,
) {
    if (folders.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyStateText(
                title = "No matching folders",
                message = "Try another search, or add music files in folders Wavdrop can scan.",
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 0.dp, top = 4.dp, end = 32.dp, bottom = 4.dp),
        ) {
            items(folders, key = { it.folderKey }) { folder ->
                FolderRow(folder = folder, onClick = { onFolderClick(folder.folderKey) })
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                )
            }
        }
        if (sortMode == FolderSortMode.NAME) {
            AlphabetSideIndex(
                onLetterSelected = { letter ->
                    AlphabetIndex.firstIndexForFolderLetter(folders, letter)?.let { index ->
                        coroutineScope.launch { listState.animateScrollToItem(index) }
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun FolderRow(
    folder: FolderSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 10.dp else 14.dp
    val iconSize = if (compact) 36.dp else 40.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
            modifier = Modifier.size(iconSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = folder.folderKey,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${folder.songCount} songs - ${formatTotalDuration(folder.totalDurationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Loading folders...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun EmptyContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyStateText(
            title = "No folders found",
            message = "Folders appear after Wavdrop scans local audio files with folder paths.",
        )
    }
}

@Composable
private fun EmptyStateText(
    title: String,
    message: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

private fun formatTotalDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    return if (totalMinutes < 60) {
        "$totalMinutes min"
    } else {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        if (m == 0L) "${h}h" else "${h}h ${m}m"
    }
}
