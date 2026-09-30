package com.launchpoint.wavdrop.ui.screen.nowplaying

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import com.launchpoint.wavdrop.data.lyrics.SyncedLyricsLine
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * Index of the line that is current at [positionMs], or null before the first timestamp.
 * The current line is the last one whose time is <= [positionMs], so at an exact timestamp that
 * line is active, equal timestamps resolve to the last of them, and after the final line the final
 * line stays active. [lines] must be ordered by time (as produced by the LRC parser). Lines are
 * identified by position only, never by text; empty timed lines participate in the timeline.
 */
internal fun activeSyncedLyricsIndex(lines: List<SyncedLyricsLine>, positionMs: Long): Int? {
    var low = 0
    var high = lines.size // first index whose time is > positionMs
    while (low < high) {
        val mid = (low + high) ushr 1
        if (lines[mid].timeMs <= positionMs) low = mid + 1 else high = mid
    }
    return (low - 1).takeIf { it >= 0 }
}

/**
 * The negative `scrollOffset` to pass to [LazyListState.scrollToItem] so an
 * item of [itemHeightPx] is placed with its top at `(viewport - item) / 2`, i.e. vertically centered.
 * (A positive offset scrolls the item toward the viewport start, so moving it down is negative.)
 */
internal fun syncedLyricsScrollOffset(
    viewportHeightPx: Int,
    itemHeightPx: Int,
): Int = -(viewportHeightPx - itemHeightPx) / 2

/**
 * How far (px, positive = scroll forward) an item must still move so its center sits at the center
 * of the viewport, in the list's own item coordinate space ([viewportStartOffsetPx] and
 * [viewportEndOffsetPx] come from `LazyListLayoutInfo`, so content padding is already accounted
 * for). Zero when the item is already centered.
 */
internal fun syncedLyricsCenteringDelta(
    itemOffsetPx: Int,
    itemSizePx: Int,
    viewportStartOffsetPx: Int,
    viewportEndOffsetPx: Int,
): Float {
    val viewportCenter = (viewportStartOffsetPx + viewportEndOffsetPx) / 2f
    return itemOffsetPx + itemSizePx / 2f - viewportCenter
}

/** Centering offset for [target] from the live layout: its real height if laid out, else the visible average. */
private fun LazyListState.centeringOffsetFor(target: Int): Int {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    val itemHeight = visible.firstOrNull { it.index == target }?.size
        ?: visible.takeIf { it.isNotEmpty() }?.let { items -> items.sumOf { it.size } / items.size }
        ?: 0
    return syncedLyricsScrollOffset(
        viewportHeightPx = info.viewportSize.height,
        itemHeightPx = itemHeight,
    )
}

/**
 * Final check against the real layout: if the laid-out target is not centered (a different estimate,
 * or padding semantics of the scroll API), nudge it the remaining distance. No-op when already centered.
 */
private suspend fun LazyListState.nudgeToCenter(target: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == target } ?: return
    val delta = syncedLyricsCenteringDelta(
        itemOffsetPx = item.offset,
        itemSizePx = item.size,
        viewportStartOffsetPx = info.viewportStartOffset,
        viewportEndOffsetPx = info.viewportEndOffset,
    )
    if (abs(delta) <= 1f) return
    scrollBy(delta)
}

/**
 * Synchronized lyrics for the artwork overlay. The active line is derived from [trackProgress], the
 * one continuous Media3 progress state shared with the seek track (this composable adds no progress
 * observer, timer or poller of its own). The position is read inside `derivedStateOf`, so the list
 * only recomposes when the active line changes, and auto-scroll is keyed to that index.
 *
 * Following is immediate, never animated: the new active line must already be in its centered
 * position when it becomes highlighted, instead of being highlighted low and then gliding up.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun SyncedLyricsOverlayList(
    lines: List<SyncedLyricsLine>,
    compact: Boolean,
    trackProgress: ProgressStateWithTickInterval,
    modifier: Modifier = Modifier,
) {
    val activeIndex by remember(lines, trackProgress) {
        derivedStateOf { activeSyncedLyricsIndex(lines, trackProgress.currentPositionMs) }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(lines, activeIndex) {
        // Item/viewport sizes are only known after the first measure.
        snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        val target = activeIndex ?: 0
        if (target !in lines.indices) return@LaunchedEffect
        // The item's real height is only known once it is laid out, so refine once.
        val estimated = listState.centeringOffsetFor(target)
        listState.scrollToItem(target, estimated)
        val refined = listState.centeringOffsetFor(target)
        if (abs(refined - estimated) > 1) listState.scrollToItem(target, refined)
        listState.nudgeToCenter(target)
    }

    BoxWithConstraints(modifier = modifier) {
        // Half-viewport padding at both ends lets even the first and last lines reach the middle.
        val edgePadding = maxHeight / 2
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = edgePadding, bottom = edgePadding),
        ) {
            itemsIndexed(lines) { index, line ->
                if (line.text.isBlank()) {
                    // Timed gap: keeps its place in the timeline, nothing is highlighted.
                    Spacer(Modifier.height(if (compact) 12.dp else 18.dp))
                } else {
                    val active = index == activeIndex
                    Text(
                        text = line.text,
                        style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) Color.White else Color.White.copy(alpha = 0.48f),
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = if (compact) 3.dp else 5.dp),
                    )
                }
            }
        }
    }
}
