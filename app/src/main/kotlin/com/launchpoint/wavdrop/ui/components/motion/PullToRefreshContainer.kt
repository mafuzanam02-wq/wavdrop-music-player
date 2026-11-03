package com.launchpoint.wavdrop.ui.components.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.launchpoint.wavdrop.ui.scan.LibraryScanUiState
import kotlinx.coroutines.delay

private val ThresholdDp = 80.dp
private val IndicatorHeight = 40.dp
private val RestingOffset = 56.dp

/**
 * Shared pull-to-refresh for Home and Songs (P5-F11). It owns only presentation: the scan itself stays with
 * `LibraryScanCoordinator`, whose [scanState] this reflects, and [onRefresh] is that coordinator's one rescan entry point.
 *
 * - The content follows the finger with increasing resistance, capped at ~38% of the viewport; a release past an 80dp
 *   (viewport-limited) threshold requests one refresh, a shorter pull or a reverse drag cancels.
 * - A gesture always settles itself on completion ([PullRefreshController.finishGesture]); it is reached from the pointer
 *   release (slow drags) and from the trailing fling (fast flicks), and is idempotent.
 * - The compact indicator spins only while the scan is confirmed [LibraryScanUiState.Scanning]; between the request and
 *   that confirmation it shows a static "starting" ring. Success is shown only after a user-requested scan is observed
 *   completing. Warnings/errors are left to the existing status card (no success shown).
 * - An automatic scan (not user-requested) shows only a thin progress bar and never displaces the content.
 */
@Composable
fun PullToRefreshContainer(
    scanState: LibraryScanUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val currentScan by rememberUpdatedState(scanState)
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentReduced by rememberUpdatedState(reducedMotion)

    var phase by remember { mutableStateOf(ManualRefreshPhase.None) }
    var viewportPx by remember { mutableFloatStateOf(0f) }
    val thresholdPx = with(density) { ThresholdDp.toPx() }
    val restingPx by rememberUpdatedState(with(density) { RestingOffset.toPx() })
    val indicatorPx = with(density) { IndicatorHeight.toPx() }

    val controller = remember(scope) { PullRefreshController(scope) }
    controller.configure(viewportPx, thresholdPx)
    // Navigation away / disposal: nothing may keep animating or hold a half-finished gesture.
    DisposableEffect(controller) { onDispose { controller.cancel() } }

    // Phase follows the authoritative scan state (never a timer).
    LaunchedEffect(scanState) { phase = reduceManualRefresh(phase, scanState) }
    LaunchedEffect(phase) {
        when (phase) {
            ManualRefreshPhase.Succeeded -> {
                delay(PullRefreshCopy.SUCCESS_VISIBLE_MS)
                phase = ManualRefreshPhase.None
            }
            ManualRefreshPhase.Requested -> {
                // The scan never reported Scanning (e.g. it finished inside one frame): drop the request, claim nothing.
                delay(PullRefreshCopy.UNACKNOWLEDGED_REQUEST_MS)
                phase = ManualRefreshPhase.None
            }
            else -> Unit
        }
    }
    val mode = indicatorMode(phase, controller.isDragging)
    val restTarget = if (holdsCompactPosition(mode)) restingPx else 0f
    // Belt and braces: also settle when the resting position, motion setting or finger state changes (no-op while dragging
    // or already settling there). Gesture completion itself never depends on this.
    LaunchedEffect(restTarget, reducedMotion, controller.isDragging) { controller.settleTo(restTarget, reducedMotion) }

    val requestRefresh: () -> Unit = {
        val request = requestManualRefresh(phase, currentScan)
        phase = request.phase
        if (request.invokeRefresh) currentOnRefresh()
    }
    val currentRequestRefresh by rememberUpdatedState(requestRefresh)

    /** Ends the active gesture (if any). [suppressRefresh] ends it as a cancel, e.g. when pulling gets disabled mid-drag. */
    val finishGesture: (Boolean) -> Unit = { suppressRefresh ->
        val refreshing = suppressRefresh || phase == ManualRefreshPhase.Requested || phase == ManualRefreshPhase.Scanning
        controller.finishGesture(
            alreadyRefreshing = refreshing,
            reducedMotion = currentReduced,
            onFire = { currentRequestRefresh() },
            target = { if (holdsCompactPosition(indicatorMode(phase, isPulling = false))) restingPx else 0f },
        )
    }
    val currentFinish by rememberUpdatedState(finishGesture)
    LaunchedEffect(enabled) { if (!enabled) currentFinish(true) }

    val connection = remember(controller) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!currentEnabled || source != NestedScrollSource.UserInput) return Offset.Zero
                // Reversing while displaced shrinks the pull before the list may scroll up (cancel by dragging back).
                if (available.y < 0f && controller.isDragging) {
                    return Offset(0f, controller.pull(available.y) { WavdropHaptics.threshold(haptics) })
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!currentEnabled || source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                // The list is at its top and did not consume the downward drag: this is a pull.
                return Offset(0f, controller.pull(available.y) { WavdropHaptics.threshold(haptics) })
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // Fast flicks reach completion here; slow drags usually already finished on pointer release (idempotent).
                currentFinish(false)
                // The pulled gesture owns its trailing fling; swallow it so the list does not also move.
                return if (controller.consumeFling()) available else Velocity.Zero
            }
        }
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { viewportPx = it.height.toFloat() }
            // Observes (never consumes) pointers: the last finger leaving always completes the gesture, even without a fling.
            .pointerInput(controller) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDown() }) controller.onPointerDown()
                        if (event.changes.none { it.pressed }) currentFinish(false)
                    }
                }
            }
            .nestedScroll(connection)
            .semantics {
                customActions = listOf(CustomAccessibilityAction(PullRefreshCopy.ACTION) { currentRequestRefresh(); true })
            },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = controller.offsetPx }) { content() }

        if (mode != RefreshIndicatorMode.Hidden) {
            val description = when (mode) {
                RefreshIndicatorMode.Starting -> PullRefreshCopy.STARTING
                RefreshIndicatorMode.Scanning -> PullRefreshCopy.SCANNING
                RefreshIndicatorMode.Success -> PullRefreshCopy.SUCCESS
                else -> PullRefreshCopy.PULL_DESCRIPTION
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        val offset = controller.offsetPx
                        translationY = offset / 2f - indicatorPx / 2f
                        alpha = (offset / indicatorPx).coerceIn(0f, 1f)
                    }
                    .semantics {
                        stateDescription = description
                        liveRegion = LiveRegionMode.Polite
                    },
            ) {
                RefreshIndicator(mode = mode, pullProgress = { controller.progress }, reducedMotion = reducedMotion)
            }
        } else if (scanState == LibraryScanUiState.Scanning && phase == ManualRefreshPhase.None) {
            // Automatic (non user-requested) scan: unobtrusive, content stays put.
            LinearProgressIndicator(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).semantics { stateDescription = PullRefreshCopy.SCANNING },
            )
        }
    }
}

@Composable
private fun RefreshIndicator(mode: RefreshIndicatorMode, pullProgress: () -> Float, reducedMotion: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.height(IndicatorHeight),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp).height(IndicatorHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (mode) {
                RefreshIndicatorMode.Success -> {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text(
                        PullRefreshCopy.SUCCESS,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                // Requested but not yet confirmed: a static ring, never a spinner implying work is under way.
                RefreshIndicatorMode.Starting ->
                    CircularProgressIndicator(progress = { STATIC_PROGRESS }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                // Confirmed scanning is indeterminate only when motion is allowed; reduced motion shows a static partial ring.
                RefreshIndicatorMode.Scanning ->
                    if (reducedMotion) CircularProgressIndicator(progress = { STATIC_PROGRESS }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    else CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                else -> CircularProgressIndicator(
                    progress = pullProgress,
                    modifier = Modifier.size(22.dp).graphicsLayer {
                        if (!reducedMotion) rotationZ = pullProgress() * 180f
                    },
                    strokeWidth = 2.5.dp,
                )
            }
        }
    }
}

private const val STATIC_PROGRESS = 0.75f
