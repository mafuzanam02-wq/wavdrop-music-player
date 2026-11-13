package com.launchpoint.wavdrop.ui.screen.wrapped

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File

private const val TAG = "WrappedVideoExport"

/** Test tags of the export bar (also stable hooks for the emulator UI flows). */
internal const val TAG_VIDEO_EXPORT_BAR = "wrapped_video_export_bar"
internal const val TAG_VIDEO_EXPORT_STATUS = "wrapped_video_export_status"
internal const val TAG_VIDEO_EXPORT_BUTTON = "wrapped_video_export_button"
internal const val TAG_VIDEO_EXPORT_CANCEL = "wrapped_video_export_cancel"
internal const val TAG_VIDEO_EXPORT_SHARE = "wrapped_video_export_share"

/** The coordinator plus the (screen-scoped) slot where the render host is mounted while a slide export runs. */
internal class WrappedVideoExportController(
    val coordinator: WrappedVideoExportCoordinator,
    private val hostState: State<WrappedSlideHost?>,
) {
    val host: WrappedSlideHost? get() = hostState.value
}

/**
 * Screen-scoped export: lives exactly as long as the Wrapped screen is composed. Leaving the screen (or the activity
 * being recreated) cancels an active export through [WrappedVideoExportCoordinator.release], which removes its
 * intermediates; the render host is unmounted because nothing is composed any more.
 */
@Composable
internal fun rememberWrappedVideoExportController(): WrappedVideoExportController {
    val context = LocalContext.current.applicationContext
    // Explicitly main-bound: Compose capture and the render-host state must never run on a worker thread, whichever
    // dispatcher the surrounding composition happens to use for effects.
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    val controller = remember {
        val hostState = mutableStateOf<WrappedSlideHost?>(null)
        val slideBase = wrappedExportBaseDir(context.cacheDir)
        val videoDir = wrappedVideoDir(context.cacheDir)
        val encoder = WrappedVideoEncoder(
            engineFactory = { Media3WrappedVideoEngine(context) },
            inspect = ::inspectWrappedVideo,
            decodable = ::wrappedSlideDecodable,
        )
        val coordinator = WrappedVideoExportCoordinator(
            scope = scope,
            generateSlides = { plan: WrappedExportPlan ->
                val host = WrappedSlideHost(plan)
                hostState.value = host // mounted by the screen; unmounted in finally on every path
                try {
                    exportWrappedSlides(context, host, plan, slideBase)
                } finally {
                    hostState.value = null
                }
            },
            encode = { slides, plan, onState -> encoder.encode(slides, videoDir, wrappedVideoFileName(plan), onState) },
            sweepOrphans = { sweepWrappedVideoOrphans(slideBase, videoDir) },
            onCleanupProblem = { Log.w(TAG, it) },
        )
        WrappedVideoExportController(coordinator, hostState)
    }
    DisposableEffect(controller) { onDispose { controller.coordinator.release() } }
    return controller
}

/**
 * Mounts the render host (when an export is generating slides) inside the attached composition so it takes part in real
 * draw passes, yet occupies no layout space (0x0), draws nothing outside its bounds (clipped) and is invisible to
 * accessibility services.
 */
@Composable
internal fun WrappedVideoExportHostMount(controller: WrappedVideoExportController) {
    val host = controller.host ?: return
    Box(
        Modifier
            .size(0.dp)
            .clipToBounds()
            .clearAndSetSemantics { },
    ) { host.Content() }
}

internal fun wrappedVideoExportStatusText(state: WrappedVideoExportState): String? = when (state) {
    WrappedVideoExportState.Idle -> null
    is WrappedVideoExportState.Preparing -> "Preparing ${state.pageCount} slides · ${state.periodLabel}"
    is WrappedVideoExportState.Encoding ->
        "Encoding video · ${state.periodLabel}" + (state.percent?.let { " · $it%" } ?: "")
    is WrappedVideoExportState.Cancelling -> "Cancelling export…"
    is WrappedVideoExportState.Completed -> "Video ready · ${state.periodLabel}"
    is WrappedVideoExportState.Failed -> when (state.reason) {
        WrappedVideoExportFailure.SlidesFailed -> "Couldn't prepare the slides for ${state.periodLabel}."
        WrappedVideoExportFailure.EncodingFailed -> "Couldn't encode the video for ${state.periodLabel}."
        WrappedVideoExportFailure.Unexpected -> "Something went wrong exporting ${state.periodLabel}."
    }
    is WrappedVideoExportState.Cancelled -> "Export cancelled."
}

/** Compact Material 3 strip under the story controls. Pure UI: all decisions live in the coordinator. */
@Composable
internal fun WrappedVideoExportBar(
    state: WrappedVideoExportState,
    exportEnabled: Boolean,
    shareEnabled: Boolean,
    onExport: () -> Unit,
    onCancel: () -> Unit,
    onShare: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = wrappedVideoExportStatusText(state)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (state is WrappedVideoExportState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .semantics {
                        liveRegion = LiveRegionMode.Polite // state changes are announced
                        testTag = TAG_VIDEO_EXPORT_STATUS
                    },
            )
        }
        when (state) {
            is WrappedVideoExportState.Preparing, is WrappedVideoExportState.Cancelling ->
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = status.orEmpty() }) // no invented percentage
            is WrappedVideoExportState.Encoding -> {
                val percent = state.percent
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Encoding video $percent percent" },
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Encoding video, progress unknown" })
                }
            }
            else -> Unit
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                is WrappedVideoExportState.Preparing, is WrappedVideoExportState.Encoding ->
                    TextButton(onClick = onCancel, modifier = Modifier.semantics { testTag = TAG_VIDEO_EXPORT_CANCEL }) {
                        Text("Cancel")
                    }
                is WrappedVideoExportState.Cancelling -> Unit
                is WrappedVideoExportState.Completed -> {
                    TextButton(onClick = onExport, enabled = exportEnabled, modifier = Modifier.semantics { testTag = TAG_VIDEO_EXPORT_BUTTON }) {
                        Text("Export again")
                    }
                    Button(
                        onClick = { onShare(state.file) },
                        enabled = shareEnabled,
                        modifier = Modifier.semantics { testTag = TAG_VIDEO_EXPORT_SHARE },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Share video", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                else -> FilledTonalButton(
                    onClick = onExport,
                    enabled = exportEnabled,
                    modifier = Modifier.semantics { testTag = TAG_VIDEO_EXPORT_BUTTON },
                ) {
                    Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        if (state is WrappedVideoExportState.Failed) "Retry" else "Export video",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
