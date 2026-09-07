package com.launchpoint.wavdrop.ui.screen.nowplaying

import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import kotlin.math.abs
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
    val previousCount = if (currentIndex > 0) {
        currentIndex.coerceAtMost(state.queue.size)
    } else {
        0
    }
    val currentSong = state.queue.getOrNull(currentIndex)
    val upNextStartIndex = if (currentIndex >= 0) {
        (currentIndex + 1).coerceAtMost(state.queue.size)
    } else {
        state.queue.size
    }
    val upNextCount = state.queue.size - upNextStartIndex

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

    // Stable identity of the grabbed occurrence for the whole gesture (song id + occurrence ordinal),
    // plus the immutable grabbed Song for the floating preview. The live source playback index is
    // re-resolved from the session against the current queue — never assumed fixed.
    // Always-fresh view of `state` for use inside gesture lambdas, whose enclosing pointerInput does
    // not restart on recomposition and would otherwise capture a stale `state` snapshot.
    val latestState            = rememberUpdatedState(state)
    var dragSession            by remember { mutableStateOf<QueueDragSession?>(null) }
    var draggingSong           by remember { mutableStateOf<Song?>(null) }
    var dragTargetPlaybackIndex by remember { mutableStateOf<Int?>(null) }
    var pointerViewportY       by remember { mutableStateOf<Float?>(null) }
    var isDragActive           by remember { mutableStateOf(false) }
    var autoScrollJob          by remember { mutableStateOf<Job?>(null) }
    // Ephemeral UI-only registry of the bounds of every currently-composed Up Next drag handle,
    // keyed by the LazyColumn item key (stable per composed row). The stable parent-level pointer
    // detector hit-tests pointer-down against these to decide whether a reorder may start. Handles
    // register/update via onGloballyPositioned and unregister on disposal, so the registry always
    // reflects only visible rows — but a drag that has already started does NOT depend on it.
    val handleRegistry         = remember { mutableStateMapOf<String, QueueDragHandleTarget>() }
    // LayoutCoordinates of the queue viewport Box: the single coordinate space shared by the parent
    // pointerInput's pointer positions and every registered handle's bounds.
    var viewportCoords         by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // "Move to…" dialog identity. Reuses the drag session shape (song id + occurrence ordinal + start
    // index) so the invoked occurrence is re-resolved against the live queue at execute time — the
    // exact same duplicate-safe path as drag. Null when the dialog is closed.
    var moveDialogSession      by remember { mutableStateOf<QueueDragSession?>(null) }
    // Live playback index of the grabbed occurrence in the current queue (null once invalidated).
    val draggingPlaybackIndex   = dragSession?.let { resolveDraggedOccurrenceIndex(state.queue, it) }
    val anyDragging             = isDragActive && draggingPlaybackIndex != null && pointerViewportY != null
    val compact                 = LocalCompactMode.current
    val density                 = LocalDensity.current
    val rowHeightPx             = with(density) { if (compact) 56.dp.toPx() else 64.dp.toPx() }
    val haptics                 = LocalHapticFeedback.current
    // Honour the OS "remove animations" accessibility flag (same idiom as WrappedScreen): disable
    // non-essential scale/spring/placement motion while keeping the insertion marker and position
    // badge, which convey function rather than decoration.
    val reduceMotion            = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) < 0.1f
    }
    // Progressive edge-scroll tuning (Phase B). Velocity is computed in px/s by a pure helper and
    // converted to a per-frame delta using measured elapsed time, so it is frame-rate independent.
    val edgeZonePx              = with(density) { 96.dp.toPx() }
    val minEdgeSpeedPxPerSec    = with(density) { 220.dp.toPx() }
    val maxEdgeSpeedPxPerSec    = with(density) { 2200.dp.toPx() }
    // Enlarged drag-handle hit width (from the row's left edge). The visible icon stays small; only
    // the registered hit region grows, and it never reaches the artwork so artwork scroll is intact.
    val handleHitWidthPx        = with(density) { 44.dp.toPx() }

    fun stopAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    fun clearDragState() {
        stopAutoScroll()
        isDragActive            = false
        dragSession             = null
        draggingSong            = null
        dragTargetPlaybackIndex = null
        pointerViewportY        = null
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
        val firstIdx = upNextStartIndex
        val lastIdx = upNextStartIndex + upNextCount - 1
        dragTargetPlaybackIndex = target.coerceIn(firstIdx, lastIdx)
    }

    suspend fun runAutoScrollFrame(dtSeconds: Float): Boolean {
        // Reads only stable MutableState (isDragActive/dragSession/pointerViewportY), never the
        // captured `state` param which is stale inside this long-lived coroutine. Structural
        // invalidation (overtake / queue mutation) is handled reactively below, not here.
        val pointerY = pointerViewportY
        if (!isDragActive || dragSession == null || pointerY == null) return false

        val viewport = listState.layoutInfo.viewportSize.height.toFloat()
        // Progressive px/s velocity → per-frame px using real elapsed time (frame-rate independent).
        val velocityPxPerSec = edgeScrollVelocityPxPerSec(
            pointerY = pointerY,
            viewportHeightPx = viewport,
            edgeZonePx = edgeZonePx,
            minSpeedPxPerSec = minEdgeSpeedPxPerSec,
            maxSpeedPxPerSec = maxEdgeSpeedPxPerSec,
        )

        if (velocityPxPerSec != 0f && isDragActive) {
            listState.scrollBy(velocityPxPerSec * dtSeconds)
            if (isDragActive) {
                updateDragTarget(pointerY)
            }
        }
        return true
    }

    fun startAutoScroll() {
        stopAutoScroll()
        autoScrollJob = autoScrollScope.launch {
            var lastFrameNanos = System.nanoTime()
            while (isActive) {
                delay(16L)
                val now = System.nanoTime()
                // Clamp dt so a scheduler stall (or a returning-from-background pause) cannot produce
                // one giant scroll jump.
                val dtSeconds = ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                lastFrameNanos = now
                if (!runAutoScrollFrame(dtSeconds)) break
            }
        }
    }

    // ── Drag lifecycle, owned by the stable parent pointerInput (not by any LazyColumn row) ──────
    // These read the FRESH queue via latestState (the pointerInput(Unit) lambda is captured once and
    // never restarts, so the `state` param it closes over would be stale). They mutate the remembered
    // MutableStates above, whose identities are stable across recomposition.

    // [pointerY] is the actual pointer-down Y at slop crossing, in viewport-Box local space — no
    // synthesised row-centre offset, so the floating preview sits under the finger without a jump.
    fun beginDrag(handle: QueueDragHandleTarget, pointerY: Float) {
        val liveQueue = latestState.value.queue
        val playbackIndex = handle.playbackIndex
        val song = liveQueue.getOrNull(playbackIndex) ?: return
        dragSession = QueueDragSession(
            sourceSongId = handle.songId,
            sourceOccurrenceOrdinal = queueOccurrenceOrdinal(liveQueue, playbackIndex),
            startSourcePlaybackIndex = playbackIndex,
        )
        draggingSong = song
        dragTargetPlaybackIndex = playbackIndex
        val viewport = listState.layoutInfo.viewportSize.height.toFloat()
        pointerViewportY = if (viewport > 0f) pointerY.coerceIn(0f, viewport) else pointerY
        updateDragTarget(pointerViewportY ?: pointerY)
        isDragActive = true
        // The single pickup haptic — fired only here, at vertical-slop crossing. Never on down, on
        // row crossings, on target updates, or during auto-scroll.
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        startAutoScroll()
    }

    fun applyDragDelta(dy: Float) {
        if (!isDragActive) return
        val pointerY = pointerViewportY ?: return
        val viewport = listState.layoutInfo.viewportSize.height.toFloat()
        val nextPointerY = if (viewport > 0f) {
            (pointerY + dy).coerceIn(0f, viewport)
        } else {
            pointerY + dy
        }
        pointerViewportY = nextPointerY
        updateDragTarget(nextPointerY)
    }

    fun endDrag() {
        stopAutoScroll()
        isDragActive = false
        val session = dragSession
        // The single, final commit point — reached only for a valid, non-cancelled drop.
        // planQueueDragEnd re-resolves the grabbed occurrence against the live queue and enforces the
        // Up Next / overtake invariants, so it is the ONLY path that calls onMoveItemTo.
        if (session != null) {
            val liveState = latestState.value
            val decision = planQueueDragEnd(
                queue = liveState.queue,
                currentIndex = liveState.currentIndex,
                session = session,
                targetPlaybackIndex = dragTargetPlaybackIndex,
                cancelled = false,
            )
            if (decision is QueueDragEndDecision.Commit) {
                onMoveItemTo(decision.fromPlaybackIndex, decision.toPlaybackIndex)
            }
        }
        clearDragState()
    }

    // Cancel means cancel: never commit a reorder. Just tear down drag state.
    fun cancelDrag() {
        clearDragState()
    }

    // ── "Move to…" execution (Phase C) ──────────────────────────────────────────────────────────
    // Both paths re-resolve the invoked occurrence against the FRESH queue and route through the same
    // authoritative move as drag. They return true only when a genuine move is committed (so the
    // caller can show feedback), and reject rather than move the wrong item if the queue changed.

    fun runMoveToTarget(session: QueueDragSession, target: Int?): Boolean {
        if (target == null) return false
        val live = latestState.value
        // planQueueDragEnd is the single source of truth: it re-resolves the occurrence, enforces
        // source/target strictly in Up Next, and NoOps when target == source or is invalid.
        val decision = planQueueDragEnd(
            queue = live.queue,
            currentIndex = live.currentIndex,
            session = session,
            targetPlaybackIndex = target,
            cancelled = false,
        )
        return if (decision is QueueDragEndDecision.Commit) {
            onMoveItemTo(decision.fromPlaybackIndex, decision.toPlaybackIndex)
            true
        } else {
            false
        }
    }

    fun runMovePlayNext(session: QueueDragSession): Boolean {
        val live = latestState.value
        val source = resolveDraggedOccurrenceIndex(live.queue, session) ?: return false
        if (source <= live.currentIndex) return false
        if (source == live.currentIndex + 1) return false // already immediate next → NoOp
        onPlayNext(source) // reuses PlayerController.moveToPlayNext
        return true
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

    fun showMovedSnackbar() {
        scope.launch {
            snackbarHostState.showSnackbar("Moved in queue")
        }
    }

    // Scroll to the "Playing now" section header on open and when the current track changes.
    // LazyColumn item layout (0-based):
    //   0           "Previously played" header   } only when currentIndex > 0
    //   1..ci       previous song rows           }
    //   ci+1 or 0   "Playing now" header         <- scroll target
    //   ci+2 or 1   QueueNowPlayingRow
    LaunchedEffect(currentIndex) {
        // Keyed on currentIndex only: a current-track change during a drag is suppressed (does not
        // yank the viewport), and because the key does not include isDragActive there is no forced
        // catch-up scroll when the drag later ends — the user's viewport is preserved.
        if (shouldAutoScrollToPlayingNow(currentIndex, isDragActive)) {
            val target = if (currentIndex > 0) currentIndex + 1 else 0
            listState.scrollToItem(target)
        }
    }

    // Reactive mid-drag safety: if playback overtakes the grabbed occurrence or a queue mutation
    // removes/invalidates it, cancel the drag immediately (stops auto-scroll, no commit). Reads the
    // FRESH `state` (the auto-scroll coroutine cannot, since it captures a stale snapshot).
    LaunchedEffect(state.queue, currentIndex, isDragActive) {
        val session = dragSession
        if (isDragActive && session != null &&
            !isDraggedOccurrenceStillValid(state.queue, currentIndex, session)
        ) {
            clearDragState()
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
                    .weight(1f, fill = false)
                    // Stable parent = the queue viewport Box. It defines the shared coordinate space
                    // (pointer positions here == handle bounds via localBoundingBoxOf == pointerViewportY
                    // == floating-preview offset) and OWNS the whole drag gesture for the sheet's
                    // lifetime, so LazyColumn virtualising the source row cannot cancel it.
                    .onGloballyPositioned { viewportCoords = it }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            // Observe in the Initial pass so the parent can claim a handle-drag before
                            // the child LazyColumn's scrollable (Main pass) consumes it.
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            // Only a pointer-down on a registered Up Next handle is eligible. Anything
                            // else (rows, artwork, headers) is left untouched → normal scroll/tap/swipe.
                            val handle = findDragHandleAt(handleRegistry.values, down.position)
                                ?: return@awaitEachGesture
                            val touchSlop = viewConfiguration.touchSlop
                            var totalDx = 0f
                            var totalDy = 0f
                            var dragStarted = false
                            try {
                                // Slop phase: distinguish vertical reorder from horizontal swipe / tap.
                                // Nothing is consumed until vertical slop is crossed, so a horizontal
                                // swipe-to-remove or a tap still reaches the child untouched.
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: return@awaitEachGesture
                                    if (!change.pressed) return@awaitEachGesture // released before slop → tap
                                    val delta = change.positionChange()
                                    totalDx += delta.x
                                    totalDy += delta.y
                                    if (abs(totalDx) > touchSlop && abs(totalDx) >= abs(totalDy)) {
                                        // Horizontal intent → let swipe-to-remove / child handle it.
                                        return@awaitEachGesture
                                    }
                                    if (abs(totalDy) > touchSlop) {
                                        change.consume()
                                        beginDrag(handle, change.position.y)
                                        dragStarted = true
                                        break
                                    }
                                }
                                // Drag phase: the parent consumes every move so the list does not fight
                                // the gesture. This loop survives the source row leaving composition —
                                // its lifetime is the parent Box's, not the disposed row's.
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null) {
                                        cancelDrag()
                                        dragStarted = false
                                        return@awaitEachGesture
                                    }
                                    if (!change.pressed) {
                                        change.consume()
                                        endDrag()
                                        dragStarted = false
                                        return@awaitEachGesture
                                    }
                                    val dy = change.positionChange().y
                                    if (dy != 0f) applyDragDelta(dy)
                                    change.consume()
                                }
                            } finally {
                                // Safety net: if the coroutine is cancelled mid-drag (e.g. the whole
                                // sheet is dismissed), tear down without committing.
                                if (dragStarted) cancelDrag()
                            }
                        }
                    },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                ) {
        // ── Previously played ─────────────────────────────────────────────────
        if (previousCount > 0) {
            item {
                QueueSectionHeader(label = "Previously played · $previousCount")
            }
            items(
                count = previousCount,
                // key = absolute queue index (0..currentIndex-1), always unique
                key = { index -> "previous-${state.queue[index].id}-$index" },
            ) { index ->
                val song = state.queue[index]
                QueuePreviousItemRow(
                    song = song,
                    onJump = { onJumpToItem(index) },
                    onPlayNext = { onPlaySongNext(song) },
                    onAddToQueue = { onAddSongToQueue(song) },
                    onViewStats = { onViewStats(song.id) },
                    onShare = { onShareSong(song) },
                )
                if (index < previousCount - 1) {
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
                    modifier = Modifier.padding(top = if (previousCount > 0) 8.dp else 0.dp),
                )
            }
            item {
                QueueNowPlayingRow(song = currentSong)
            }
        }

        // ── Up next ───────────────────────────────────────────────────────────
        if (upNextCount > 0) {
            item {
                QueueSectionHeader(
                    label = "Up next · $upNextCount",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(
                count = upNextCount,
                // key = absolute queue index (currentIndex+1..), always unique
                key = { index ->
                    val playbackIndex = upNextStartIndex + index
                    "up-next-${state.queue[playbackIndex].id}-$playbackIndex"
                },
            ) { index ->
                val playbackIndex = upNextStartIndex + index
                val song = state.queue[playbackIndex]
                val isFirstUpNext = index == 0
                val isLastUpNext = index == upNextCount - 1
                val isDragging = draggingPlaybackIndex == playbackIndex
                val rowKey = "up-next-${song.id}-$playbackIndex"
                // Insertion marker for this row (Top/Bottom/none), matching the move-to-index commit.
                val dragSource = draggingPlaybackIndex
                val dragTarget = dragTargetPlaybackIndex
                val insertionEdge = if (isDragActive && dragSource != null && dragTarget != null) {
                    queueInsertionEdgeFor(playbackIndex, dragSource, dragTarget)
                } else {
                    null
                }
                // Wrap row + divider so the whole entry animates into place on the final commit
                // (placement only — the backing list is never locally reordered during the gesture).
                Column(
                    modifier = Modifier.animateItem(
                        placementSpec = if (reduceMotion) {
                            null
                        } else {
                            spring(
                                stiffness = Spring.StiffnessMediumLow,
                                visibilityThreshold = IntOffset.VisibilityThreshold,
                            )
                        },
                    ),
                ) {
                    SwipeableQueueItemRow(
                        song = song,
                        isFirstUpNext = isFirstUpNext,
                        isLastUpNext = isLastUpNext,
                        isDragging = isDragging,
                        isDimmed = anyDragging && !isDragging,
                        insertionEdge = insertionEdge,
                        onJump = { onJumpToItem(playbackIndex) },
                        onMoveUp = { onMoveUp(playbackIndex) },
                        onMoveDown = { onMoveDown(playbackIndex) },
                        onPlayNext = { onPlayNext(playbackIndex) },
                        onMoveTo = {
                            // Capture the exact invoked occurrence (duplicate-safe); the dialog
                            // re-resolves it against the live queue when a destination is chosen.
                            moveDialogSession = QueueDragSession(
                                sourceSongId = song.id,
                                sourceOccurrenceOrdinal = queueOccurrenceOrdinal(
                                    latestState.value.queue,
                                    playbackIndex,
                                ),
                                startSourcePlaybackIndex = playbackIndex,
                            )
                        },
                        onRemove = {
                            onRemoveItem(playbackIndex)
                            showRemovedSnackbar()
                        },
                        onViewStats = { onViewStats(song.id) },
                        onShare = { onShareSong(song) },
                        // The handle is now only a hit-registration region: it reports its bounds (in
                        // the viewport Box's coordinate space, via the shared viewportCoords) and
                        // unregisters on disposal. It no longer owns the drag detector, so virtualising
                        // this row can no longer cancel an in-progress drag.
                        onHandlePositioned = { handleCoords ->
                            val vp = viewportCoords
                            if (vp != null && vp.isAttached && handleCoords.isAttached) {
                                val raw = vp.localBoundingBoxOf(handleCoords)
                                // Enlarge only the hit region: span from the row's left edge to ~44dp,
                                // keeping the handle's vertical extent. This never reaches the artwork,
                                // so touching artwork/title still scrolls the list normally, and
                                // adjacent rows' regions do not overlap vertically.
                                val hitBounds = Rect(
                                    left = 0f,
                                    top = raw.top,
                                    right = maxOf(raw.right, handleHitWidthPx),
                                    bottom = raw.bottom,
                                )
                                handleRegistry[rowKey] = QueueDragHandleTarget(
                                    playbackIndex = playbackIndex,
                                    songId = song.id,
                                    bounds = hitBounds,
                                )
                            }
                        },
                        onHandleDisposed = { handleRegistry.remove(rowKey) },
                    )
                    if (!isLastUpNext) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
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
                // Edge fades: hint that more queue exists beyond the viewport. Purely decorative and
                // short, so they never obscure text/artwork; they strengthen a little during a drag.
                val fadeColor = MaterialTheme.colorScheme.surface
                val baseFade = if (compact) 20.dp else 28.dp
                val fadeHeight = if (anyDragging) baseFade + 8.dp else baseFade
                if (listState.canScrollBackward) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(fadeHeight)
                            .background(Brush.verticalGradient(listOf(fadeColor, Color.Transparent))),
                    )
                }
                if (listState.canScrollForward) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(fadeHeight)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, fadeColor))),
                    )
                }

                // Immutable captured Song: the preview never flips to a different track if the
                // queue shifts under an active drag (the old index-based lookup could).
                val previewSong = draggingSong
                val previewY = pointerViewportY
                if (anyDragging && previewSong != null && previewY != null) {
                    // Subtle "lift": spring the preview up to a small scale on pickup (skipped under
                    // reduced motion), plus a static elevation shadow. Keeps Wavdrop recognisable.
                    var lifted by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { lifted = true }
                    val liftScale by animateFloatAsState(
                        targetValue = if (lifted && !reduceMotion) 1.02f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        label = "queueDragLift",
                    )
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = (previewY - rowHeightPx / 2f).roundToInt(),
                                )
                            }
                            .graphicsLayer {
                                scaleX = liftScale
                                scaleY = liftScale
                            }
                            .shadow(elevation = 8.dp, clip = false)
                            .zIndex(1f),
                    )

                    // Long-distance position feedback: "N of M" for the drop destination within Up
                    // Next. Follows the finger, sits just above the preview, and vanishes on end/cancel.
                    val positionLabel = dragTargetPlaybackIndex?.let {
                        queueMovePositionLabel(it, upNextStartIndex, upNextCount)
                    }
                    if (positionLabel != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(50),
                            tonalElevation = 3.dp,
                            shadowElevation = 3.dp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset {
                                    IntOffset(
                                        x = 0,
                                        y = (previewY - rowHeightPx).coerceAtLeast(0f).roundToInt(),
                                    )
                                }
                                .padding(end = 12.dp)
                                .zIndex(2f),
                        ) {
                            Text(
                                text = positionLabel,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )

        // ── "Move to…" dialog (Phase C) ─────────────────────────────────────────
        val activeMoveSession = moveDialogSession
        if (activeMoveSession != null) {
            // If the invoked occurrence stops being a valid Up Next item while the dialog is open
            // (playback overtook it, or it was removed), close instead of risking the wrong move.
            LaunchedEffect(state.queue, currentIndex) {
                if (!isDraggedOccurrenceStillValid(state.queue, currentIndex, activeMoveSession)) {
                    moveDialogSession = null
                }
            }
            if (isDraggedOccurrenceStillValid(state.queue, currentIndex, activeMoveSession)) {
                MoveToDialog(
                    // Live Up Next count → validation range updates if the queue changes underneath.
                    upNextCount = upNextCount,
                    onDismiss = { moveDialogSession = null },
                    onPlayNext = {
                        if (runMovePlayNext(activeMoveSession)) showMovedSnackbar()
                        moveDialogSession = null
                    },
                    onTopOfUpNext = {
                        val target = topOfUpNextPlaybackIndex(latestState.value.currentIndex)
                        if (runMoveToTarget(activeMoveSession, target)) showMovedSnackbar()
                        moveDialogSession = null
                    },
                    onEndOfQueue = {
                        val target = endOfQueuePlaybackIndex(latestState.value.queue.size)
                        if (runMoveToTarget(activeMoveSession, target)) showMovedSnackbar()
                        moveDialogSession = null
                    },
                    onMoveToPosition = { position ->
                        val live = latestState.value
                        val target = upNextPositionToPlaybackIndex(
                            position1Based = position,
                            currentIndex = live.currentIndex,
                            queueSize = live.queue.size,
                        )
                        if (runMoveToTarget(activeMoveSession, target)) showMovedSnackbar()
                        moveDialogSession = null
                    },
                )
            }
        }
    }
}

// ── "Move to…" dialog ─────────────────────────────────────────────────────────

@Composable
private fun MoveToDialog(
    upNextCount: Int,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onTopOfUpNext: () -> Unit,
    onEndOfQueue: () -> Unit,
    onMoveToPosition: (Int) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            var positionMode by remember { mutableStateOf(false) }
            var positionText by remember { mutableStateOf("") }
            // The valid 1-based Up Next position, or null for blank/zero/negative/too-large/non-numeric.
            val moveTarget = positionText.toIntOrNull()?.takeIf { it in 1..upNextCount }
            val valid = moveTarget != null

            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    text = if (positionMode) "Move to position" else "Move track",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(16.dp))
                if (!positionMode) {
                    MoveDestinationRow(label = "Play next", onClick = onPlayNext)
                    MoveDestinationRow(label = "Top of Up Next", onClick = onTopOfUpNext)
                    MoveDestinationRow(label = "Position…", onClick = { positionMode = true })
                    MoveDestinationRow(label = "End of queue", onClick = onEndOfQueue)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                } else {
                    OutlinedTextField(
                        value = positionText,
                        onValueChange = { new -> positionText = new.filter { it.isDigit() }.take(6) },
                        label = { Text("Position in Up Next") },
                        supportingText = { Text("1–$upNextCount") },
                        isError = positionText.isNotEmpty() && !valid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { moveTarget?.let(onMoveToPosition) },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { positionMode = false }) { Text("Back") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = { moveTarget?.let(onMoveToPosition) },
                            enabled = valid,
                        ) { Text("Move") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveDestinationRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
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
                    text = { Text("Track Details") },
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
    insertionEdge: QueueInsertionEdge?,
    onJump: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onPlayNext: () -> Unit,
    onMoveTo: () -> Unit,
    onRemove: () -> Unit,
    onViewStats: () -> Unit,
    onShare: () -> Unit,
    onHandlePositioned: (LayoutCoordinates) -> Unit,
    onHandleDisposed: () -> Unit,
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
            insertionEdge = insertionEdge,
            onJump = onJump,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onPlayNext = onPlayNext,
            onMoveTo = onMoveTo,
            onRemove = onRemove,
            onViewStats = onViewStats,
            onShare = onShare,
            onHandlePositioned = onHandlePositioned,
            onHandleDisposed = onHandleDisposed,
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
    onMoveTo: () -> Unit = {},
    // Drop-target insertion marker for this row (Top/Bottom/none). Only real Up Next rows pass it.
    insertionEdge: QueueInsertionEdge? = null,
    // Handle hit-registration callbacks. Only supplied for real Up Next rows; the floating preview
    // row (dragHandleEnabled = false) passes neither and never registers.
    onHandlePositioned: (LayoutCoordinates) -> Unit = {},
    onHandleDisposed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val compact = LocalCompactMode.current
    val verticalPadding = if (compact) 8.dp else 10.dp
    val artworkSize = if (compact) 40.dp else 44.dp
    val insertionColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Draw the insertion marker as a thin accent line on the target edge, over the row's
            // content and divider so it is unambiguous at first/last positions and while scrolling.
            .drawWithContent {
                drawContent()
                if (insertionEdge != null) {
                    val stroke = 3.dp.toPx()
                    val y = if (insertionEdge == QueueInsertionEdge.Top) stroke / 2f else size.height - stroke / 2f
                    drawLine(
                        color = insertionColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = stroke,
                    )
                }
            }
            .background(
                if (isDragging) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(enabled = !(isDragging || isDimmed), onClick = onJump)
            .padding(start = 12.dp, end = 4.dp, top = verticalPadding, bottom = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dragHandleEnabled) {
            // Unregister the handle when this row leaves composition (LazyColumn virtualisation).
            // This only removes the hit region; it never cancels an in-progress drag, whose identity
            // lives in the parent-owned QueueDragSession.
            DisposableEffect(Unit) {
                onDispose { onHandleDisposed() }
            }
        }
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(artworkSize)
                .then(
                    // The handle reports its bounds (in the shared viewport coordinate space) so the
                    // stable parent detector can hit-test pointer-down. It owns no gesture detector.
                    if (dragHandleEnabled) {
                        Modifier.onGloballyPositioned { onHandlePositioned(it) }
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
                    text = { Text("Move to…") },
                    onClick = { expanded = false; onMoveTo() },
                )
                DropdownMenuItem(
                    text = { Text("Remove from queue") },
                    onClick = { expanded = false; onRemove() },
                )
                DropdownMenuItem(
                    text = { Text("Track Details") },
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
