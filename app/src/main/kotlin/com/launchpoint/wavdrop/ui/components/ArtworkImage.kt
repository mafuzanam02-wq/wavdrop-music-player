package com.launchpoint.wavdrop.ui.components

import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.ui.components.motion.rememberReducedMotion
import com.launchpoint.wavdrop.ui.theme.WavdropMotion
import kotlinx.coroutines.delay

private const val ARTWORK_TAG = "WavdropArtwork"

/**
 * Shared artwork surface (WC-07).
 *
 * Two paths share one placeholder/frame policy and one request factory:
 *  - DEFAULT (every list/grid row and header): a non-subcomposing Coil `AsyncImage` over an always-present placeholder, keyed by the
 *    request identity so a recycled LazyColumn/LazyGrid slot never draws the previous row's cover, with no BoxWithConstraints. Pass
 *    [artworkSize] when the surface has a fixed size so the decode is bounded to its pixel size.
 *  - [retainPreviousOnLoad] (the single, non-recycled large Now Playing artwork): a request-keyed loader whose retained/previous
 *    cover is owned by the request that produced it ([ArtworkSurface]); late results from a superseded request are ignored, a
 *    definitive failure clears the previous cover, and the decode size comes from the measured surface.
 */
@Composable
fun ArtworkImage(
    artworkUri: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    retainPreviousOnLoad: Boolean = false,
    artworkSize: Dp? = null,
) {
    Box(
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
        if (retainPreviousOnLoad) {
            LargeArtworkContent(artworkUri, contentDescription, placeholderIcon)
        } else {
            ListArtworkContent(artworkUri, contentDescription, placeholderIcon, artworkSize)
        }
    }
}

/** The one place artwork [ImageRequest]s are built, so no call site constructs a subtly different request. */
internal object ArtworkRequestFactory {
    fun build(
        context: android.content.Context,
        key: ArtworkRequestKey,
        crossfadeMs: Int,
        allowHardware: Boolean = true,
    ): ImageRequest = ImageRequest.Builder(context)
        .data(key.uri)
        .apply { key.target?.let { size(it.widthPx, it.heightPx) } } // no target: Coil resolves the size from the layout constraints
        .crossfade(crossfadeMs)
        .allowHardware(allowHardware)
        .build()
}

@Composable
private fun ListArtworkContent(
    artworkUri: String?,
    contentDescription: String?,
    placeholderIcon: ImageVector?,
    artworkSize: Dp?,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val reducedMotion = rememberReducedMotion()
    val requestKey = remember(artworkUri, artworkSize, density) {
        artworkUri?.takeIf { it.isNotBlank() }?.let { uri ->
            ArtworkRequestKey(uri, artworkSize?.let { ArtworkSizing.thumbnailTarget(it.value, density) })
        }
    }

    if (requestKey == null) {
        ArtworkPlaceholder(placeholderIcon, Modifier.artworkPlaceholderSize())
        return
    }
    // A new request identity gets a brand-new image painter, so a reused row slot cannot draw the previous cover while loading.
    key(requestKey) {
        val request = remember(requestKey, reducedMotion) {
            ArtworkRequestFactory.build(context, requestKey, if (reducedMotion) 0 else WavdropMotion.Durations.StandardStateChange)
        }
        var loaded by remember { mutableStateOf(false) }
        if (!loaded) ArtworkPlaceholder(placeholderIcon, Modifier.artworkPlaceholderSize())
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { loaded = true },
        )
    }
}

/** A successfully loaded large artwork. Compared by identity (a new load is a new object). */
private class LoadedArtwork(val bitmap: ImageBitmap)

@Composable
private fun LargeArtworkContent(
    artworkUri: String?,
    contentDescription: String?,
    placeholderIcon: ImageVector?,
) {
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val hasArtwork = !artworkUri.isNullOrBlank()
    val target = remember(measured) { ArtworkSizing.largeTarget(measured.width, measured.height) }
    val requestKey = if (hasArtwork && target != null) ArtworkRequestKey(artworkUri!!, target) else null
    var state by remember { mutableStateOf<ArtworkSurfaceState<LoadedArtwork>>(ArtworkSurfaceState.Idle) }
    val crossfadeMs = if (reducedMotion) 0 else WavdropMotion.Durations.StandardStateChange

    LaunchedEffect(requestKey, hasArtwork) {
        if (!hasArtwork) {
            if (BuildConfig.DEBUG && state != ArtworkSurfaceState.Idle) Log.d(ARTWORK_TAG, "large: no artwork for current track, cleared retained")
            state = ArtworkSurface.reduce(state, ArtworkEvent.NoArtwork)
            return@LaunchedEffect
        }
        val current = requestKey ?: return@LaunchedEffect // not measured yet: placeholder until the first layout pass
        val hadPrevious = ArtworkSurface.displayed(state) != null
        state = ArtworkSurface.reduce(state, ArtworkEvent.Requested(current))
        if (BuildConfig.DEBUG) {
            Log.d(ARTWORK_TAG, "large: request uri=${current.uri} target=${current.target?.widthPx}x${current.target?.heightPx} retainedPrevious=$hadPrevious")
        }
        // Software bitmaps: this surface sits under overlays/clips, and a hardware bitmap that cannot be drawn there fails silently.
        val request = ArtworkRequestFactory.build(context, current, crossfadeMs = 0, allowHardware = false)
        var attempt = 1
        while (true) {
            when (val result = context.imageLoader.execute(request)) {
                is SuccessResult -> {
                    val drawable = result.drawable
                    val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: drawable.toBitmap()
                    state = ArtworkSurface.reduce(state, ArtworkEvent.Succeeded(current, LoadedArtwork(bitmap.asImageBitmap())))
                    return@LaunchedEffect
                }
                is ErrorResult -> {
                    if (ArtworkRetryPolicy.shouldRetry(attempt)) {
                        attempt++
                        delay(ArtworkRetryPolicy.RETRY_DELAY_MS)
                    } else {
                        if (BuildConfig.DEBUG) {
                            Log.d(ARTWORK_TAG, "large: failed uri=${current.uri} target=${current.target?.widthPx}x${current.target?.heightPx} " +
                                "attempts=$attempt error=${result.throwable::class.java.simpleName}: ${result.throwable.message}; previous cleared")
                        }
                        state = ArtworkSurface.reduce(state, ArtworkEvent.Failed(current))
                        return@LaunchedEffect
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().onSizeChanged { measured = it }, contentAlignment = Alignment.Center) {
        // The URI check is applied at draw time too, so an artless track never shows a retained cover for even one frame.
        val shown = if (hasArtwork) ArtworkSurface.displayed(state) else null
        Crossfade(
            targetState = shown,
            animationSpec = if (reducedMotion) snap() else tween(crossfadeMs),
            label = "artwork",
        ) { art ->
            if (art == null) {
                ArtworkPlaceholder(placeholderIcon, Modifier.artworkPlaceholderSize())
            } else {
                Image(
                    bitmap = art.bitmap,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Placeholder icon at 42% of the surface's shortest side, clamped to 22–72dp — computed in a plain layout modifier from the
 * incoming constraints, so no BoxWithConstraints/subcomposition is needed.
 */
private fun Modifier.artworkPlaceholderSize(): Modifier = layout { measurable, constraints ->
    val minPx = 22.dp.roundToPx()
    val maxPx = 72.dp.roundToPx()
    val shortest = minOf(
        if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE,
        if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE,
    )
    val side = if (shortest == Int.MAX_VALUE) maxPx else (shortest * 0.42f).toInt().coerceIn(minPx, maxPx)
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(side, side) { placeable.placeRelative(0, 0) }
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
