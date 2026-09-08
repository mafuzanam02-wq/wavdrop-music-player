package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.launchpoint.wavdrop.ui.components.motion.rememberReducedMotion
import com.launchpoint.wavdrop.ui.theme.WavdropMotion

/**
 * Shared artwork surface.
 *
 * By default (used by every list/grid row and header) the placeholder shows whenever the current
 * request is not [AsyncImagePainter.State.Success] — the long-standing behavior. This is important
 * for recycled LazyColumn/LazyGrid rows: a row's composition slot is reused as it scrolls, so
 * retaining a previous cover there could briefly show the wrong thumbnail.
 *
 * [retainPreviousOnLoad] opts a single, non-recycled surface (the large Now Playing artwork) into
 * keeping the previously loaded artwork visible while the next track's artwork loads, so an ordinary
 * artwork-to-artwork change crossfades directly instead of flashing the placeholder. See
 * [resolveArtworkRenderDecision] for the exact policy.
 */
@Composable
fun ArtworkImage(
    artworkUri: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    retainPreviousOnLoad: Boolean = false,
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val shortestSide = if (maxWidth < maxHeight) maxWidth else maxHeight
        val placeholderSize = (shortestSide * 0.42f).coerceIn(22.dp, 72.dp)

        // The last successfully-loaded result painter, kept ONLY when retention is enabled. It holds
        // the concrete bitmap painter (not the mutating AsyncImagePainter), so it stays drawable
        // after the request moves on to the next track. UI-only and bounded to a single reference.
        var lastSuccessPainter by remember { mutableStateOf<Painter?>(null) }

        if (artworkUri.isNullOrBlank()) {
            // No artwork for the current track: show the genuine placeholder and drop any retained
            // cover so a later load never shows a previous track's art (contract C).
            if (retainPreviousOnLoad && lastSuccessPainter != null) {
                SideEffect { lastSuccessPainter = null }
            }
            ArtworkPlaceholder(
                placeholderIcon = placeholderIcon,
                modifier = Modifier.size(placeholderSize),
            )
        } else {
            // Subtle crossfade so artwork settles in instead of snapping; disabled under reduced
            // motion. Coil only animates on load, not on recomposition.
            val context = LocalContext.current
            val reducedMotion = rememberReducedMotion()
            val model = remember(artworkUri, reducedMotion) {
                ImageRequest.Builder(context)
                    .data(artworkUri)
                    .crossfade(if (reducedMotion) 0 else WavdropMotion.Durations.StandardStateChange)
                    .build()
            }

            val retained = lastSuccessPainter
            Box(modifier = Modifier.fillMaxSize()) {
                // Base layer: the previous cover, kept visible under the incoming image while it
                // loads. The new image's crossfade then fades in over it → a direct A→B transition.
                if (retainPreviousOnLoad && retained != null) {
                    Image(
                        painter = retained,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                SubcomposeAsyncImage(
                    model = model,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val decision = resolveArtworkRenderDecision(
                        phase = painter.state.toArtworkLoadPhase(),
                        retainPreviousOnLoad = retainPreviousOnLoad,
                        hasPreviousSuccess = retained != null,
                    )
                    when (decision) {
                        ArtworkRenderDecision.NEW_IMAGE -> {
                            SubcomposeAsyncImageContent()
                            if (retainPreviousOnLoad) {
                                val resultPainter = (painter.state as AsyncImagePainter.State.Success).painter
                                SideEffect { lastSuccessPainter = resultPainter }
                            }
                        }
                        // Draw nothing — the retained base layer remains visible underneath.
                        ArtworkRenderDecision.RETAIN_PREVIOUS -> Unit
                        ArtworkRenderDecision.PLACEHOLDER -> {
                            // Definitive empty/error: stop showing any stale previous cover so the
                            // state reads truthfully (contract D).
                            if (retainPreviousOnLoad && retained != null) {
                                SideEffect { lastSuccessPainter = null }
                            }
                            ArtworkPlaceholder(
                                placeholderIcon = placeholderIcon,
                                modifier = Modifier.size(placeholderSize),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What [ArtworkImage] should draw for the current load state. */
internal enum class ArtworkRenderDecision { NEW_IMAGE, RETAIN_PREVIOUS, PLACEHOLDER }

/** Coil load state collapsed to the three cases the render policy cares about. */
internal enum class ArtworkLoadPhase { LOADING, SUCCESS, RESOLVED_EMPTY }

/**
 * Pure render policy (unit-tested). Retention only ever kicks in while [ArtworkLoadPhase.LOADING]
 * with a prior success and retention enabled; a definitive empty/error always falls back to the
 * placeholder so a failed load never masquerades as the previous track's cover.
 */
internal fun resolveArtworkRenderDecision(
    phase: ArtworkLoadPhase,
    retainPreviousOnLoad: Boolean,
    hasPreviousSuccess: Boolean,
): ArtworkRenderDecision = when (phase) {
    ArtworkLoadPhase.SUCCESS -> ArtworkRenderDecision.NEW_IMAGE
    ArtworkLoadPhase.LOADING ->
        if (retainPreviousOnLoad && hasPreviousSuccess) ArtworkRenderDecision.RETAIN_PREVIOUS
        else ArtworkRenderDecision.PLACEHOLDER
    ArtworkLoadPhase.RESOLVED_EMPTY -> ArtworkRenderDecision.PLACEHOLDER
}

private fun AsyncImagePainter.State.toArtworkLoadPhase(): ArtworkLoadPhase = when (this) {
    is AsyncImagePainter.State.Success -> ArtworkLoadPhase.SUCCESS
    is AsyncImagePainter.State.Loading -> ArtworkLoadPhase.LOADING
    is AsyncImagePainter.State.Error,
    AsyncImagePainter.State.Empty -> ArtworkLoadPhase.RESOLVED_EMPTY
}

@Composable
private fun ArtworkPlaceholder(
    placeholderIcon: ImageVector?,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = placeholderIcon ?: Icons.Default.Album,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}
