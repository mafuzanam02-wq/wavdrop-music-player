package com.launchpoint.wavdrop.ui.screen.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import com.launchpoint.wavdrop.data.artwork.ArtworkResolver
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.NowPlayingState
import com.launchpoint.wavdrop.ui.components.ArtworkImage
import com.launchpoint.wavdrop.ui.components.LocalArtworkCornerStyle
import com.launchpoint.wavdrop.ui.components.LocalCompactMode
import com.launchpoint.wavdrop.ui.components.shareSong
import com.launchpoint.wavdrop.ui.components.toShape
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    state: NowPlayingState,
    onDismiss: () -> Unit,
    onJumpToItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onMoveItemTo: (Int, Int) -> Unit,
    onPlayNext: (Int) -> Unit,
    onPlaySongNext: (Song) -> Unit,
    onAddSongToQueue: (Song) -> Unit,
    onViewStats: (Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        QueueSheetContent(
            state = state,
            onDismiss = onDismiss,
            onJumpToItem = onJumpToItem,
            onRemoveItem = onRemoveItem,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onMoveItemTo = onMoveItemTo,
            onPlayNext = onPlayNext,
            onPlaySongNext = onPlaySongNext,
            onAddSongToQueue = onAddSongToQueue,
            onViewStats = onViewStats,
        )
    }
}

@Composable
private fun QueueSheetContent(
    state: NowPlayingState,
    onDismiss: () -> Unit,
    onJumpToItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onMoveItemTo: (Int, Int) -> Unit,
    onPlayNext: (Int) -> Unit,
    onPlaySongNext: (Song) -> Unit,
    onAddSongToQueue: (Song) -> Unit,
    onViewStats: (Long) -> Unit,
) {
    val currentIndex = state.currentIndex
    val previousSongs = if (currentIndex > 0) state.queue.take(currentIndex) else emptyList()
    val currentSong = state.queue.getOrNull(currentIndex)
    val upNextSongs = if (currentIndex >= 0) state.queue.drop(currentIndex + 1) else emptyList()

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val autoScrollScope = rememberCoroutineScope()
    val context = LocalContext.current
    val onShareSong: (Song) -> Unit = { song ->
        shareSong(context, song) {
            scope.launch { snackbarHostState.showSnackbar("Could not share this track") }
        }
    }

    var draggingPlaybackIndex  by remember { mutableStateOf<Int?>(null) }
    var draggingSongId         by remember { mutableStateOf<Long?>(null) }
    var dragStartPlaybackIndex by remember { mutableStateOf(0) }
    var dragTargetPlaybackIndex by remember { mutableStateOf<Int?>(null) }
    var pointerViewportY       by remember { mutableStateOf<Float?>(null) }
    var isDragActive           by remember { mutableStateOf(false) }
    var autoScrollJob          by remember { mutableStateOf<Job?>(null) }
    val anyDragging             = isDragActive && draggingPlaybackIndex != null && pointerViewportY != null
    val compact                 = LocalCompactMode.current
    val density                 = LocalDensity.current
    val rowHeightPx             = with(density) { if (compact) 56.dp.toPx() else 64.dp.toPx() }

    fun stopAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    fun clearDragState() {
        stopAutoScroll()
        isDragActive           = false
        draggingPlaybackIndex  = null
        draggingSongId         = null
        dragStartPlaybackIndex = 0
        dragTargetPlaybackIndex = null
        pointerViewportY       = null
    }

    fun updateDragTarget(pointerY: Float) {
        val upNextItems = listState.layoutInfo.visibleItemsInfo
            .mapNotNull { item ->
                val key = item.key as? String ?: return@mapNotNull null
                if (!key.startsWith("up-next-")) return@mapNotNull null
                val playbackIndex = key.substringAfterLast("-").toIntOrNull() ?: return@mapNotNull null
                item to playbackIndex
            }
            .sortedBy { it.first.offset }
        if (upNextItems.isEmpty()) return

        val target = upNextItems.firstOrNull { (item, _) ->
            pointerY < item.offset + item.size / 2f
        }?.second ?: upNextItems.last().second
        val firstIdx = currentIndex + 1
        val lastIdx = currentIndex + upNextSongs.size
        dragTargetPlaybackIndex = target.coerceIn(firstIdx, lastIdx)
    }

    suspend fun runAutoScrollFrame(): Boolean {
        val playbackIndex = draggingPlaybackIndex
        val songId = draggingSongId
        val pointerY = pointerViewportY
        if (!isDragActive || playbackIndex == null || songId == null || pointerY == null) return false
        if (state.queue.getOrNull(playbackIndex)?.id != songId) {
            clearDragState()
            return false
        }

        val edgePx      = with(density) { 80.dp.toPx() }
        val scrollSpeed = with(density) { 8.dp.toPx() }
        val viewport = listState.layoutInfo.viewportSize.height.toFloat()
        val scrollAmount = if (viewport > 0f) {
            when {
                pointerY < edgePx            -> -scrollSpeed
                pointerY > viewport - edgePx ->  scrollSpeed
                else                         ->  0f
            }
        } else {
            0f
        }

        if (scrollAmount != 0f && isDragActive) {
            listState.scrollBy(scrollAmount)
            if (isDragActive) {
                updateDragTarget(pointerY)
            }
        }
        return true
    }

    fun startAutoScroll() {
        stopAutoScroll()
        autoScrollJob = autoScrollScope.launch {
            while (isActive) {
                if (!runAutoScrollFrame()) break
                delay(16L)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            clearDragState()
        }
    }

    fun showRemovedSnackbar() {
        scope.launch {
            snackbarHostState.showSnackbar("Removed from queue")
        }
    }

    // Scroll to the "Playing now" section header on open and when the current track changes.
    // LazyColumn item layout (0-based):
    //   0           "Previously played" header   } only when currentIndex > 0
    //   1..ci       previous song rows           }
    //   ci+1 or 0   "Playing now" header         <- scroll target
    //   ci+2 or 1   QueueNowPlayingRow
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            val target = if (currentIndex > 0) currentIndex + 1 else 0
            listState.scrollToItem(target)
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            QueueSheetHeader(onDismiss = onDismiss)
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                ) {
        // ── Previously played ─────────────────────────────────────────────────
        if (previousSongs.isNotEmpty()) {
            item {
                QueueSectionHeader(label = "Previously played · ${previousSongs.size}")
            }
            itemsIndexed(
                items = previousSongs,
                // key = absolute queue index (0..currentIndex-1), always unique
                key = { index, song -> "previous-${song.id}-$index" },
            ) { index, song ->
                QueuePreviousItemRow(
                    song = song,
                    onJump = { onJumpToItem(index) },
                    onPlayNext = { onPlaySongNext(song) },
                    onAddToQueue = { onAddSongToQueue(song) },
                    onViewStats = { onViewStats(song.id) },
                    onShare = { onShareSong(song) },
                )
                if (index < previousSongs.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }

        // ── Playing now ───────────────────────────────────────────────────────
        if (currentSong != null) {
            item {
                QueueSectionHeader(
                    label = "Playing now",
                    modifier = Modifier.padding(top = if (previousSongs.isNotEmpty()) 8.dp else 0.dp),
                )
            }
            item {
                QueueNowPlayingRow(song = currentSong)
            }
        }

        // ── Up next ───────────────────────────────────────────────────────────
        if (upNextSongs.isNotEmpty()) {
            item {
                QueueSectionHeader(
                    label = "Up next · ${upNextSongs.size}",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            itemsIndexed(
                items = upNextSongs,
                // key = absolute queue index (currentIndex+1..), always unique
                key = { index, song -> "up-next-${song.id}-${currentIndex + 1 + index}" },
            ) { index, song ->
                val playbackIndex = currentIndex + 1 + index
                val isFirstUpNext = index == 0
                val isLastUpNext = index == upNextSongs.lastIndex
                val isDragging = draggingPlaybackIndex == playbackIndex
                SwipeableQueueItemRow(
                    song = song,
                    isFirstUpNext = isFirstUpNext,
                    isLastUpNext = isLastUpNext,
                    isDragging = isDragging,
                    isDimmed = anyDragging && !isDragging,
                    onJump = { onJumpToItem(playbackIndex) },
                    onMoveUp = { onMoveUp(playbackIndex) },
                    onMoveDown = { onMoveDown(playbackIndex) },
                    onPlayNext = { onPlayNext(playbackIndex) },
                    onRemove = {
                        onRemoveItem(playbackIndex)
                        showRemovedSnackbar()
                    },
                    onViewStats = { onViewStats(song.id) },
                    onShare = { onShareSong(song) },
                    onDragStart = {
                        draggingPlaybackIndex  = playbackIndex
                        draggingSongId         = song.id
                        dragStartPlaybackIndex = playbackIndex
                        dragTargetPlaybackIndex = playbackIndex
                        val key = "up-next-${song.id}-$playbackIndex"
                        val itemOffset = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == key }?.offset ?: 0
                        val viewport = listState.layoutInfo.viewportSize.height.toFloat()
                        val startPointerY = itemOffset.toFloat() + rowHeightPx / 2
                        pointerViewportY = if (viewport > 0f) {
                            startPointerY.coerceIn(0f, viewport)
                        } else {
                            startPointerY
                        }
                        updateDragTarget(pointerViewportY ?: startPointerY)
                        isDragActive = true
                        startAutoScroll()
                    },
                    onDragDelta = onDragDelta@ { dy ->
                        if (!isDragActive) return@onDragDelta
                        val pointerY = pointerViewportY ?: return@onDragDelta
                        val viewport = listState.layoutInfo.viewportSize.height.toFloat()
                        val nextPointerY = if (viewport > 0f) {
                            (pointerY + dy).coerceIn(0f, viewport)
                        } else {
                            pointerY + dy
                        }
                        pointerViewportY = nextPointerY
                        updateDragTarget(nextPointerY)
                    },
                    onDragEnd   = {
                        stopAutoScroll()
                        isDragActive = false
                        val from             = dragStartPlaybackIndex
                        val firstIdx         = currentIndex + 1
                        val fromLocalIdx     = from - firstIdx
                        val to = dragTargetPlaybackIndex
                        val draggedStillExists = draggingSongId != null &&
                            upNextSongs.getOrNull(fromLocalIdx)?.id == draggingSongId
                        if (draggedStillExists && fromLocalIdx >= 0 && to != null && to != from) {
                            onMoveItemTo(from, to)
                        }
                        clearDragState()
                    },
                    onDragCancel = {
                        stopAutoScroll()
                        isDragActive = false
                        val from         = dragStartPlaybackIndex
                        val firstIdx     = currentIndex + 1
                        val fromLocalIdx = from - firstIdx
                        val to = dragTargetPlaybackIndex
                        val draggedStillExists = draggingSongId != null &&
                            upNextSongs.getOrNull(fromLocalIdx)?.id == draggingSongId
                        if (draggedStillExists && fromLocalIdx >= 0 && to != null && to != from) {
                            onMoveItemTo(from, to)
                        }
                        clearDragState()
                    },
                )
                if (!isLastUpNext) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        } else if (currentSong != null) {
            item {
                Text(
                    text = "Nothing up next",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
                }
                val previewSong = draggingPlaybackIndex?.let { state.queue.getOrNull(it) }
                val previewY = pointerViewportY
                if (anyDragging && previewSong != null && previewY != null) {
                    QueueItemRow(
                        song = previewSong,
                        isFirstUpNext = false,
                        isLastUpNext = false,
                        isDragging = true,
                        isDimmed = false,
                        dragHandleEnabled = false,
                        onJump = {},
                        onMoveUp = {},
                        onMoveDown = {},
                        onPlayNext = {},
                        onRemove = {},
                        onViewStats = {},
                        onShare = {},
                        onDragStart = {},
                        onDragDelta = {},
                        onDragEnd = {},
                        onDragCancel = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = (previewY - rowHeightPx / 2f).roundToInt(),
                                )
                            }
                            .zIndex(1f),
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

// ── Section header ────────────────────────────────────────────────────────────

@Composable
private fun QueueSheetHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Queue",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close queue",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            )
        }
    }
}

@Composable
private fun QueueSectionHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

// ── Playing-now row (highlighted) ─────────────────────────────────────────────

@Composable
private fun QueueNowPlayingRow(song: Song) {
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 8.dp else 12.dp
    val artworkSize = if (compact) 40.dp else 44.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(start = 16.dp, end = 4.dp, top = verticalPadding, bottom = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(40.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        ArtworkImage(
            artworkUri = ArtworkResolver.albumArtworkUri(song.albumId),
            contentDescription = "Album artwork for ${song.album}",
            placeholderIcon = Icons.Default.MusicNote,
            modifier = Modifier
                .padding(start = 12.dp)
                .size(artworkSize),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(16.dp),
                )
            }
            Text(
                text = song.displayArtist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// ── Previously-played row (dimmed, limited actions) ───────────────────────────

@Composable
private fun QueuePreviousItemRow(
    song: Song,
    onJump: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onViewStats: () -> Unit,
    onShare: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 8.dp else 10.dp
    val artworkSize = if (compact) 40.dp else 44.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onJump)
            .padding(start = 12.dp, end = 4.dp, top = verticalPadding, bottom = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Spacer matches the 20 dp drag-handle icon width in QueueItemRow for visual alignment
        Spacer(Modifier.size(20.dp))
        ArtworkImage(
            artworkUri = ArtworkResolver.albumArtworkUri(song.albumId),
            contentDescription = "Album artwork for ${song.album}",
            placeholderIcon = Icons.Default.MusicNote,
            modifier = Modifier
                .padding(start = 12.dp)
                .size(artworkSize),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = song.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.displayArtist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Previous item actions",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Play") },
                    onClick = { expanded = false; onJump() },
                )
                DropdownMenuItem(
                    text = { Text("Play next") },
                    onClick = { expanded = false; onPlayNext() },
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    onClick = { expanded = false; onAddToQueue() },
                )
                DropdownMenuItem(
                    text = { Text("View stats") },
                    onClick = { expanded = false; onViewStats() },
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = { expanded = false; onShare() },
                )
            }
        }
    }
}

// ── Up-next row (full actions) ────────────────────────────────────────────────

@Composable
private fun SwipeableQueueItemRow(
    song: Song,
    isFirstUpNext: Boolean,
    isLastUpNext: Boolean,
    isDragging: Boolean,
    isDimmed: Boolean,
    onJump: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onPlayNext: () -> Unit,
    onRemove: () -> Unit,
    onViewStats: () -> Unit,
    onShare: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onRemove()
                false
            } else {
                true
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.alpha(
            when {
                isDragging -> 0f
                isDimmed -> 0.65f
                else -> 1f
            },
        ),
        gesturesEnabled = !(isDragging || isDimmed),
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "Remove",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        QueueItemRow(
            song = song,
            isFirstUpNext = isFirstUpNext,
            isLastUpNext = isLastUpNext,
            isDragging = isDragging,
            isDimmed = isDimmed,
            dragHandleEnabled = true,
            onJump = onJump,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onPlayNext = onPlayNext,
            onRemove = onRemove,
            onViewStats = onViewStats,
            onShare = onShare,
            onDragStart = onDragStart,
            onDragDelta = onDragDelta,
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel,
        )
    }
}

@Composable
private fun QueueItemRow(
    song: Song,
    isFirstUpNext: Boolean,
    isLastUpNext: Boolean,
    isDragging: Boolean,
    isDimmed: Boolean,
    dragHandleEnabled: Boolean,
    onJump: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onPlayNext: () -> Unit,
    onRemove: () -> Unit,
    onViewStats: () -> Unit,
    onShare: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 8.dp else 10.dp
    val artworkSize = if (compact) 40.dp else 44.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (isDragging) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(enabled = !(isDragging || isDimmed), onClick = onJump)
            .padding(start = 12.dp, end = 4.dp, top = verticalPadding, bottom = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(artworkSize)
                .then(
                    if (dragHandleEnabled) {
                        Modifier.pointerInput(song.id) {
                            awaitEachGesture {
                                var dragStarted = false
                                var dropCommitted = false
                                try {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val touchSlopChange = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                                        change.consume()
                                        onDragStart()
                                        dragStarted = true
                                        onDragDelta(overSlop)
                                    }
                                    if (touchSlopChange != null) {
                                        verticalDrag(touchSlopChange.id) { change ->
                                            onDragDelta(change.positionChange().y)
                                            change.consume()
                                        }
                                        if (dragStarted) {
                                            onDragEnd()
                                            dropCommitted = true
                                        }
                                    }
                                } finally {
                                    if (dragStarted && !dropCommitted) {
                                        onDragCancel()
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Default.DragHandle,
                contentDescription = "Drag to reorder",
                tint               = if (isDragging) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                modifier           = Modifier.size(16.dp),
            )
        }
        ArtworkImage(
            artworkUri = ArtworkResolver.albumArtworkUri(song.albumId),
            contentDescription = "Album artwork for ${song.album}",
            placeholderIcon = Icons.Default.MusicNote,
            modifier = Modifier
                .padding(start = 12.dp)
                .size(artworkSize),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = song.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.displayArtist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Queue item actions",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (!isFirstUpNext) {
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        onClick = { expanded = false; onMoveUp() },
                    )
                }
                if (!isLastUpNext) {
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        onClick = { expanded = false; onMoveDown() },
                    )
                }
                if (!isFirstUpNext) {
                    DropdownMenuItem(
                        text = { Text("Play next") },
                        onClick = { expanded = false; onPlayNext() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Remove from queue") },
                    onClick = { expanded = false; onRemove() },
                )
                DropdownMenuItem(
                    text = { Text("View stats") },
                    onClick = { expanded = false; onViewStats() },
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = { expanded = false; onShare() },
                )
            }
        }
    }
}

// ── Handle shown on Now Playing screen ───────────────────────────────────────

@Composable
fun QueueHandle(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = "Open queue",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
