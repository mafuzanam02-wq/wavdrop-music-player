package com.launchpoint.wavdrop.ui.screen.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.launchpoint.wavdrop.ui.components.AddToPlaylistDialog
import com.launchpoint.wavdrop.ui.components.ArtworkImage
import com.launchpoint.wavdrop.ui.components.PrimaryDestination
import com.launchpoint.wavdrop.ui.components.PrimaryNavigationBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onNavigateBack: () -> Unit,
    onHomeClick: () -> Unit = {},
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
    val playlists         by viewModel.playlists.collectAsStateWithLifecycle()
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showLyricsOverlay by remember { mutableStateOf(false) }
    var showLyricsEditor  by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now Playing") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (state.song != null) {
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
                onEditLyrics      = { showLyricsEditor = true },
                onOpenTrackDetails = onOpenTrackDetails,
                onOpenAlbum        = onOpenAlbum,
                onOpenArtist       = onOpenArtist,
                onOpenFolder       = onOpenFolder,
                onOpenStatistics   = onOpenStatistics,
                modifier          = Modifier.padding(innerPadding),
            )
        }
    }

    if (showAddToPlaylist) {
        AddToPlaylistDialog(
            playlists        = playlists,
            onSelectPlaylist = { id ->
                viewModel.addToPlaylist(id)
                showAddToPlaylist = false
            },
            onCreateAndAdd   = { name ->
                viewModel.createPlaylistAndAdd(name)
                showAddToPlaylist = false
            },
            onDismiss        = { showAddToPlaylist = false },
        )
    }

    if (showLyricsEditor && state.song != null) {
        LyricsEditorDialog(
            lyrics = lyricsState,
            hasCustomLyrics = hasCustomLyrics,
            onSave = { text ->
                viewModel.saveCustomLyrics(text) {
                    showLyricsEditor = false
                    showLyricsOverlay = true
                }
            },
            onClear = {
                viewModel.clearCustomLyrics {
                    showLyricsEditor = false
                    showLyricsOverlay = true
                }
            },
            onDismiss = { showLyricsEditor = false },
        )
    }
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
    onOpenFolder: (String) -> Unit,
    onOpenStatistics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = state.song ?: return
    val queuePosition = if (state.currentIndex >= 0 && state.queue.isNotEmpty()) {
        "${state.currentIndex + 1} / ${state.queue.size}"
    } else {
        ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TrackInfoBlock(
            song               = song,
            queuePosition      = queuePosition,
            lyrics             = lyrics,
            showLyricsOverlay  = showLyricsOverlay,
            onToggleLyrics     = onToggleLyrics,
            onEditLyrics       = onEditLyrics,
            onOpenTrackDetails = { onOpenTrackDetails(song.id) },
            onOpenArtist       = if (song.hasKnownArtist()) {
                { onOpenArtist(ArtistGrouper.artistKey(song)) }
            } else {
                null
            },
            onOpenAlbum        = if (song.hasKnownAlbum()) {
                { onOpenAlbum(AlbumGrouper.albumKey(song)) }
            } else {
                null
            },
        )

        Spacer(Modifier.height(14.dp))
        NowPlayingActionRow(
            song = song,
            onToggleLyrics = onToggleLyrics,
            onOpenFolder = onOpenFolder,
            onOpenStatistics = onOpenStatistics,
        )

        Spacer(Modifier.height(16.dp))
        SeekBar(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            isSeekable = state.isSeekable,
            onSeek = onSeek,
        )

        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = if (state.shuffleEnabled) "Turn shuffle off" else "Turn shuffle on",
                    tint = if (state.shuffleEnabled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(72.dp)) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = onCycleRepeatMode) {
                Icon(
                    imageVector = if (state.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne
                                  else Icons.Default.Repeat,
                    contentDescription = when (state.repeatMode) {
                        RepeatMode.OFF -> "Turn repeat all on"
                        RepeatMode.ALL -> "Turn repeat one on"
                        RepeatMode.ONE -> "Turn repeat off"
                    },
                    tint = if (state.repeatMode == RepeatMode.OFF)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingActionRow(
    song: Song,
    onToggleLyrics: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenStatistics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val folderKey = song.validFolderKey()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NowPlayingActionChip(
            label = "Lyrics",
            onClick = onToggleLyrics,
        )
        if (folderKey != null) {
            NowPlayingActionChip(
                label = "Folder",
                onClick = { onOpenFolder(folderKey) },
            )
        }
        NowPlayingActionChip(
            label = "Stats",
            onClick = onOpenStatistics,
        )
    }
}

@Composable
private fun NowPlayingActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun LyricsEditorDialog(
    lyrics: LyricsResult,
    hasCustomLyrics: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(lyrics) {
        mutableStateOf((lyrics as? LyricsResult.Available)?.text.orEmpty())
    }
    val canSave = text.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit lyrics") },
        text = {
            Column {
                Text(
                    text = "Unsynced lyrics. Timing and karaoke highlighting are not supported yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text("Lyrics") },
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
                    OutlinedButton(onClick = onClear) {
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

@Composable
private fun TrackInfoBlock(
    song: Song,
    queuePosition: String,
    lyrics: LyricsResult,
    showLyricsOverlay: Boolean,
    onToggleLyrics: () -> Unit,
    onEditLyrics: () -> Unit,
    onOpenTrackDetails: () -> Unit,
    onOpenArtist: (() -> Unit)?,
    onOpenAlbum: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier            = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtworkWithLyricsOverlay(
            song = song,
            lyrics = lyrics,
            showLyricsOverlay = showLyricsOverlay,
            onToggleLyrics = onToggleLyrics,
            onEditLyrics = onEditLyrics,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .aspectRatio(1f),
        )
        if (!showLyricsOverlay) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Double-tap for lyrics · Long-press to edit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(24.dp))
        }
        Text(
            text       = song.title,
            style      = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color      = MaterialTheme.colorScheme.onSurface,
            textAlign  = TextAlign.Center,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenTrackDetails),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text     = song.artist,
            style    = MaterialTheme.typography.titleMedium,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickableIfNotNull(onOpenArtist),
        )
        if (song.album.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text      = song.album,
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                textAlign = TextAlign.Center,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
                modifier  = Modifier
                    .fillMaxWidth()
                    .clickableIfNotNull(onOpenAlbum),
            )
        }
        if (queuePosition.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text  = queuePosition,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ArtworkWithLyricsOverlay(
    song: Song,
    lyrics: LyricsResult,
    showLyricsOverlay: Boolean,
    onToggleLyrics: () -> Unit,
    onEditLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.pointerInput(showLyricsOverlay, song.id) {
            detectTapGestures(
                onDoubleTap = { onToggleLyrics() },
                onLongPress = { onEditLyrics() },
            )
        },
    ) {
        ArtworkImage(
            artworkUri = ArtworkResolver.albumArtworkUri(song.albumId),
            contentDescription = "Album artwork for ${song.album}",
            placeholderIcon = Icons.Default.MusicNote,
            modifier = Modifier.fillMaxSize(),
        )

        if (showLyricsOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.58f)),
            ) {
                LyricsOverlayContent(
                    lyrics = lyrics,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 52.dp),
                )
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

@Composable
private fun LyricsOverlayContent(
    lyrics: LyricsResult,
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
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Start,
                modifier = modifier.verticalScroll(rememberScrollState()),
            )
        }
        LyricsResult.NotFound,
        is LyricsResult.Error -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = "No lyrics found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.88f),
                    textAlign = TextAlign.Center,
                )
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
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableStateOf(0L) }

    val displayPositionMs = if (isDragging) dragPositionMs else positionMs
    val sliderValue = if (durationMs > 0) {
        (displayPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    var trackWidthPx by remember { mutableStateOf(0) }

    fun seekToOffset(x: Float) {
        if (trackWidthPx <= 0 || durationMs <= 0) return
        val fraction = (x / trackWidthPx).coerceIn(0f, 1f)
        dragPositionMs = (fraction * durationMs).toLong()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .onSizeChanged { trackWidthPx = it.width }
                .pointerInput(isSeekable, durationMs, trackWidthPx) {
                    if (!isSeekable || durationMs <= 0) return@pointerInput
                    detectTapGestures { offset ->
                        seekToOffset(offset.x)
                        onSeek(dragPositionMs)
                    }
                }
                .pointerInput(isSeekable, durationMs, trackWidthPx) {
                    if (!isSeekable || durationMs <= 0) return@pointerInput
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
                if (durationMs > 0) {
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
                text = formatMs(displayPositionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            Text(
                text = if (durationMs > 0) "-${formatMs(durationMs - displayPositionMs)}" else "--:--",
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

@Composable
private fun EmptyNowPlayingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No track playing",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
            textAlign = TextAlign.Center,
        )
    }
}
