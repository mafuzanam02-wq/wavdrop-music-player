package com.launchpoint.wavdrop.ui.components

import kotlin.math.ceil
import kotlin.math.min

/** Decode target in pixels. */
internal data class ArtworkTarget(val widthPx: Int, val heightPx: Int)

/**
 * Identity of one artwork request: the artwork model plus the render parameters that change the decoded bitmap. Deliberately NOT
 * the song id — two songs on the same album share a request (same URI, same size), so a track change inside an album neither
 * reloads nor clears valid artwork, while a size change or a different URI is a genuinely new request.
 */
internal data class ArtworkRequestKey(val uri: String, val target: ArtworkTarget?)

/** Pure target-size policy (WC-07). Never produces a zero/negative/unbounded size. */
internal object ArtworkSizing {
    /** Upper bound for the large Now Playing decode, so a big/foldable window can never request an unbounded bitmap. */
    const val LARGE_MAX_PX = 1600

    /** Measured sizes are rounded UP to this bucket so sub-bucket layout jitter never rebuilds the request. */
    const val LARGE_BUCKET_PX = 64

    /** Fixed thumbnail surface (row / mini player / header): the surface's own pixel size, rounded up. Null for an invalid size. */
    fun thumbnailTarget(sizeDp: Float, density: Float): ArtworkTarget? {
        if (!sizeDp.isFinite() || !density.isFinite() || sizeDp <= 0f || density <= 0f) return null
        val px = ceil(sizeDp * density).toInt().coerceAtLeast(1)
        return ArtworkTarget(px, px)
    }

    /** Large adaptive surface: the measured size bucketed up and capped. Null while unmeasured (zero/negative). */
    fun largeTarget(widthPx: Int, heightPx: Int): ArtworkTarget? {
        if (widthPx <= 0 || heightPx <= 0) return null
        fun bucket(v: Int): Int = min(((v + LARGE_BUCKET_PX - 1) / LARGE_BUCKET_PX) * LARGE_BUCKET_PX, LARGE_MAX_PX)
        return ArtworkTarget(bucket(widthPx), bucket(heightPx))
    }
}

/** Bounded retry for the large surface only: a single transient provider failure must not stick for the whole track. */
internal object ArtworkRetryPolicy {
    const val MAX_ATTEMPTS = 2
    const val RETRY_DELAY_MS = 500L
    fun shouldRetry(attemptsMade: Int): Boolean = attemptsMade < MAX_ATTEMPTS
}

/** A successfully loaded artwork that remembers which request produced it. */
internal class Retained<A>(val key: ArtworkRequestKey, val art: A)

/** What the large artwork surface knows. Ownership is request-scoped: every non-Idle state carries the key it belongs to. */
internal sealed interface ArtworkSurfaceState<out A> {
    /** Nothing requested (no artwork, or surface not measured yet): placeholder. */
    data object Idle : ArtworkSurfaceState<Nothing>

    /** [key] is loading; [retained] is the previous successful art kept visible underneath (null when retention is off/none). */
    data class Loading<A>(val key: ArtworkRequestKey, val retained: Retained<A>?) : ArtworkSurfaceState<A>

    /** [key] finished successfully. */
    data class Showing<A>(val key: ArtworkRequestKey, val art: A) : ArtworkSurfaceState<A>

    /** [key] definitively failed: placeholder, and no previous cover is kept. */
    data class Failed(val key: ArtworkRequestKey) : ArtworkSurfaceState<Nothing>
}

internal sealed interface ArtworkEvent<out A> {
    /** The current song has no artwork (or the appearance mode shows none): clear everything. */
    data object NoArtwork : ArtworkEvent<Nothing>
    data class Requested(val key: ArtworkRequestKey) : ArtworkEvent<Nothing>
    data class Succeeded<A>(val key: ArtworkRequestKey, val art: A) : ArtworkEvent<A>
    /** Definitive failure of [key] (cancellation/replacement is NOT an event; it simply never completes). */
    data class Failed(val key: ArtworkRequestKey) : ArtworkEvent<Nothing>
}

/** Pure ownership/retention reducer for the large surface (unit-tested). Late results for a superseded key are ignored. */
internal object ArtworkSurface {

    fun <A> reduce(state: ArtworkSurfaceState<A>, event: ArtworkEvent<A>, retainPrevious: Boolean = true): ArtworkSurfaceState<A> =
        when (event) {
            ArtworkEvent.NoArtwork -> ArtworkSurfaceState.Idle

            is ArtworkEvent.Requested -> when {
                // Same URI + same size already showing/loading/failed for this key: nothing to do (same-album reuse).
                currentKey(state) == event.key -> state
                else -> ArtworkSurfaceState.Loading(event.key, if (retainPrevious) retainedFrom(state) else null)
            }

            is ArtworkEvent.Succeeded ->
                if (state is ArtworkSurfaceState.Loading && state.key == event.key) ArtworkSurfaceState.Showing(event.key, event.art) else state

            is ArtworkEvent.Failed ->
                if (state is ArtworkSurfaceState.Loading && state.key == event.key) ArtworkSurfaceState.Failed(event.key) else state
        }

    /** The art to draw for [state], or null for the placeholder. */
    fun <A> displayed(state: ArtworkSurfaceState<A>): A? = when (state) {
        is ArtworkSurfaceState.Showing -> state.art
        is ArtworkSurfaceState.Loading -> state.retained?.art
        ArtworkSurfaceState.Idle, is ArtworkSurfaceState.Failed -> null
    }

    private fun <A> currentKey(state: ArtworkSurfaceState<A>): ArtworkRequestKey? = when (state) {
        is ArtworkSurfaceState.Showing -> state.key
        is ArtworkSurfaceState.Loading -> state.key
        is ArtworkSurfaceState.Failed -> state.key
        ArtworkSurfaceState.Idle -> null
    }

    private fun <A> retainedFrom(state: ArtworkSurfaceState<A>): Retained<A>? = when (state) {
        is ArtworkSurfaceState.Showing -> Retained(state.key, state.art)
        is ArtworkSurfaceState.Loading -> state.retained
        ArtworkSurfaceState.Idle, is ArtworkSurfaceState.Failed -> null
    }
}
