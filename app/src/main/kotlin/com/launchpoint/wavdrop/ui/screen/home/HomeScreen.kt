package com.launchpoint.wavdrop.ui.screen.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.R
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.search.AlphabetIndex
import com.launchpoint.wavdrop.data.settings.AppIconChoice
import com.launchpoint.wavdrop.data.settings.HomeSectionId
import com.launchpoint.wavdrop.playback.SleepTimerOption
import com.launchpoint.wavdrop.playback.SleepTimerState
import com.launchpoint.wavdrop.data.library.FolderGrouper
import com.launchpoint.wavdrop.ui.components.AddToPlaylistDialog
import com.launchpoint.wavdrop.ui.components.SleepTimerDialog
import com.launchpoint.wavdrop.ui.components.shareSong
import com.launchpoint.wavdrop.ui.components.AlphabetSideIndex
import com.launchpoint.wavdrop.ui.components.ArtworkImage
import com.launchpoint.wavdrop.ui.components.MiniPlayer
import com.launchpoint.wavdrop.ui.components.PrimaryDestination
import com.launchpoint.wavdrop.ui.components.PrimaryNavigationBar
import com.launchpoint.wavdrop.ui.components.SongRow
import com.launchpoint.wavdrop.ui.components.SongRowWithOverflow
import com.launchpoint.wavdrop.ui.permission.AudioPermissionGate
import com.launchpoint.wavdrop.ui.viewmodel.PlaylistActionsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSettingsClick: () -> Unit = {},
    onInsightsClick: () -> Unit = {},
    onSongsClick: () -> Unit = {},
    onLibraryClick: () -> Unit = {},
    onNowPlayingClick: () -> Unit = {},
    onTrackDetailsClick: (Long) -> Unit = {},
    onFolderClick: (String) -> Unit = {},
    onPlaylistsClick: () -> Unit = {},
    onSmartCollectionsClick: () -> Unit = {},
    onWrappedClick: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onGlobalSearchClick: () -> Unit = {},
    onReportsAndInsightsClick: () -> Unit = {},
    onLibrarySettingsClick: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    playlistVm: PlaylistActionsViewModel = hiltViewModel(),
) {
    // ── Permission state ──────────────────────────────────────────────────────
    // ── Playback + library state ──────────────────────────────────────────────
    val uiState         by viewModel.uiState.collectAsStateWithLifecycle()
    val dashboardState  by viewModel.dashboardState.collectAsStateWithLifecycle()
    val homeLayout      by viewModel.homeLayout.collectAsStateWithLifecycle()
    val nowPlaying      by viewModel.nowPlayingState.collectAsStateWithLifecycle()
    val favoriteSongIds by viewModel.favoriteSongIds.collectAsStateWithLifecycle()
    val appIconChoice   by viewModel.appIconChoice.collectAsStateWithLifecycle()
    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()

    val folderModeNeedsSelection by viewModel.folderModeNeedsSelection.collectAsStateWithLifecycle()
    val playlists        by playlistVm.playlists.collectAsStateWithLifecycle()
    val allPlaylistSongs by playlistVm.allPlaylistSongs.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope    = rememberCoroutineScope()
    val context           = LocalContext.current
    var addToPlaylistSong  by remember { mutableStateOf<Song?>(null) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sleepTimerNowMs  by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(sleepTimerState.isActive, sleepTimerState.endsAtMs) {
        if (!sleepTimerState.isActive || sleepTimerState.endsAtMs == null) return@LaunchedEffect
        while (true) {
            sleepTimerNowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val sleepTimerLabel = sleepTimerState.homeHeaderLabel(sleepTimerNowMs)

    // ── Layout ────────────────────────────────────────────────────────────────
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    HomeHeaderTitle(
                        appIconChoice = appIconChoice,
                        subtitle = dashboardState.librarySummary(),
                    )
                },
                actions = {
                    if (sleepTimerLabel != null) {
                        SleepTimerChip(
                            label = sleepTimerLabel,
                            onClick = { showSleepTimerDialog = true },
                        )
                    }
                    IconButton(onClick = onGlobalSearchClick) {
                        Icon(
                            imageVector        = Icons.Default.Search,
                            contentDescription = "Search",
                            tint               = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector        = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint               = MaterialTheme.colorScheme.onSurface,
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
            Column {
                MiniPlayer(
                    nowPlaying        = nowPlaying,
                    onOpenNowPlaying  = onNowPlayingClick,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onPrevious        = viewModel::skipToPrevious,
                    onNext            = viewModel::skipToNext,
                    onToggleShuffle   = viewModel::toggleShuffle,
                    onCycleRepeatMode = viewModel::cycleRepeatMode,
                    applyNavigationBarsPadding = false,
                )
                PrimaryNavigationBar(
                    selected = PrimaryDestination.HOME,
                    onHomeClick = {},
                    onSongsClick = onSongsClick,
                    onLibraryClick = onLibraryClick,
                    onInsightsClick = onInsightsClick,
                )
            }
        },
    ) { innerPadding ->
        AudioPermissionGate(
            onPermissionGranted = viewModel::syncIfNeeded,
            modifier = Modifier.padding(innerPadding),
        ) {
            if (uiState == HomeUiState.Loading) {
                ScanningContent(Modifier.padding(innerPadding))
            } else {
                HomeDashboardContent(
                    dashboard                = dashboardState,
                    visibleSections          = homeLayout.visibleSections,
                    currentSongId            = nowPlaying.song?.id,
                    favoriteSongIds          = favoriteSongIds,
                    nowPlayingSong           = nowPlaying.song,
                    folderModeNeedsSelection = folderModeNeedsSelection,
                    onNowPlayingClick        = onNowPlayingClick,
                    onLibraryClick           = onLibraryClick,
                    onPlaylistsClick         = onPlaylistsClick,
                    onSmartCollectionsClick  = onSmartCollectionsClick,
                    onWrappedClick           = onWrappedClick,
                    onReportsAndInsightsClick = onReportsAndInsightsClick,
                    onSongClick              = viewModel::playSong,
                    onToggleFavorite         = { song, wasFavorite ->
                        viewModel.toggleFavorite(song.id)
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                if (wasFavorite) "Removed from Favorites" else "Added to Favorites",
                            )
                        }
                    },
                    onTrackDetailsClick    = onTrackDetailsClick,
                    onPlayNext             = viewModel::playNext,
                    onAddToQueue           = viewModel::addToQueue,
                    onAddToPlaylist        = { song -> addToPlaylistSong = song },
                    onShare                = { song ->
                        shareSong(context, song) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Could not share this track")
                            }
                        }
                    },
                    onRescan               = viewModel::refreshLibrary,
                    onSettingsClick        = onSettingsClick,
                    onLibrarySettingsClick = onLibrarySettingsClick,
                    modifier               = Modifier.padding(innerPadding),
                )
            }
        }
    }

    if (showSleepTimerDialog) {
        SleepTimerDialog(
            state = sleepTimerState,
            onOptionSelected = { option ->
                viewModel.setSleepTimer(option)
                showSleepTimerDialog = false
            },
            onCustomDurationSelected = { durationMs ->
                viewModel.setCustomSleepTimer(durationMs)
                showSleepTimerDialog = false
            },
            onDismiss = { showSleepTimerDialog = false },
        )
    }

    addToPlaylistSong?.let { song ->
        AddToPlaylistDialog(
            playlists           = playlists,
            existingPlaylistIds = allPlaylistSongs
                .filter { it.songId == song.id }
                .map { it.playlistId }
                .toSet(),
            onSelectPlaylist    = { playlistId ->
                playlistVm.addSongToPlaylist(song.id, playlistId) { result ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(result.singleSongMessage())
                    }
                }
                addToPlaylistSong = null
            },
            onCreateAndAdd      = { name ->
                playlistVm.createPlaylistAndAddSong(name, song.id)
                addToPlaylistSong = null
            },
            onDismiss           = { addToPlaylistSong = null },
        )
    }
}

@Composable
private fun HomeHeaderTitle(
    appIconChoice: AppIconChoice,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painter = painterResource(appIconChoice.homeHeaderIconResId()),
            contentDescription = "Selected Wavdrop icon",
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Wavdrop",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SleepTimerChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .padding(end = 2.dp)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Timer,
                contentDescription = "Sleep timer active — tap to adjust",
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun HomeDashboardUiState.librarySummary(): String =
    if (totalSongs > 0) "$totalSongs local songs" else "Your local music library"

internal enum class HomeListeningActivityPreview {
    RECENTLY_PLAYED,
    MOST_PLAYED,
}

internal fun selectHomeListeningActivityPreview(
    visibleSections: Set<HomeSectionId>,
    hasRecentlyPlayed: Boolean,
    hasMostPlayed: Boolean,
): HomeListeningActivityPreview? {
    val enabled =
        HomeSectionId.RECENTLY_PLAYED in visibleSections ||
            HomeSectionId.MOST_PLAYED in visibleSections
    if (!enabled) return null
    return when {
        hasRecentlyPlayed -> HomeListeningActivityPreview.RECENTLY_PLAYED
        hasMostPlayed     -> HomeListeningActivityPreview.MOST_PLAYED
        else              -> null
    }
}

private fun SleepTimerState.homeHeaderLabel(nowMs: Long): String? = when {
    !isActive -> null
    option == SleepTimerOption.END_OF_CURRENT_SONG -> "Timer: End of song"
    else -> endsAtMs?.let { endsAt ->
        val remaining = (endsAt - nowMs).coerceAtLeast(0L)
        "Timer %d:%02d".format(remaining / 60_000L, (remaining % 60_000L) / 1_000L)
    }
}

private fun AppIconChoice.homeHeaderIconResId(): Int = when (this) {
    AppIconChoice.MIDNIGHT_VIOLET -> R.mipmap.wavdrop_icon_midnight_violet
    AppIconChoice.CLEAN_PURPLE    -> R.mipmap.wavdrop_icon_clean_purple
    AppIconChoice.DEEP_TEAL       -> R.mipmap.wavdrop_icon_deep_teal
    AppIconChoice.OBSIDIAN_BLACK  -> R.mipmap.wavdrop_icon_obsidian_black
    AppIconChoice.OCEAN_BLUE      -> R.mipmap.wavdrop_icon_ocean_blue
    AppIconChoice.SUNSET_ORANGE   -> R.mipmap.wavdrop_icon_sunset_orange
}

// ── Permission screens ────────────────────────────────────────────────────────

// ── Library content ───────────────────────────────────────────────────────────

@Composable
private fun LibraryContent(
    uiState: HomeUiState,
    currentSongId: Long?,
    favoriteSongIds: Set<Long>,
    onSongClick: (Song) -> Unit,
    onShuffleAll: () -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onToggleFavorite: (Song, Boolean) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onFolderClick: (String) -> Unit,
    onShare: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        HomeUiState.Loading  -> ScanningContent(modifier)
        HomeUiState.Empty    -> EmptyLibraryContent(onRescan = {}, onSettingsClick = {}, modifier = modifier)
        is HomeUiState.Songs -> SongListContent(
            songs               = uiState.songs,
            currentSongId       = currentSongId,
            favoriteSongIds     = favoriteSongIds,
            onSongClick         = onSongClick,
            onShuffleAll        = onShuffleAll,
            onPlayNext          = onPlayNext,
            onAddToQueue        = onAddToQueue,
            onToggleFavorite    = onToggleFavorite,
            onAddToPlaylist     = onAddToPlaylist,
            onTrackDetailsClick = onTrackDetailsClick,
            onFolderClick       = onFolderClick,
            onShare             = onShare,
            modifier            = modifier,
        )
    }
}

@Composable
private fun ScanningContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text  = "Scanning library…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun EmptyLibraryContent(
    onRescan: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredColumn(modifier) {
        Icon(
            imageVector        = Icons.Default.LibraryMusic,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier           = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text      = "No songs found",
            style     = MaterialTheme.typography.titleMedium,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text      = "Add audio files to your device, then rescan your library.",
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            textAlign = TextAlign.Center,
            modifier  = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text      = "Wavdrop only scans music stored on this device.",
            style     = MaterialTheme.typography.bodySmall,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            textAlign = TextAlign.Center,
            modifier  = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRescan) { Text("Rescan library") }
        TextButton(onClick = onSettingsClick) { Text("Choose folder in Settings") }
    }
}

@Composable
private fun HomeDashboardContent(
    dashboard: HomeDashboardUiState,
    visibleSections: Set<HomeSectionId>,
    currentSongId: Long?,
    favoriteSongIds: Set<Long>,
    nowPlayingSong: Song?,
    folderModeNeedsSelection: Boolean,
    onNowPlayingClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
    onSmartCollectionsClick: () -> Unit,
    onWrappedClick: () -> Unit,
    onReportsAndInsightsClick: () -> Unit,
    onSongClick: (Song) -> Unit,
    onToggleFavorite: (Song, Boolean) -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onShare: (Song) -> Unit,
    onRescan: () -> Unit,
    onSettingsClick: () -> Unit,
    onLibrarySettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp),
    ) {
        if (HomeSectionId.CONTINUE_LISTENING in visibleSections) {
            item {
                ContinueListeningCard(
                    song = nowPlayingSong,
                    onClick = onNowPlayingClick,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }

        if (dashboard.totalSongs == 0) {
            item {
                DashboardEmptyLibraryCard(
                    folderModeNeedsSelection = folderModeNeedsSelection,
                    onRescan = onRescan,
                    onSettingsClick = onSettingsClick,
                    onLibrarySettingsClick = onLibrarySettingsClick,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            when (
                selectHomeListeningActivityPreview(
                    visibleSections = visibleSections,
                    hasRecentlyPlayed = dashboard.recentlyPlayed.isNotEmpty(),
                    hasMostPlayed = dashboard.mostPlayed.isNotEmpty(),
                )
            ) {
                HomeListeningActivityPreview.RECENTLY_PLAYED -> {
                    dashboardSection(
                        title = "Recently Played",
                        songs = dashboard.recentlyPlayed,
                        emptyText = "",
                        currentSongId = currentSongId,
                        favoriteSongIds = favoriteSongIds,
                        onSongClick = onSongClick,
                        onToggleFavorite = onToggleFavorite,
                        onTrackDetailsClick = onTrackDetailsClick,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        onShare = onShare,
                        actionLabel = "View insights",
                        onActionClick = onReportsAndInsightsClick,
                    )
                }
                HomeListeningActivityPreview.MOST_PLAYED -> {
                    dashboardSection(
                        title = "Most Played",
                        songs = dashboard.mostPlayed,
                        emptyText = "",
                        currentSongId = currentSongId,
                        favoriteSongIds = favoriteSongIds,
                        onSongClick = onSongClick,
                        onToggleFavorite = onToggleFavorite,
                        onTrackDetailsClick = onTrackDetailsClick,
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        onShare = onShare,
                        actionLabel = "View insights",
                        onActionClick = onReportsAndInsightsClick,
                    )
                }
                null -> Unit
            }
            if (HomeSectionId.WRAPPED in visibleSections && dashboard.wrapped != null) {
                item {
                    WrappedPreviewCard(
                        wrapped = dashboard.wrapped,
                        onClick = onWrappedClick,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            if (HomeSectionId.SMART_COLLECTIONS in visibleSections) {
                item {
                    DashboardListSectionHeader(
                        title = "Smart Collections",
                        actionLabel = "View all",
                        onActionClick = onSmartCollectionsClick,
                    )
                }
                if (dashboard.smartCollections.isEmpty()) {
                    item { DashboardEmptyText("Smart collections like Favorites and Recently Played will appear as you listen.") }
                } else {
                    items(dashboard.smartCollections, key = { it.id }) { collection ->
                        SmartCollectionPreviewRow(collection = collection, onClick = onSmartCollectionsClick)
                    }
                }
            }
            item {
                LibraryShortcutCard(
                    totalSongs = dashboard.totalSongs,
                    onClick = onLibraryClick,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        if (dashboard.totalSongs == 0) {
            item {
                LibraryShortcutCard(
                    totalSongs = dashboard.totalSongs,
                    onClick = onLibraryClick,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.dashboardSection(
    title: String,
    songs: List<Song>,
    emptyText: String,
    currentSongId: Long?,
    favoriteSongIds: Set<Long>,
    onSongClick: (Song) -> Unit,
    onToggleFavorite: (Song, Boolean) -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onShare: (Song) -> Unit,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    item {
        DashboardListSectionHeader(
            title = title,
            actionLabel = actionLabel,
            onActionClick = onActionClick,
        )
    }
    if (songs.isEmpty()) {
        item { DashboardEmptyText(emptyText) }
    } else {
        items(songs, key = { "${title}_${it.id}" }) { song ->
            val isFavorite = song.id in favoriteSongIds
            SongRowWithOverflow(
                song             = song,
                isCurrent        = song.id == currentSongId,
                isFavorite       = isFavorite,
                onPlay           = { onSongClick(song) },
                onPlayNext       = { onPlayNext(song) },
                onAddToQueue     = { onAddToQueue(song) },
                onToggleFavorite = { onToggleFavorite(song, isFavorite) },
                onAddToPlaylist  = { onAddToPlaylist(song) },
                onTrackDetails   = { onTrackDetailsClick(song.id) },
                onShare          = { onShare(song) },
                modifier         = Modifier.fillMaxWidth(),
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 0.5.dp,
            )
        }
    }
}

@Composable
private fun WrappedPreviewCard(
    wrapped: WrappedSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlight = when {
        wrapped.mostPlayedArtist != null -> "Top artist: ${wrapped.mostPlayedArtist.artistKey}"
        wrapped.mostPlayedSong != null -> "Top track: ${wrapped.mostPlayedSong.song.displayTitle}"
        else -> "${wrapped.totalPlayCount} plays"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Wrapped",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = wrapped.period.displayLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = highlight,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onClick) {
                    Text("View")
                }
            }
            Text(
                text = "Your year in music",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun DashboardEmptyLibraryCard(
    folderModeNeedsSelection: Boolean,
    onRescan: () -> Unit,
    onSettingsClick: () -> Unit,
    onLibrarySettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.LibraryMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    if (folderModeNeedsSelection) {
                        Text(
                            text = "No folders selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Wavdrop is set to scan selected folders only, but no folders have been added yet. Add a folder in Library Settings to start scanning.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    } else {
                        Text(
                            text = "No songs found yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Add audio files to your device, then rescan your library.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                        Text(
                            text = "Wavdrop only scans music stored on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (folderModeNeedsSelection) {
                    TextButton(onClick = onLibrarySettingsClick) { Text("Open Library Settings") }
                } else {
                    TextButton(onClick = onRescan) { Text("Rescan library") }
                    TextButton(onClick = onLibrarySettingsClick) { Text("Choose folder in Settings") }
                }
            }
        }
    }
}

@Composable
private fun LibraryShortcutCard(
    totalSongs: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.LibraryMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (totalSongs > 0) {
                        "$totalSongs songs, plus albums, artists, folders and playlists"
                    } else {
                        "Browse songs, albums, artists, folders and playlists"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onClick) {
                Text("Open")
            }
        }
    }
}

@Composable
private fun ContinueListeningCard(
    song: Song?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = song != null, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ArtworkImage(
                artworkUri = song?.let { ArtworkResolver.albumArtworkUri(it.albumId) },
                contentDescription = song?.let { "Album artwork for ${it.album}" },
                placeholderIcon = Icons.Default.MusicNote,
                modifier = Modifier.size(44.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Continue Listening",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = song?.title ?: "Nothing playing yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song?.artist ?: "Pick something from your library when you are ready.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DashboardListSectionHeader(
    title: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onActionClick != null) {
            TextButton(onClick = onActionClick) { Text(actionLabel) }
        }
    }
}

@Composable
private fun DashboardEmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PlaylistPreviewRow(
    playlist: PlaylistSummary,
    onClick: () -> Unit,
) {
    DashboardPreviewRow(
        title = playlist.name,
        subtitle = "${playlist.songCount} songs",
        icon = Icons.AutoMirrored.Filled.QueueMusic,
        onClick = onClick,
    )
}

@Composable
private fun SmartCollectionPreviewRow(
    collection: SmartCollection,
    onClick: () -> Unit,
) {
    DashboardPreviewRow(
        title = collection.title,
        subtitle = "${collection.songCount} songs",
        icon = Icons.Default.AutoAwesome,
        onClick = onClick,
    )
}

@Composable
private fun DashboardPreviewRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun SongListContent(
    songs: List<Song>,
    currentSongId: Long?,
    favoriteSongIds: Set<Long>,
    onSongClick: (Song) -> Unit,
    onShuffleAll: () -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onToggleFavorite: (Song, Boolean) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onTrackDetailsClick: (Long) -> Unit,
    onFolderClick: (String) -> Unit,
    onShare: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text  = "No matching songs. Try another search or add music that matches this query.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        return
    }
    val listState      = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state          = listState,
            modifier       = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 0.dp, top = 4.dp, end = 32.dp, bottom = 4.dp),
        ) {
            item(key = "shuffle_header") {
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onShuffleAll) {
                        Icon(
                            imageVector        = Icons.Default.Shuffle,
                            contentDescription = null,
                            modifier           = Modifier
                                .size(18.dp)
                                .padding(end = 2.dp),
                        )
                        Text(
                            text     = "Shuffle ${songs.size} songs",
                            style    = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                HorizontalDivider(
                    color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                )
            }
            items(songs, key = { it.id }) { song ->
                val isFavorite = song.id in favoriteSongIds
                SongRowWithOverflow(
                    song             = song,
                    isCurrent        = song.id == currentSongId,
                    isFavorite       = isFavorite,
                    onPlay           = { onSongClick(song) },
                    onPlayNext       = { onPlayNext(song) },
                    onAddToQueue     = { onAddToQueue(song) },
                    onToggleFavorite = { onToggleFavorite(song, isFavorite) },
                    onAddToPlaylist  = { onAddToPlaylist(song) },
                    onTrackDetails   = { onTrackDetailsClick(song.id) },
                    onViewFolder     = song.validFolderKey()?.let { key -> { onFolderClick(key) } },
                    onShare          = { onShare(song) },
                    modifier         = Modifier.fillMaxWidth(),
                )
                HorizontalDivider(
                    color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    thickness = 0.5.dp,
                )
            }
        }
        AlphabetSideIndex(
            onLetterSelected = { letter ->
                // offset +1 accounts for the shuffle header item at index 0
                AlphabetIndex.firstIndexForSongLetter(songs, letter)?.let { index ->
                    coroutineScope.launch { listState.animateScrollToItem(index + 1) }
                }
            },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

private fun Song.validFolderKey(): String? {
    val hasFolderMetadata = !folderPath
        ?.trim()
        ?.trim('/', '\\')
        .isNullOrBlank()
    if (!hasFolderMetadata) return null
    return FolderGrouper.folderKey(this)
        .takeUnless { it == FolderGrouper.UNKNOWN_FOLDER }
        ?.takeIf { it.isNotBlank() }
}

// ── Shared layout helper ──────────────────────────────────────────────────────

@Composable
private fun CenteredColumn(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier              = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally,
        content               = { content() },
    )
}
