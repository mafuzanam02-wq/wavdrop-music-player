package com.launchpoint.wavdrop.ui.screen.nowplaying

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.data.grouping.AlbumGrouper
import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.library.FolderGrouper
import com.launchpoint.wavdrop.data.lyrics.LyricsResult
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.playback.RepeatMode
import com.launchpoint.wavdrop.playback.SleepTimerOption
import com.launchpoint.wavdrop.ui.components.AddToPlaylistDialog
import com.launchpoint.wavdrop.ui.components.shareSong
import com.launchpoint.wavdrop.ui.components.ArtworkImage
import com.launchpoint.wavdrop.ui.components.LocalArtworkCornerStyle
import com.launchpoint.wavdrop.ui.components.LocalNowPlayingBackground
import com.launchpoint.wavdrop.ui.components.LocalNowPlayingTimeDisplayMode
import com.launchpoint.wavdrop.ui.components.PrimaryDestination
import com.launchpoint.wavdrop.data.settings.NowPlayingTimeDisplayMode
import com.launchpoint.wavdrop.ui.components.PrimaryNavigationBar
import com.launchpoint.wavdrop.ui.components.toShape
import com.launchpoint.wavdrop.data.settings.NowPlayingBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onNavigateBack: () -> Unit,
    onHomeClick: () -> Unit = {},
    onSongsClick: () -> Unit = {},
    onLibraryClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onOpenTrackDetails: (Long) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onOpenFolder: (String) -> Unit = {},
    onOpenStatistics: () -> Unit = {},
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val state             by viewModel.nowPlayingState.collectAsStateWithLifecycle()
    val lyricsState       by viewModel.lyricsState.collectAsStateWithLifecycle()
    val isFavorite        by viewModel.isFavorite.collectAsStateWithLifecycle()
    val hasCustomLyrics   by viewModel.hasCustomLyrics.collectAsStateWithLifecycle()
    val sleepTimerState   by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val playlists         by viewModel.playlists.collectAsStateWithLifecycle()
    val allPlaylistSongs  by viewModel.allPlaylistSongs.collectAsStateWithLifecycle()
    val snackbarHostState  = remember { SnackbarHostState() }
    val coroutineScope     = rememberCoroutineScope()
    val context            = LocalContext.current
    var showAddToPlaylist  by remember { mutableStateOf(false) }
    var showLyricsOverlay  by remember { mutableStateOf(false) }
    var showLyricsEditor   by remember { mutableStateOf(false) }
    var overlayBeforeEdit  by remember { mutableStateOf(false) }
    var showMoreActions    by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showQueueSheet     by remember { mutableStateOf(false) }

    // Tick every second while a duration-based timer is counting down.
    var sleepTimerNowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepTimerState.isActive, sleepTimerState.endsAtMs) {
        if (!sleepTimerState.isActive || sleepTimerState.endsAtMs == null) return@LaunchedEffect
        while (true) {
            sleepTimerNowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val sleepTimerLabel: String? = when {
        !sleepTimerState.isActive -> null
        sleepTimerState.option == SleepTimerOption.END_OF_CURRENT_SONG -> "Sleep Timer: End of song"
        else -> sleepTimerState.endsAtMs?.let { endsAtMs ->
            val remaining = (endsAtMs - sleepTimerNowMs).coerceAtLeast(0L)
            "Sleep Timer: %d:%02d".format(remaining / 60_000L, (remaining % 60_000L) / 1_000L)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text     = "Now Playing",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    val song = state.song
                    if (song != null) {
                        val isExternalAudio = song.isExternalAudio()
                        val folderKey = song.validFolderKey()
                        IconButton(onClick = { showQueueSheet = true }) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "Open queue",
                                tint               = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (!isExternalAudio) {
                            IconButton(onClick = { showAddToPlaylist = true }) {
                                Icon(
                                    imageVector        = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = "Add to playlist",
                                    tint               = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(onClick = viewModel::toggleFavorite) {
                                Icon(
                                    imageVector        = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                                    tint               = if (isFavorite) MaterialTheme.colorScheme.primary
                                                         else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        IconButton(onClick = { showSleepTimerDialog = true }) {
                            Icon(
                                imageVector        = Icons.Default.Timer,
                                contentDescription = if (sleepTimerState.isActive) "Sleep Timer active"
                                                     else "Sleep Timer",
                                tint               = if (sleepTimerState.isActive)
                                                         MaterialTheme.colorScheme.primary
                                                     else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Box {
                            IconButton(onClick = { showMoreActions = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More actions",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            DropdownMenu(
                                expanded = showMoreActions,
                                onDismissRequest = { showMoreActions = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Open Queue") },
                                    onClick = {
                                        showMoreActions = false
                                        showQueueSheet = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    onClick = {
                                        showMoreActions = false
                                        shareSong(context, song) {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar("Could not share this track")
                                            }
                                        }
                                    },
                                )
                                if (folderKey != null) {
                                    DropdownMenuItem(
                                        text = { Text("Folder") },
                                        onClick = {
                                            showMoreActions = false
                                            onOpenFolder(folderKey)
                                        },
                                    )
                                }
                                if (!isExternalAudio) {
                                    DropdownMenuItem(
                                        text = { Text("Track details") },
                                        onClick = {
                                            showMoreActions = false
                                            onOpenTrackDetails(song.id)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Stats") },
                                        onClick = {
                                            showMoreActions = false
                                            onOpenStatistics()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Search lyrics online") },
                                    onClick = {
                                        showMoreActions = false
                                        searchLyricsOnline(context, song)
                                    },
                                )
                                if (song.hasKnownArtist()) {
                                    DropdownMenuItem(
                                        text = { Text("Search artist online") },
                                        onClick = {
                                            showMoreActions = false
                                            searchArtistOnline(context, song)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            PrimaryNavigationBar(
                selected = null,
                onHomeClick = onHomeClick,
                onSongsClick = onSongsClick,
                onLibraryClick = onLibraryClick,
                onSettingsClick = onSettingsClick,
            )
        },
    ) { innerPadding ->
        if (state.song == null) {
            EmptyNowPlayingContent(Modifier.padding(innerPadding))
        } else {
            NowPlayingContent(
                state             = state,
                onPrevious        = viewModel::skipToPrevious,
                onTogglePlayPause = viewModel::togglePlayPause,
                onNext            = viewModel::skipToNext,
                onToggleShuffle   = viewModel::toggleShuffle,
                onCycleRepeatMode = viewModel::cycleRepeatMode,
                onSeek            = viewModel::seekTo,
                lyrics            = lyricsState,
                showLyricsOverlay = showLyricsOverlay,
                onToggleLyrics    = { showLyricsOverlay = !showLyricsOverlay },
                onEditLyrics      = { overlayBeforeEdit = showLyricsOverlay; showLyricsEditor = true },
                onOpenTrackDetails = onOpenTrackDetails,
                onOpenAlbum        = onOpenAlbum,
                onOpenArtist       = onOpenArtist,
                onOpenQueue       = { showQueueSheet = true },
                sleepTimerLabel   = sleepTimerLabel,
                modifier          = Modifier.padding(innerPadding),
            )
        }
    }

    if (showAddToPlaylist) {
        val song = state.song
        AddToPlaylistDialog(
            playlists           = playlists,
            existingPlaylistIds = if (song != null) {
                allPlaylistSongs
                    .filter { it.songId == song.id }
                    .map { it.playlistId }
                    .toSet()
            } else emptySet(),
            onSelectPlaylist    = { id ->
                viewModel.addToPlaylist(id) { result ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(result.singleSongMessage())
                    }
                }
                showAddToPlaylist = false
            },
            onCreateAndAdd      = { name ->
                viewModel.createPlaylistAndAdd(name)
                showAddToPlaylist = false
            },
            onDismiss           = { showAddToPlaylist = false },
        )
    }

    if (showSleepTimerDialog) {
        SleepTimerDialog(
            selected = sleepTimerState.option,
            onSelect = { option ->
                viewModel.setSleepTimer(option)
                showSleepTimerDialog = false
            },
            onDismiss = { showSleepTimerDialog = false },
        )
    }

    if (showLyricsEditor && state.song != null) {
        LyricsEditorDialog(
            lyrics = lyricsState,
            hasCustomLyrics = hasCustomLyrics,
            onSave = { text ->
                viewModel.saveCustomLyrics(text) {
                    showLyricsEditor = false
                    showLyricsOverlay = overlayBeforeEdit
                }
            },
            onClear = {
                viewModel.clearCustomLyrics {
                    showLyricsEditor = false
                    showLyricsOverlay = overlayBeforeEdit
                }
            },
            onDismiss = { showLyricsEditor = false },
        )
    }

    if (showQueueSheet) {
        QueueSheet(
            state        = state,
            onDismiss    = { showQueueSheet = false },
            onJumpToItem = { viewModel.jumpToQueueItem(it) },
            onRemoveItem = { viewModel.removeFromQueue(it) },
            onMoveUp     = { viewModel.moveQueueItemUp(it) },
            onMoveDown   = { viewModel.moveQueueItemDown(it) },
            onMoveItemTo = { from, to -> viewModel.moveQueueItemTo(from, to) },
            onPlayNext   = { viewModel.moveToPlayNext(it) },
            onPlaySongNext = { viewModel.playNext(it) },
            onAddSongToQueue = { viewModel.addToQueue(it) },
            onViewStats  = { songId ->
                showQueueSheet = false
                onOpenTrackDetails(songId)
            },
        )
    }
}

@Composable
private fun SleepTimerDialog(
    selected: SleepTimerOption,
    onSelect: (SleepTimerOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer") },
        text = {
            Column {
                SleepTimerOption.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = { onSelect(option) },
                        )
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun NowPlayingContent(
    state: NowPlayingState,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onSeek: (Long) -> Unit,
    lyrics: LyricsResult,
    showLyricsOverlay: Boolean,
    onToggleLyrics: () -> Unit,
    onEditLyrics: () -> Unit,
    onOpenTrackDetails: (Long) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenQueue: () -> Unit,
    sleepTimerLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val song = state.song ?: return
    val queuePosition = if (state.currentIndex >= 0 && state.queue.isNotEmpty()) {
        "${state.currentIndex + 1} / ${state.queue.size}"
    } else {
        ""
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val layoutProfile = resolveNowPlayingLayoutProfile(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            fontScale = fontScale,
        )
        val metrics = nowPlayingLayoutMetrics(profile = layoutProfile)
        val titleClick: (() -> Unit)? = if (!song.isExternalAudio()) {
            { onOpenTrackDetails(song.id) }
        } else {
            null
        }
        val artistClick: (() -> Unit)? = if (song.hasKnownArtist()) {
            { onOpenArtist(ArtistGrouper.artistKey(song)) }
        } else {
            null
        }
        val albumClick: (() -> Unit)? = if (song.hasKnownAlbum()) {
            { onOpenAlbum(AlbumGrouper.albumKey(song)) }
        } else {
            null
        }
        val upNextCount = (state.queue.size - state.currentIndex - 1).coerceAtLeast(0)

        FixedBottomNowPlayingLayout(
            state = state,
            song = song,
            queuePosition = queuePosition,
            lyrics = lyrics,
            profile = layoutProfile,
            metrics = metrics,
            showLyricsOverlay = showLyricsOverlay,
            onToggleLyrics = onToggleLyrics,
            onEditLyrics = onEditLyrics,
            onPrevious = onPrevious,
            onTogglePlayPause = onTogglePlayPause,
            onNext = onNext,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeatMode = onCycleRepeatMode,
            onSeek = onSeek,
            onOpenTrackDetails = titleClick,
            onOpenArtist = artistClick,
            onOpenAlbum = albumClick,
            onOpenQueue = onOpenQueue,
            sleepTimerLabel = sleepTimerLabel,
            upNextCount = upNextCount,
        )
    }
}

private enum class NowPlayingLayoutProfile {
    Compact,
    Medium,
    Expanded,
}

private fun resolveNowPlayingLayoutProfile(
    maxWidth: Dp,
    maxHeight: Dp,
    fontScale: Float,
): NowPlayingLayoutProfile = when {
    maxHeight < 640.dp || maxWidth < 380.dp || (fontScale >= 1.15f && maxHeight < 700.dp) ->
        NowPlayingLayoutProfile.Compact
    maxHeight >= 780.dp && maxWidth >= 400.dp && fontScale < 1.15f ->
        NowPlayingLayoutProfile.Expanded
    else ->
        NowPlayingLayoutProfile.Medium
}

private data class NowPlayingLayoutMetrics(
    val artworkSize: Dp,
    val lyricsPanelHeight: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val titleStyle: TextStyle,
    val artistStyle: TextStyle,
    val showAlbum: Boolean,
    val showQueuePosition: Boolean,
    val showLyricsHint: Boolean,
    val bottomPanelSpacing: Dp,
)

@Composable
private fun nowPlayingLayoutMetrics(profile: NowPlayingLayoutProfile): NowPlayingLayoutMetrics =
    when (profile) {
        NowPlayingLayoutProfile.Compact -> NowPlayingLayoutMetrics(
            artworkSize = 120.dp,
            lyricsPanelHeight = 140.dp,
            horizontalPadding = 16.dp,
            verticalPadding = 8.dp,
            titleStyle = MaterialTheme.typography.titleSmall,
            artistStyle = MaterialTheme.typography.bodySmall,
            showAlbum = false,
            showQueuePosition = false,
            showLyricsHint = false,
            bottomPanelSpacing = 4.dp,
        )
        NowPlayingLayoutProfile.Medium -> NowPlayingLayoutMetrics(
            artworkSize = 200.dp,
            lyricsPanelHeight = 200.dp,
            horizontalPadding = 24.dp,
            verticalPadding = 12.dp,
            titleStyle = MaterialTheme.typography.headlineSmall,
            artistStyle = MaterialTheme.typography.titleMedium,
            showAlbum = true,
            showQueuePosition = true,
            showLyricsHint = true,
            bottomPanelSpacing = 6.dp,
        )
        NowPlayingLayoutProfile.Expanded -> NowPlayingLayoutMetrics(
            artworkSize = 240.dp,
            lyricsPanelHeight = 240.dp,
            horizontalPadding = 24.dp,
            verticalPadding = 16.dp,
            titleStyle = MaterialTheme.typography.headlineSmall,
            artistStyle = MaterialTheme.typography.titleMedium,
            showAlbum = true,
            showQueuePosition = true,
            showLyricsHint = true,
            bottomPanelSpacing = 6.dp,
        )
    }

private fun NowPlayingLayoutMetrics.withUpperAreaSizing(
    profile: NowPlayingLayoutProfile,
    upperWidth: Dp,
    upperHeight: Dp,
): NowPlayingLayoutMetrics {
    // Usable width after horizontal padding.
    val availableWidth = (upperWidth - 16.dp).coerceAtLeast(96.dp)

    // Fraction of the upper area height allocated to the artwork / lyrics panel.
    // Larger screens get a higher fraction so the artwork feels proportionate.
    val artworkHeightFraction = when (profile) {
        NowPlayingLayoutProfile.Compact  -> 0.48f
        NowPlayingLayoutProfile.Medium   -> 0.56f
        NowPlayingLayoutProfile.Expanded -> 0.62f
    }
    // Lyrics panel is allowed slightly more vertical room than artwork so lyrics text
    // has breathing space on all device sizes.
    val lyricsHeightFraction = when (profile) {
        NowPlayingLayoutProfile.Compact  -> 0.72f
        NowPlayingLayoutProfile.Medium   -> 0.80f
        NowPlayingLayoutProfile.Expanded -> 0.86f
    }

    // Minimum sizes prevent artwork from becoming unusably small on very short screens.
    val minArtwork = when (profile) {
        NowPlayingLayoutProfile.Compact  -> 96.dp
        NowPlayingLayoutProfile.Medium   -> 140.dp
        NowPlayingLayoutProfile.Expanded -> 160.dp
    }
    val minLyrics = when (profile) {
        NowPlayingLayoutProfile.Compact  -> 120.dp
        NowPlayingLayoutProfile.Medium   -> 180.dp
        NowPlayingLayoutProfile.Expanded -> 200.dp
    }

    val artworkFromHeight = (upperHeight * artworkHeightFraction).coerceAtLeast(minArtwork)
    val lyricsFromHeight  = (upperHeight * lyricsHeightFraction).coerceAtLeast(minLyrics)

    // Final size = min(width-constrained, height-based).
    // This naturally caps square artwork to both the available width and the available height
    // fraction without any hardcoded upper limit that could over-constrain tall screens.
    val newArtworkSize       = minOf(availableWidth, artworkFromHeight)
    val newLyricsPanelHeight = minOf(availableWidth, lyricsFromHeight)

    val showSecondaryMetadata = upperHeight >= when (profile) {
        NowPlayingLayoutProfile.Compact  -> 999.dp  // never on compact
        NowPlayingLayoutProfile.Medium   -> 400.dp
        NowPlayingLayoutProfile.Expanded -> 420.dp
    }

    return copy(
        artworkSize       = newArtworkSize,
        lyricsPanelHeight = newLyricsPanelHeight,
        showAlbum         = showAlbum && showSecondaryMetadata,
        showQueuePosition = showQueuePosition && upperHeight >= when (profile) {
            NowPlayingLayoutProfile.Compact  -> 999.dp
            NowPlayingLayoutProfile.Medium   -> 460.dp
            NowPlayingLayoutProfile.Expanded -> 480.dp
        },
    )
}

@Composable
private fun FixedBottomNowPlayingLayout(
    state: NowPlayingState,
    song: Song,
    queuePosition: String,
    lyrics: LyricsResult,
    profile: NowPlayingLayoutProfile,
    metrics: NowPlayingLayoutMetrics,
    showLyricsOverlay: Boolean,
    onToggleLyrics: () -> Unit,
    onEditLyrics: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenTrackDetails: (() -> Unit)?,
    onOpenArtist: (() -> Unit)?,
    onOpenAlbum: (() -> Unit)?,
    onOpenQueue: () -> Unit,
    sleepTimerLabel: String?,
    upNextCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = metrics.horizontalPadding,
                end = metrics.horizontalPadding,
                top = metrics.verticalPadding,
                bottom = 4.dp,
            ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val upperMetrics = metrics.withUpperAreaSizing(
                profile = profile,
                upperWidth = maxWidth,
                upperHeight = maxHeight,
            )
            UpperNowPlayingContent(
                song = song,
                queuePosition = queuePosition,
                lyrics = lyrics,
                metrics = upperMetrics,
                showLyricsOverlay = showLyricsOverlay,
                onToggleLyrics = onToggleLyrics,
                onPrevious = onPrevious,
                onNext = onNext,
                onEditLyrics = onEditLyrics,
                onOpenTrackDetails = onOpenTrackDetails,
                onOpenArtist = onOpenArtist,
                onOpenAlbum = onOpenAlbum,
            )
        }

        BottomPlaybackPanel(
            state = state,
            metrics = metrics,
            onToggleShuffle = onToggleShuffle,
            onPrevious = onPrevious,
            onTogglePlayPause = onTogglePlayPause,
            onNext = onNext,
            onCycleRepeatMode = onCycleRepeatMode,
            onSeek = onSeek,
            onOpenQueue = onOpenQueue,
            sleepTimerLabel = sleepTimerLabel,
            upNextCount = upNextCount,
        )
    }
}

@Composable
private fun UpperNowPlayingContent(
    song: Song,
    queuePosition: String,
    lyrics: LyricsResult,
    metrics: NowPlayingLayoutMetrics,
    showLyricsOverlay: Boolean,
    onToggleLyrics: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onEditLyrics: () -> Unit,
    onOpenTrackDetails: (() -> Unit)?,
    onOpenArtist: (() -> Unit)?,
    onOpenAlbum: (() -> Unit)?,
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showLyricsOverlay) {
                ArtworkWithLyricsOverlay(
                    song = song,
                    lyrics = lyrics,
                    showLyricsOverlay = true,
                    onToggleLyrics = onToggleLyrics,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onEditLyrics = onEditLyrics,
                    compactLyricsOverlay = !metrics.showLyricsHint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(metrics.lyricsPanelHeight),
                )
            } else {
                ArtworkWithLyricsOverlay(
                    song = song,
                    lyrics = lyrics,
                    showLyricsOverlay = false,
                    onToggleLyrics = onToggleLyrics,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onEditLyrics = onEditLyrics,
                    modifier = Modifier.size(metrics.artworkSize),
                )
                if (metrics.showLyricsHint) {
                    Text(
                        text = "Double-tap for lyrics · Long-press to edit lyrics",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Text(
                text = song.title,
                style = metrics.titleStyle,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableIfNotNull(onOpenTrackDetails),
            )
            Text(
                text = song.artist,
                style = metrics.artistStyle,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableIfNotNull(onOpenArtist),
            )
            if (metrics.showAlbum && song.album.isNotBlank()) {
                Text(
                    text = song.album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableIfNotNull(onOpenAlbum),
                )
            }
            if (metrics.showQueuePosition && queuePosition.isNotBlank()) {
                Text(
                    text = queuePosition,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun BottomPlaybackPanel(
    state: NowPlayingState,
    metrics: NowPlayingLayoutMetrics,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    sleepTimerLabel: String?,
    upNextCount: Int,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(metrics.bottomPanelSpacing),
    ) {
        SeekBar(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            isSeekable = state.isSeekable,
            onSeek = onSeek,
        )
        if (sleepTimerLabel != null) {
            Text(
                text = sleepTimerLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PlaybackControlsRow(
            state = state,
            onToggleShuffle = onToggleShuffle,
            onPrevious = onPrevious,
            onTogglePlayPause = onTogglePlayPause,
            onNext = onNext,
            onCycleRepeatMode = onCycleRepeatMode,
        )
        QueueHandle(
            upNextCount = upNextCount,
            onClick = onOpenQueue,
        )
    }
}

@Composable
private fun PlaybackControlsRow(
    state: NowPlayingState,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeatMode: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleShuffle, modifier = Modifier.size(42.dp)) {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = if (state.shuffleEnabled) "Turn shuffle off" else "Turn shuffle on",
                tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onPrevious, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(30.dp),
            )
        }
        IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(58.dp)) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp),
            )
        }
        IconButton(onClick = onNext, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(30.dp),
            )
        }
        IconButton(onClick = onCycleRepeatMode, modifier = Modifier.size(42.dp)) {
            Icon(
                imageVector = if (state.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne
                              else Icons.Default.Repeat,
                contentDescription = when (state.repeatMode) {
                    RepeatMode.OFF -> "Turn repeat all on"
                    RepeatMode.ALL -> "Turn repeat one on"
                    RepeatMode.ONE -> "Turn repeat off"
                },
                tint = if (state.repeatMode == RepeatMode.OFF) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun LyricsEditorDialog(
    lyrics: LyricsResult,
    hasCustomLyrics: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Initialize once — no reactive key so an in-flight lyricsState update
    // cannot reset text the user is actively editing.
    var text by remember {
        mutableStateOf((lyrics as? LyricsResult.Available)?.text.orEmpty())
    }
    var showClearConfirm by remember { mutableStateOf(false) }
    val canSave = text.isNotBlank()

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Remove lyrics?") },
            text = { Text("Your custom lyrics for this track will be removed.") },
            confirmButton = {
                Button(onClick = onClear) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Keep editing") }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Edit lyrics") },
            text = {
                Column {
                    Text(
                        text = "Plain text only. Paste or type lyrics, then tap Save.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    )
                    Spacer(Modifier.height(12.dp))
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 320.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        placeholder = { Text("Lyrics") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Default,
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSave(text) },
                    enabled = canSave,
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasCustomLyrics) {
                        OutlinedButton(onClick = { showClearConfirm = true }) {
                            Text("Clear")
                        }
                        Spacer(Modifier.size(8.dp))
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            },
        )
    }
}

@Composable
private fun ArtworkWithLyricsOverlay(
    song: Song,
    lyrics: LyricsResult,
    showLyricsOverlay: Boolean,
    onToggleLyrics: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onEditLyrics: () -> Unit,
    compactLyricsOverlay: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context              = LocalContext.current
    val swipeThresholdPx     = with(LocalDensity.current) { 80.dp.toPx() }
    val currentToggleLyrics  by rememberUpdatedState(onToggleLyrics)
    val currentPrevious      by rememberUpdatedState(onPrevious)
    val currentNext          by rememberUpdatedState(onNext)
    val currentEdit          by rememberUpdatedState(onEditLyrics)
    val artworkShape         = LocalArtworkCornerStyle.current.toShape()
    val npBackground         = LocalNowPlayingBackground.current
    val effectiveArtworkUri  = if (npBackground == NowPlayingBackground.ARTWORK) {
        ArtworkResolver.albumArtworkUri(song.albumId)
    } else {
        null
    }

    Box(
        modifier = modifier
            .pointerInput(song.id) {
                detectTapGestures(
                    onDoubleTap = { currentToggleLyrics() },
                    onLongPress = { currentEdit() },
                )
            }
            .pointerInput(song.id, showLyricsOverlay, swipeThresholdPx) {
                if (showLyricsOverlay) return@pointerInput
                var dragDistance = Offset.Zero
                detectDragGestures(
                    onDragStart  = { dragDistance = Offset.Zero },
                    onDrag       = { change, dragAmount ->
                        dragDistance += dragAmount
                        val horizontal = abs(dragDistance.x)
                        val vertical   = abs(dragDistance.y)
                        if (horizontal >= swipeThresholdPx && horizontal > vertical * 1.25f) {
                            change.consume()
                        }
                    },
                    onDragEnd    = {
                        val horizontal = abs(dragDistance.x)
                        val vertical   = abs(dragDistance.y)
                        if (horizontal >= swipeThresholdPx && horizontal > vertical * 1.25f) {
                            if (dragDistance.x < 0f) currentNext() else currentPrevious()
                        }
                        dragDistance = Offset.Zero
                    },
                    onDragCancel = { dragDistance = Offset.Zero },
                )
            },
    ) {
        ArtworkImage(
            artworkUri = effectiveArtworkUri,
            contentDescription = "Album artwork for ${song.album}",
            placeholderIcon = Icons.Default.MusicNote,
            shape = artworkShape,
            modifier = Modifier.fillMaxSize(),
        )

        if (showLyricsOverlay) {
            val scrimAlpha = if (compactLyricsOverlay) 0.78f else 0.68f
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha)),
            ) {
                LyricsOverlayContent(
                    lyrics = lyrics,
                    onSearchOnline = { searchLyricsOnline(context, song) },
                    compact = compactLyricsOverlay,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = if (compactLyricsOverlay) 16.dp else 22.dp,
                            end = if (compactLyricsOverlay) 16.dp else 22.dp,
                            top = if (compactLyricsOverlay) 16.dp else 22.dp,
                            bottom = if (compactLyricsOverlay) 16.dp else 52.dp,
                        ),
                )
                if (!compactLyricsOverlay) {
                    Text(
                        text = "Double-tap to close · Long-press to edit",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.62f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsOverlayContent(
    lyrics: LyricsResult,
    onSearchOnline: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    when (lyrics) {
        LyricsResult.Loading -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        is LyricsResult.Available -> {
            Text(
                text = lyrics.text,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Start,
                modifier = modifier.verticalScroll(rememberScrollState()),
            )
        }
        LyricsResult.NotFound,
        is LyricsResult.Error -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.40f),
                        modifier = Modifier.size(if (compact) 28.dp else 36.dp),
                    )
                    Text(
                        text = "No lyrics found",
                        style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.88f),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = if (compact) {
                            "Long-press to add custom lyrics."
                        } else {
                            "Long-press to add custom lyrics, or add embedded/sidecar lyrics locally."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.50f),
                        textAlign = TextAlign.Center,
                    )
                    if (!compact) {
                        TextButton(onClick = onSearchOnline) {
                            Text(
                                text = "Search lyrics online",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.clickableIfNotNull(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) this else clickable(onClick = onClick)

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

private fun Song.hasKnownArtist(): Boolean =
    artist.hasKnownMetadata(unknownLabel = "Unknown Artist")

private fun Song.hasKnownAlbum(): Boolean =
    album.hasKnownMetadata(unknownLabel = "Unknown Album")

private fun Song.isExternalAudio(): Boolean =
    id == Long.MIN_VALUE && album == "External audio"

private fun String.hasKnownMetadata(unknownLabel: String): Boolean {
    val value = trim()
    return value.isNotBlank() &&
        !value.equals(unknownLabel, ignoreCase = true) &&
        !value.equals("<unknown>", ignoreCase = true)
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    isSeekable: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeDisplayMode = LocalNowPlayingTimeDisplayMode.current
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableStateOf(0L) }

    val safeDurationMs = durationMs.coerceAtLeast(0L)
    val displayPositionMs = if (isDragging) dragPositionMs else positionMs
    val safeDisplayPositionMs = displayPositionMs.coerceForDisplay(safeDurationMs)
    val remainingMs = if (safeDurationMs > 0) {
        (safeDurationMs - safeDisplayPositionMs).coerceAtLeast(0L)
    } else {
        0L
    }
    val sliderValue = if (safeDurationMs > 0) {
        (safeDisplayPositionMs.toFloat() / safeDurationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    var trackWidthPx by remember { mutableStateOf(0) }

    fun seekToOffset(x: Float) {
        if (trackWidthPx <= 0 || safeDurationMs <= 0) return
        val fraction = (x / trackWidthPx).coerceIn(0f, 1f)
        dragPositionMs = (fraction * safeDurationMs).toLong()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .onSizeChanged { trackWidthPx = it.width }
                .pointerInput(isSeekable, safeDurationMs, trackWidthPx) {
                    if (!isSeekable || safeDurationMs <= 0) return@pointerInput
                    detectTapGestures { offset ->
                        seekToOffset(offset.x)
                        onSeek(dragPositionMs)
                    }
                }
                .pointerInput(isSeekable, safeDurationMs, trackWidthPx) {
                    if (!isSeekable || safeDurationMs <= 0) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            seekToOffset(offset.x)
                        },
                        onDrag = { change, _ ->
                            seekToOffset(change.position.x)
                        },
                        onDragEnd = {
                            if (isDragging) onSeek(dragPositionMs)
                            isDragging = false
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                    )
                },
        ) {
            val activeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
            val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
            val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerY = size.height / 2f
                val activeEnd = size.width * sliderValue
                drawLine(
                    color = inactiveColor,
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = activeColor,
                    start = Offset(0f, centerY),
                    end = Offset(activeEnd, centerY),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                if (safeDurationMs > 0) {
                    drawCircle(
                        color = thumbColor,
                        radius = 5.dp.toPx(),
                        center = Offset(activeEnd, centerY),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatMs(safeDisplayPositionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            Text(
                text = when {
                    safeDurationMs <= 0 -> "--:--"
                    timeDisplayMode == NowPlayingTimeDisplayMode.REMAINING -> "-${formatMs(remainingMs)}"
                    else -> formatMs(safeDurationMs)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}

private fun Long.coerceForDisplay(durationMs: Long): Long =
    if (durationMs > 0L) coerceIn(0L, durationMs) else coerceAtLeast(0L)

private fun searchLyricsOnline(context: Context, song: Song) {
    val query = buildString {
        if (song.hasKnownArtist()) {
            append(song.artist)
            append(' ')
        }
        append(song.title)
        append(" lyrics")
    }
    val url = "https://www.google.com/search?q=${Uri.encode(query)}"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
    }
}

private fun searchArtistOnline(context: Context, song: Song) {
    val url = "https://www.google.com/search?q=${Uri.encode(song.artist)}"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun EmptyNowPlayingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No track playing. Choose a song from your library to start playback.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
            textAlign = TextAlign.Center,
        )
    }
}
